package dev.lydex.plugin.stream

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor

/**
 * Base class for a plug-in's provider service: extend it, implement the
 * typed handlers, and declare the subclass both as a `<service>` (exported)
 * and in the [StreamPluginContract.META_SERVICE_CLASS] meta-data. The AIDL
 * stub, JSON envelopes, and error mapping are handled here so plug-in code
 * never touches the wire format.
 *
 * Handlers run on binder threads and may block on network I/O; the host
 * wraps every call in a timeout watchdog. Throw [ProviderException] for a
 * typed failure — anything else becomes an INTERNAL error envelope.
 */
abstract class StreamProviderService : Service() {

    abstract fun onGetCapabilities(): ProviderCapabilities

    abstract fun onGetAuthState(): AuthState

    /** Required unless auth state is NOT_REQUIRED. */
    open fun onGetLoginIntent(): Intent? = null

    /** containerId null = root. */
    abstract fun onBrowse(containerId: String?, pageToken: String?, pageSize: Int): BrowsePage

    open fun onSearch(query: String, pageToken: String?, pageSize: Int): BrowsePage =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "search not supported")

    abstract fun onResolve(trackId: String, options: ResolveOptions): StreamHandle

    /** Tier-2 fallback; most providers never implement it. */
    open fun onOpenStream(trackId: String, offsetBytes: Long): ParcelFileDescriptor =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "fd-stream not supported")

    // ── Write hooks ─────────────────────────────────────────────────────
    // Default to UNSUPPORTED: a plug-in that does not override these stays
    // read-only and says so, instead of accepting a write it drops. Override
    // together with the matching ProviderCapabilities flag — the host trusts
    // the flag to decide whether to show the affordance at all.

    open fun onSetFavorite(itemId: String, favorite: Boolean): Unit =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "favorite not supported")

    open fun onSetRating(itemId: String, rating: Int): Unit =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "rating not supported")

    open fun onCreatePlaylist(name: String, trackIds: List<String>): StreamNode =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "playlist edit not supported")

    open fun onUpdatePlaylist(
        playlistId: String,
        addTrackIds: List<String>,
        removeTrackIds: List<String>,
    ): Unit =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "playlist edit not supported")

    open fun onRenamePlaylist(playlistId: String, name: String): Unit =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "playlist edit not supported")

    open fun onDeletePlaylist(playlistId: String): Unit =
        throw ProviderException(StreamPluginContract.ErrorCode.UNSUPPORTED, "playlist edit not supported")

    // ── v2 hooks ────────────────────────────────────────────────────────

    /**
     * Store [image] as the artwork for [subjectId], or clear the stored one
     * when [image] is null. Returns the new artwork URL, or null if cleared.
     *
     * The fd is owned by this call and closed by the base class afterwards.
     * Implementations must not write the image into the audio file: tag
     * rewriting on APE or FLAC risks damaging a source the user may not be
     * able to replace.
     */
    open fun onUploadArtwork(
        subjectId: String,
        image: ParcelFileDescriptor?,
        mimeType: String,
    ): String? =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "artwork upload not supported",
        )

    /**
     * Record one playback. Called at the ≥50%-accumulated-playtime mark, at
     * most once per playback; [playSessionId] makes a retry idempotent.
     */
    open fun onReportPlayback(
        trackId: String,
        playSessionId: String,
        playedMs: Long,
        completed: Boolean,
        atEpochMs: Long,
    ): Unit =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "play stats not supported",
        )

    /**
     * Change counters. Requires
     * [ProviderCapabilities.supportsRevisions]; the default refuses so a
     * plug-in built against an older contract is not mistaken for one that
     * tracks changes.
     */
    open fun onGetRevisions(): ProviderRevisions =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "revisions not supported",
        )

    /**
     * Evaluate a smart-playlist rule set server-side. Requires
     * [ProviderCapabilities.supportsSmartPlaylist].
     */
    open fun onEvaluateSmartPlaylist(
        rulesJson: String,
        matchMode: String,
        limit: Int,
    ): BrowsePage =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "smart playlist not supported",
        )

    // ── v3 hooks ────────────────────────────────────────────────────────

    /**
     * Ask the server to (re)compute ReplayGain across its content. Fire-and-
     * forget: return once the server has accepted the request. Requires
     * [ProviderCapabilities.supportsLoudnessAnalysis]; the default refuses.
     */
    open fun onAnalyzeLoudness(): Unit =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "loudness analysis not supported",
        )

    /**
     * Server-side ReplayGain coverage over the account's browsable tracks.
     * Read-only; the default refuses so a provider that cannot answer says so.
     */
    open fun onGetLoudnessStatus(): LoudnessStatus =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "loudness status not supported",
        )

    /**
     * Log the current session out: revoke server-side where possible, then drop
     * the stored session so the next [onGetAuthState] reports LOGGED_OUT. The
     * default refuses, so a provider with no login (or none that overrides this)
     * says UNSUPPORTED and the host falls back to its login UI's sign-out.
     */
    open fun onLogout(): Unit =
        throw ProviderException(
            StreamPluginContract.ErrorCode.UNSUPPORTED,
            "logout not supported",
        )

    final override fun onBind(intent: Intent?): IBinder = binder

    private fun envelope(block: () -> org.json.JSONObject): String = try {
        ProviderResponse.ok(block())
    } catch (e: ProviderException) {
        ProviderResponse.error(e.code, e.message ?: "")
    } catch (t: Throwable) {
        ProviderResponse.error(
            StreamPluginContract.ErrorCode.INTERNAL,
            t.message ?: t.javaClass.simpleName,
        )
    }

    private val binder = object : ILydexStreamProvider.Stub() {
        override fun getProtocolVersion(): Int = StreamPluginContract.PROTOCOL_VERSION

        override fun getCapabilities(): String = envelope { onGetCapabilities().toJson() }

        override fun getAuthState(): String = envelope { onGetAuthState().toJson() }

        override fun getLoginIntent(): Intent? = onGetLoginIntent()

        override fun browse(containerId: String?, pageToken: String?, pageSize: Int): String =
            envelope {
                onBrowse(
                    containerId?.takeIf { it.isNotEmpty() },
                    pageToken?.takeIf { it.isNotEmpty() },
                    pageSize,
                ).toJson()
            }

        override fun search(query: String?, pageToken: String?, pageSize: Int): String =
            envelope {
                onSearch(
                    query.orEmpty(),
                    pageToken?.takeIf { it.isNotEmpty() },
                    pageSize,
                ).toJson()
            }

        override fun resolve(trackId: String?, optionsJson: String?): String = envelope {
            val opts = optionsJson?.takeIf { it.isNotEmpty() }
                ?.let { ResolveOptions.fromJson(org.json.JSONObject(it)) }
                ?: ResolveOptions()
            onResolve(
                trackId ?: throw ProviderException(
                    StreamPluginContract.ErrorCode.NOT_FOUND, "null trackId"
                ),
                opts,
            ).toJson()
        }

        override fun openStream(trackId: String?, offsetBytes: Long): ParcelFileDescriptor =
            onOpenStream(
                trackId ?: throw ProviderException(
                    StreamPluginContract.ErrorCode.NOT_FOUND, "null trackId"
                ),
                offsetBytes,
            )

        override fun setFavorite(itemId: String?, favorite: Boolean): String = envelope {
            onSetFavorite(itemId.requireId("itemId"), favorite)
            org.json.JSONObject()
        }

        override fun setRating(itemId: String?, rating: Int): String = envelope {
            onSetRating(itemId.requireId("itemId"), rating.coerceIn(0, 5))
            org.json.JSONObject()
        }

        override fun createPlaylist(name: String?, trackIds: Array<String>?): String = envelope {
            onCreatePlaylist(name.orEmpty(), trackIds?.toList() ?: emptyList()).toJson()
        }

        override fun updatePlaylist(
            playlistId: String?,
            addTrackIds: Array<String>?,
            removeTrackIds: Array<String>?,
        ): String = envelope {
            onUpdatePlaylist(
                playlistId.requireId("playlistId"),
                addTrackIds?.toList() ?: emptyList(),
                removeTrackIds?.toList() ?: emptyList(),
            )
            org.json.JSONObject()
        }

        override fun renamePlaylist(playlistId: String?, name: String?): String = envelope {
            onRenamePlaylist(playlistId.requireId("playlistId"), name.orEmpty())
            org.json.JSONObject()
        }

        override fun deletePlaylist(playlistId: String?): String = envelope {
            onDeletePlaylist(playlistId.requireId("playlistId"))
            org.json.JSONObject()
        }

        override fun uploadArtwork(
            subjectId: String?,
            image: ParcelFileDescriptor?,
            mimeType: String?,
        ): String = envelope {
            // The fd is ours for the duration of the call; close it here so a
            // plug-in that forgets cannot leak a descriptor per upload.
            image.use {
                val url = onUploadArtwork(
                    subjectId.requireId("subjectId"),
                    it,
                    mimeType.orEmpty(),
                )
                org.json.JSONObject().apply { url?.let { u -> put("artworkUrl", u) } }
            }
        }

        override fun reportPlayback(
            trackId: String?,
            playSessionId: String?,
            playedMs: Long,
            completed: Boolean,
            atEpochMs: Long,
        ): String = envelope {
            onReportPlayback(
                trackId.requireId("trackId"),
                playSessionId.requireId("playSessionId"),
                playedMs,
                completed,
                atEpochMs,
            )
            org.json.JSONObject()
        }

        override fun getRevisions(): String = envelope { onGetRevisions().toJson() }

        override fun evaluateSmartPlaylist(
            rulesJson: String?,
            matchMode: String?,
            limit: Int,
        ): String = envelope {
            onEvaluateSmartPlaylist(
                rulesJson.orEmpty(),
                matchMode?.takeIf { it.isNotEmpty() } ?: "ALL",
                if (limit > 0) limit else 500,
            ).toJson()
        }

        override fun analyzeLoudness(): String = envelope {
            onAnalyzeLoudness()
            org.json.JSONObject()
        }

        override fun getLoudnessStatus(): String = envelope { onGetLoudnessStatus().toJson() }

        override fun logout(): String = envelope {
            onLogout()
            org.json.JSONObject()
        }

        private fun String?.requireId(field: String): String =
            this?.takeIf { it.isNotEmpty() } ?: throw ProviderException(
                StreamPluginContract.ErrorCode.NOT_FOUND, "null $field"
            )
    }
}
