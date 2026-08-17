package dev.lydex.plugin.stream;

import android.content.Intent;
import android.os.ParcelFileDescriptor;

/**
 * Control-plane contract between the Lydex host and a stream-provider
 * plug-in APK's bound service.
 *
 * Wire format: every complex payload travels as a JSON string in a
 * versioned response envelope (see ProviderResponse in the shared Kotlin
 * layer). JSON — not Parcelables — because host and plug-in ship
 * independently: unknown JSON fields are ignored by old readers, whereas
 * Parcelable field-order evolution breaks silently across version skew.
 * Framework types (Intent, ParcelFileDescriptor) are the only Parcelables
 * used; their wire format is owned by the platform.
 *
 * Evolution rules:
 *  - Methods are appended at the END of this interface, never reordered or
 *    re-signed (AIDL transaction ids are positional).
 *  - JSON payloads may gain fields freely; readers use defaults for
 *    missing fields and ignore unknown ones.
 *  - PROTOCOL_VERSION in StreamPluginContract bumps only when a change is
 *    incompatible despite the rules above.
 *
 * Threading: the host calls every method from a background dispatcher and
 * wraps each call in a timeout watchdog. Implementations may block (do
 * network I/O) but must tolerate the host abandoning a slow call.
 */
interface ILydexStreamProvider {
    /** Contract revision implemented by the plug-in. */
    int getProtocolVersion();

    /** JSON envelope of ProviderCapabilities. */
    String getCapabilities();

    /**
     * JSON envelope of AuthState: NOT_REQUIRED | LOGGED_OUT | LOGGED_IN.
     * The single source of truth on whether login exists for this
     * provider — there is no separate requiresAuth capability.
     */
    String getAuthState();

    /**
     * Intent the host fires to open the plug-in's login UI, or null when
     * auth state is NOT_REQUIRED. The host re-polls getAuthState() when it
     * returns to the foreground — there is no activity-result contract.
     */
    Intent getLoginIntent();

    /**
     * JSON envelope of BrowsePage listing a container's children.
     * containerId null/empty = the provider's root. Cursor paging:
     * pageToken from the previous page, null/empty for the first.
     */
    String browse(String containerId, String pageToken, int pageSize);

    /** JSON envelope of BrowsePage with full-catalog search results. */
    String search(String query, String pageToken, int pageSize);

    /**
     * JSON envelope of StreamHandle for a playable track. optionsJson is a
     * ResolveOptions JSON (quality preference etc.); the plug-in clamps to
     * the session's actual entitlement and the handle reports what will
     * really be delivered.
     */
    String resolve(String trackId, String optionsJson);

    /**
     * Tier-2 fallback (contract-defined, host support deferred): the
     * plug-in streams a normalized contiguous container bytestream from
     * offsetBytes. Pipes do not seek — the host re-opens at a new offset.
     */
    ParcelFileDescriptor openStream(String trackId, long offsetBytes);

    // ── Write verbs (appended; see the evolution rules above) ───────────
    //
    // For provider content the server is the single source of truth, so the
    // host never stores these locally — it forwards the user's intent here
    // and re-reads. Each returns a ProviderResponse envelope so a refusal is
    // a typed error rather than a silent no-op. A plug-in that does not
    // advertise the matching capability may return UNSUPPORTED.

    /**
     * Star/unstar an item (track, album or artist id as the provider issued
     * it). Envelope payload is empty on success.
     */
    String setFavorite(String itemId, boolean favorite);

    /**
     * Rate an item 1–5, or 0 to clear the rating. Granularity is the
     * provider's: Subsonic rates per track and per album independently.
     */
    String setRating(String itemId, int rating);

    /** Create a playlist, optionally seeded. Payload: the new StreamNode. */
    String createPlaylist(String name, in String[] trackIds);

    /**
     * Add/remove tracks in a playlist. Both arrays may be empty; removal is
     * by track id, which requires the provider to resolve duplicates itself.
     */
    String updatePlaylist(String playlistId, in String[] addTrackIds, in String[] removeTrackIds);

    /** Rename a playlist. Payload is empty on success. */
    String renamePlaylist(String playlistId, String name);

    /** Delete a playlist. Payload is empty on success. */
    String deletePlaylist(String playlistId);

    // ── v2 verbs (appended; see the evolution rules above) ──────────────

    /**
     * Replace the artwork of an album, track, artist or playlist. Passing a
     * null image clears a previously uploaded one. Payload: the new
     * artworkUrl, or empty when cleared.
     *
     * The image travels as an fd, not a byte array — binder carries the
     * descriptor and the bytes never cross as a parcel. Requires
     * ProviderCapabilities.supportsArtworkUpload, which is computed per
     * session: on a multi-user server the same provider reports true for an
     * account allowed to edit shared library metadata and false for one that
     * is not, so the host's affordance follows the account rather than the
     * server.
     *
     * Providers must not write the image back into the audio file. Rewriting
     * tags on APE or FLAC risks corrupting a file the user cannot re-rip.
     */
    String uploadArtwork(String subjectId, in ParcelFileDescriptor image, String mimeType);

    /**
     * Report that the user played a track, for play-count and last-played
     * bookkeeping. The host calls this once per playback, after a few
     * seconds of ACCUMULATED playing time — accumulated, not position, so a
     * failed load or scrubbing to the end does not count as a listen, and a
     * genuine one registers as promptly as the local library records its
     * own plays.
     *
     * playSessionId identifies one playback and makes the call idempotent:
     * a retry after a network failure must not count twice. completed is
     * advisory (>=95% played) and drives nothing today.
     *
     * Requires ProviderCapabilities.supportsPlayStats.
     */
    String reportPlayback(
        String trackId,
        String playSessionId,
        long playedMs,
        boolean completed,
        long atEpochMs);

    /**
     * JSON envelope of ProviderRevisions: counters that change when the
     * provider's data does. The host polls this to learn its cached copy is
     * stale, which is the only cheap way to ask — the alternative is re-walking
     * the catalog, one browse per album, to discover that nothing moved.
     *
     * Split by scope so the host invalidates only what changed: a cover
     * appearing must not cost a re-walk of every album. Counters are opaque and
     * only ever compared for equality with the last value seen; a provider may
     * implement them as a version, a timestamp or a hash, and need not make
     * them increase.
     *
     * Requires ProviderCapabilities.supportsRevisions. Without it the host
     * falls back to invalidating on its own writes and on explicit refresh,
     * which is correct but blind to changes made elsewhere.
     */
    String getRevisions();

    /**
     * JSON envelope of a BrowsePage: the tracks matching a smart-playlist rule
     * set, evaluated over the WHOLE library.
     *
     * rulesJson is the host's own SmartPlaylistJson encoding, passed through
     * untouched — the server parses the identical shape. matchMode is "ALL" or
     * "ANY". Half the rules (rating, play count, last-played, bit depth, sample
     * rate) read what only the server holds across the whole catalogue, so the
     * host — which has pages, not a library — cannot answer them itself at any
     * size. That is the whole reason this crosses the wire.
     *
     * Requires ProviderCapabilities.supportsSmartPlaylist.
     */
    String evaluateSmartPlaylist(String rulesJson, String matchMode, int limit);

    // ── v3 verbs (appended; see the evolution rules above) ──────────────

    /**
     * Ask the server to (re)compute ReplayGain across its own content —
     * loudness analysis the host cannot do for remote files. Fire-and-forget:
     * the server runs the work in the background, so the envelope payload is
     * empty on success and only reports that the request was accepted.
     *
     * Requires ProviderCapabilities.supportsLoudnessAnalysis, which a provider
     * reports true only for a session allowed to run it (on lydex-stream, an
     * administrator account) — so the host's affordance follows the account,
     * exactly as artwork upload does.
     */
    String analyzeLoudness();

    /**
     * JSON envelope of LoudnessStatus { total, tagged }: how many of the
     * account's browsable tracks already carry ReplayGain. Lets the host show
     * server-side coverage next to the local library's. Read-only and cheap; no
     * capability gate — a provider that cannot answer returns UNSUPPORTED.
     */
    String getLoudnessStatus();

    /**
     * Log the current session out: revoke the token server-side where the
     * provider can, then drop the stored credentials so the provider returns to
     * LOGGED_OUT. Envelope payload is empty on success. A provider whose auth is
     * NOT_REQUIRED, or one that keeps no revocable session, may return
     * UNSUPPORTED — the host then falls back to opening the login UI, which
     * carries its own sign-out.
     */
    String logout();
}
