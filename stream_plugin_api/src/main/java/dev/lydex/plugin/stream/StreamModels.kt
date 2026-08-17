package dev.lydex.plugin.stream

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed models for the JSON payloads crossing the AIDL boundary, with
 * hand-rolled org.json mapping (no serialization library — this module
 * must stay dependency-free for plug-in authors).
 *
 * Evolution: readers use defaults for missing fields and ignore unknown
 * ones. Writers may add fields freely.
 */

data class ProviderCapabilities(
    /** Delivery tiers, [StreamPluginContract.Tier] values. */
    val tiers: List<String>,
    val supportsSearch: Boolean,
    /**
     * Write capabilities. The host renders a favourite/rating/playlist
     * affordance ONLY where the provider claims support — an affordance that
     * silently does nothing is worse than none. All default to false so a
     * plug-in built against an older contract stays read-only rather than
     * appearing to support writes it will reject.
     */
    val supportsFavorite: Boolean = false,
    val supportsRating: Boolean = false,
    val supportsPlaylistEdit: Boolean = false,
    /**
     * Artwork upload. False for every Subsonic-family server: the protocol
     * has no cover-upload endpoint, so the host hides the affordance rather
     * than storing a local override (stream content keeps no local state).
     */
    val supportsArtworkUpload: Boolean = false,
    /**
     * v2. Per-track play count and last-played time, over the whole catalog.
     * Gates the Favorites tab's TOP_100 and RECENT_PLAYS sub-tabs, which stay
     * hidden without it — the host cannot derive either from a browse
     * snapshot, and no Subsonic-family server reports them.
     */
    val supportsPlayStats: Boolean = false,
    /**
     * v2. Server-side evaluation of the host's smart-playlist rules. Rules
     * over rating, play count, last-played, bit depth and sample rate can
     * only be answered where the whole library lives; the host holds pages,
     * not the catalog.
     */
    val supportsSmartPlaylist: Boolean = false,
    /**
     * v2. Change counters via getRevisions(). Lets the host notice that
     * somebody else edited the library — a cover downloaded server-side, an
     * album added, a rating set from another device — without re-walking the
     * catalogue to find out.
     *
     * False leaves the host invalidating only on its own writes and on the
     * user pulling to refresh. That was the shipped behaviour, and it meant a
     * server-side change stayed invisible for the life of the app process.
     */
    val supportsRevisions: Boolean = false,
    /**
     * v3. The server can (re)compute ReplayGain over its own content — loudness
     * analysis the host cannot run for remote files. Gates the "Analyze
     * loudness" action on a streaming source. Reported per session: true only
     * for an account allowed to run it (an administrator on lydex-stream), so
     * the affordance follows the account, like [supportsArtworkUpload].
     */
    val supportsLoudnessAnalysis: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tiers", JSONArray(tiers))
        put("supportsSearch", supportsSearch)
        put("supportsFavorite", supportsFavorite)
        put("supportsRating", supportsRating)
        put("supportsPlaylistEdit", supportsPlaylistEdit)
        put("supportsArtworkUpload", supportsArtworkUpload)
        put("supportsPlayStats", supportsPlayStats)
        put("supportsSmartPlaylist", supportsSmartPlaylist)
        put("supportsRevisions", supportsRevisions)
        put("supportsLoudnessAnalysis", supportsLoudnessAnalysis)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderCapabilities = ProviderCapabilities(
            tiers = o.optJSONArray("tiers")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: listOf(StreamPluginContract.Tier.DIRECT_URL),
            supportsSearch = o.optBoolean("supportsSearch", false),
            supportsFavorite = o.optBoolean("supportsFavorite", false),
            supportsRating = o.optBoolean("supportsRating", false),
            supportsPlaylistEdit = o.optBoolean("supportsPlaylistEdit", false),
            supportsArtworkUpload = o.optBoolean("supportsArtworkUpload", false),
            supportsPlayStats = o.optBoolean("supportsPlayStats", false),
            supportsSmartPlaylist = o.optBoolean("supportsSmartPlaylist", false),
            supportsRevisions = o.optBoolean("supportsRevisions", false),
            supportsLoudnessAnalysis = o.optBoolean("supportsLoudnessAnalysis", false),
        )
    }
}


/**
 * Counters that change when the provider's data does.
 *
 * Opaque strings, not numbers: the host only ever compares them with the last
 * value it saw, so a provider is free to answer with a version, a timestamp or
 * a content hash. Making them integers would invite the host to reason about
 * ordering it has no business reasoning about — "went backwards" and "changed"
 * call for the same action.
 *
 * Split by scope because the invalidations differ wildly in cost. A cover
 * appearing should drop cached artwork answers; it should not force a re-walk
 * of the catalogue, which is one browse per album.
 *
 * An empty string means "this provider does not track that scope", and the host
 * treats it as never-changing rather than as always-changed.
 */
data class ProviderRevisions(
    /** Tracks, albums, artists — anything that changes item ids or titles. */
    val catalog: String = "",
    /** Covers gained, replaced or removed. */
    val artwork: String = "",
    /** This account's own favourites, ratings and playlists. */
    val annotations: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("catalog", catalog)
        put("artwork", artwork)
        put("annotations", annotations)
    }

    companion object {
        /**
         * Numbers are accepted as well as strings — a server counting with
         * integers is the obvious implementation, and `optString` on a JSON
         * number already yields its text.
         */
        fun fromJson(o: JSONObject): ProviderRevisions = ProviderRevisions(
            catalog = o.optString("catalog", ""),
            artwork = o.optString("artwork", ""),
            annotations = o.optString("annotations", ""),
        )
    }
}

/**
 * Three-valued auth state — the single source of truth on whether login
 * exists for this provider (see design doc; no separate requiresAuth flag).
 */
sealed class AuthState {
    object NotRequired : AuthState()
    object LoggedOut : AuthState()

    /**
     * @param maxQuality session's highest entitled quality
     *   ([StreamPluginContract.Quality] value) — determined by the auth
     *   flow/subscription, so it travels with login state, or null when
     *   the provider has no quality tiers.
     */
    data class LoggedIn(val displayName: String, val maxQuality: String?) : AuthState()

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@AuthState) {
            NotRequired -> put("state", "NOT_REQUIRED")
            LoggedOut -> put("state", "LOGGED_OUT")
            is LoggedIn -> {
                put("state", "LOGGED_IN")
                put("displayName", displayName)
                maxQuality?.let { put("maxQuality", it) }
            }
        }
    }

    companion object {
        fun fromJson(o: JSONObject): AuthState = when (o.optString("state")) {
            "NOT_REQUIRED" -> NotRequired
            "LOGGED_IN" -> LoggedIn(
                displayName = o.optString("displayName", ""),
                maxQuality = o.optString("maxQuality").takeIf { it.isNotEmpty() },
            )
            else -> LoggedOut
        }
    }
}

/**
 * One browse entry. kind == [StreamPluginContract.NodeKind.TRACK] makes it
 * playable; every other kind is a container the host can browse into.
 * IDs are opaque strings and MUST be stable across sessions — the host
 * persists them in the play queue.
 */
data class StreamNode(
    val id: String,
    val title: String,
    val kind: String,
    val artist: String? = null,
    val album: String? = null,
    /**
     * v2. The album this node belongs to, by the provider's own id.
     *
     * A track's cover *is* its album's cover, so this doubles as the artwork
     * identity of a track: the host keys its artwork cache by it (two albums
     * can share a title, and a title-keyed cache makes them overwrite each
     * other) and addresses a cover upload with it, since artwork is stored per
     * album, not per track.
     */
    val albumId: String? = null,
    /**
     * v3. The track's artist, by the provider's own id — the artwork identity
     * for an artist cover the host may upload. Present on track nodes; absent
     * where there is no single artist to address.
     */
    val artistId: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    /**
     * v2. Headers the host must send when fetching [artworkUrl].
     *
     * Artwork is fetched by the host directly, not through the plug-in, so a
     * provider whose art sits behind authentication has no other way to say
     * so — the host would simply get a 401 and show no cover.
     *
     * Mirrors [StreamSegment.headers] rather than introducing a second
     * mechanism, and keeps credentials out of the URL, where they would end
     * up in logs.
     *
     * The plug-in fills this in, not the server: the plug-in is what holds
     * the session, and it is already rewriting the provider's relative
     * artwork path into an absolute URL. A server that echoed the caller's
     * own token back into a browse response would be putting a credential
     * into something the host may cache.
     */
    val artworkHeaders: Map<String, String> = emptyMap(),
    val childCount: Int? = null,
    // Format hint (structured — presentation is the host's call).
    val codec: String? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    // Library-organization metadata. Optional, but a host that groups by
    // genre or decade has no way to derive these, so a provider that has
    // them should send them.
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    /** User rating 1–5 (0/absent = unrated). Provider-side, per-track. */
    val rating: Int? = null,
    /**
     * v2. How many times this user has played the track, and when they last
     * did. Only meaningful from a provider advertising
     * [ProviderCapabilities.supportsPlayStats].
     */
    val playCount: Int? = null,
    val lastPlayedEpochMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("kind", kind)
        artist?.let { put("artist", it) }
        album?.let { put("album", it) }
        albumId?.let { put("albumId", it) }
        artistId?.let { put("artistId", it) }
        durationMs?.let { put("durationMs", it) }
        artworkUrl?.let { put("artworkUrl", it) }
        if (artworkHeaders.isNotEmpty()) put("artworkHeaders", JSONObject(artworkHeaders as Map<*, *>))
        childCount?.let { put("childCount", it) }
        codec?.let { put("codec", it) }
        bitDepth?.let { put("bitDepth", it) }
        sampleRateHz?.let { put("sampleRateHz", it) }
        genre?.let { put("genre", it) }
        year?.let { put("year", it) }
        trackNumber?.let { put("trackNumber", it) }
        rating?.let { put("rating", it) }
        playCount?.let { put("playCount", it) }
        lastPlayedEpochMs?.let { put("lastPlayedEpochMs", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): StreamNode = StreamNode(
            id = o.getString("id"),
            title = o.optString("title", ""),
            kind = o.optString("kind", StreamPluginContract.NodeKind.FOLDER),
            artist = o.optString("artist").takeIf { it.isNotEmpty() },
            album = o.optString("album").takeIf { it.isNotEmpty() },
            albumId = o.optString("albumId").takeIf { it.isNotEmpty() },
            artistId = o.optString("artistId").takeIf { it.isNotEmpty() },
            durationMs = if (o.has("durationMs")) o.getLong("durationMs") else null,
            artworkUrl = o.optString("artworkUrl").takeIf { it.isNotEmpty() },
            artworkHeaders = o.optJSONObject("artworkHeaders")?.let { h ->
                h.keys().asSequence().associateWith { h.getString(it) }
            } ?: emptyMap(),
            childCount = if (o.has("childCount")) o.getInt("childCount") else null,
            codec = o.optString("codec").takeIf { it.isNotEmpty() },
            bitDepth = if (o.has("bitDepth")) o.getInt("bitDepth") else null,
            sampleRateHz = if (o.has("sampleRateHz")) o.getInt("sampleRateHz") else null,
            genre = o.optString("genre").takeIf { it.isNotEmpty() },
            year = if (o.has("year")) o.getInt("year") else null,
            trackNumber = if (o.has("trackNumber")) o.getInt("trackNumber") else null,
            rating = if (o.has("rating")) o.getInt("rating") else null,
            playCount = if (o.has("playCount")) o.getInt("playCount") else null,
            lastPlayedEpochMs =
                if (o.has("lastPlayedEpochMs")) o.getLong("lastPlayedEpochMs") else null,
        )
    }
}

data class BrowsePage(
    val items: List<StreamNode>,
    /** Cursor for the next page; null = last page. */
    val nextPageToken: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("items", JSONArray().also { arr -> items.forEach { arr.put(it.toJson()) } })
        nextPageToken?.let { put("nextPageToken", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): BrowsePage = BrowsePage(
            items = o.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).map { StreamNode.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            nextPageToken = o.optString("nextPageToken").takeIf { it.isNotEmpty() },
        )
    }
}

data class StreamSegment(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val durationMs: Long? = null,
    val byteLenHint: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        if (headers.isNotEmpty()) put("headers", JSONObject(headers as Map<*, *>))
        durationMs?.let { put("durationMs", it) }
        byteLenHint?.let { put("byteLenHint", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): StreamSegment = StreamSegment(
            url = o.getString("url"),
            headers = o.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.getString(it) }
            } ?: emptyMap(),
            durationMs = if (o.has("durationMs")) o.getLong("durationMs") else null,
            byteLenHint = if (o.has("byteLenHint")) o.getLong("byteLenHint") else null,
        )
    }
}

/**
 * resolve() result. A direct file is the single-segment degenerate case;
 * DASH expands to init + N segments (the plug-in expands SegmentTemplate —
 * the host never sees manifest dialects). Reports the ACTUALLY delivered
 * format after entitlement clamping — never the requested one.
 */
data class StreamHandle(
    val segments: List<StreamSegment>,
    val initSegment: StreamSegment? = null,
    /** [StreamPluginContract.Container] value. */
    val container: String,
    /** Endless stream (radio): no duration, not seekable. */
    val live: Boolean = false,
    /** Epoch millis after which URLs may 403; host re-resolves ahead of it. */
    val expiresAtEpochMs: Long? = null,
    val totalDurationMs: Long? = null,
    // Delivered format (post entitlement clamp), informational for UI.
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,

    // ── v2: sample-accurate slicing ────────────────────────────────────
    //
    // The delivered bytes are a superset of the audible track, and these say
    // how much of the decoded output to throw away. That indirection is what
    // makes cue playback bit-perfect: the segments are the untouched image
    // file, so the server never decodes or re-encodes anything to hand out a
    // slice of it. A cue track starts mid-frame, so the fetch begins at the
    // frame boundary containing it and [trimStartSamples] discards the lead-in.
    //
    // The same two fields express gapless trimming for lossy formats
    // (MP3 encoder delay, AAC priming), which is the reason they are named
    // for what they do rather than for cue sheets.

    /**
     * Where this track begins inside the delivered object, in samples.
     *
     * The authoritative, decoder-agnostic answer: whatever the container, the
     * track is `sampleCount` samples starting here. A decoder that can seek
     * by sample position — APE reads its own seek table, FLAC its SEEKTABLE —
     * needs nothing else.
     */
    val startSample: Long? = null,
    /**
     * Byte position of the decodable unit containing [startSample], as a
     * precomputed shortcut so the client need not scan for it. Paired with
     * [trimStartSamples].
     *
     * Present only where entering the bitstream at that byte is self-
     * sufficient given the object's header. It is deliberately absent for
     * APE: that format's seek table holds absolute file offsets, so a decoder
     * that consults it after being pointed at a byte in the middle looks for
     * every later frame in the wrong place. For APE, seek by [startSample].
     */
    val byteOffset: Long? = null,
    /** Samples to discard from the start of the decoded output. */
    val trimStartSamples: Long? = null,
    /** Samples to discard from the end. */
    val trimEndSamples: Long? = null,
    /**
     * Audible samples in this track; playback stops there rather than at the
     * end of the stream. null = play to the end.
     */
    val sampleCount: Long? = null,
    /**
     * Identifies the underlying object. When the next track resolves to the
     * same key, its bytes continue the same bitstream, so the connection and
     * the decoder can be kept — which is also why consecutive cue tracks are
     * gapless without anyone arranging it. Different or null = start fresh.
     */
    val continuityKey: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("segments", JSONArray().also { arr -> segments.forEach { arr.put(it.toJson()) } })
        initSegment?.let { put("initSegment", it.toJson()) }
        put("container", container)
        put("live", live)
        expiresAtEpochMs?.let { put("expiresAtEpochMs", it) }
        totalDurationMs?.let { put("totalDurationMs", it) }
        bitDepth?.let { put("bitDepth", it) }
        sampleRateHz?.let { put("sampleRateHz", it) }
        startSample?.let { put("startSample", it) }
        byteOffset?.let { put("byteOffset", it) }
        trimStartSamples?.let { put("trimStartSamples", it) }
        trimEndSamples?.let { put("trimEndSamples", it) }
        sampleCount?.let { put("sampleCount", it) }
        continuityKey?.let { put("continuityKey", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): StreamHandle = StreamHandle(
            segments = o.optJSONArray("segments")?.let { arr ->
                (0 until arr.length()).map { StreamSegment.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            initSegment = o.optJSONObject("initSegment")?.let { StreamSegment.fromJson(it) },
            container = o.optString("container", StreamPluginContract.Container.FLAC),
            live = o.optBoolean("live", false),
            expiresAtEpochMs = if (o.has("expiresAtEpochMs")) o.getLong("expiresAtEpochMs") else null,
            totalDurationMs = if (o.has("totalDurationMs")) o.getLong("totalDurationMs") else null,
            bitDepth = if (o.has("bitDepth")) o.getInt("bitDepth") else null,
            sampleRateHz = if (o.has("sampleRateHz")) o.getInt("sampleRateHz") else null,
            startSample = if (o.has("startSample")) o.getLong("startSample") else null,
            byteOffset = if (o.has("byteOffset")) o.getLong("byteOffset") else null,
            trimStartSamples =
                if (o.has("trimStartSamples")) o.getLong("trimStartSamples") else null,
            trimEndSamples = if (o.has("trimEndSamples")) o.getLong("trimEndSamples") else null,
            sampleCount = if (o.has("sampleCount")) o.getLong("sampleCount") else null,
            continuityKey = o.optString("continuityKey").takeIf { it.isNotEmpty() },
        )
    }
}

data class ResolveOptions(
    /** [StreamPluginContract.Quality] value; plug-in clamps to entitlement. */
    val preferredQuality: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        preferredQuality?.let { put("preferredQuality", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ResolveOptions = ResolveOptions(
            preferredQuality = o.optString("preferredQuality").takeIf { it.isNotEmpty() },
        )
    }
}

data class ProviderError(val code: String, val message: String)

/** Host-side view of an envelope-decoded call. */
/**
 * Server-side ReplayGain coverage: [tagged] of [total] browsable tracks already
 * carry a ReplayGain tag. The host pairs it with the local library's own
 * coverage in one status card.
 */
data class LoudnessStatus(val total: Int, val tagged: Int) {
    fun toJson(): JSONObject = JSONObject().put("total", total).put("tagged", tagged)

    companion object {
        fun fromJson(o: JSONObject): LoudnessStatus =
            LoudnessStatus(total = o.optInt("total", 0), tagged = o.optInt("tagged", 0))
    }
}

sealed class ProviderResult<out T> {
    data class Ok<T>(val value: T) : ProviderResult<T>()
    data class Err(val error: ProviderError) : ProviderResult<Nothing>()
}

/**
 * The versioned envelope every String-returning AIDL method uses:
 * `{"v":1,"ok":true,"data":{...}}` or
 * `{"v":1,"ok":false,"error":{"code":"...","message":"..."}}`.
 */
object ProviderResponse {
    private const val VERSION = 1

    fun ok(data: JSONObject): String = JSONObject().apply {
        put("v", VERSION)
        put("ok", true)
        put("data", data)
    }.toString()

    fun error(code: String, message: String): String = JSONObject().apply {
        put("v", VERSION)
        put("ok", false)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
    }.toString()

    /**
     * Decode an envelope; malformed input (including null from a dead
     * binder proxy) maps to an INTERNAL error rather than throwing.
     */
    fun <T> parse(raw: String?, parser: (JSONObject) -> T): ProviderResult<T> {
        if (raw == null) {
            return ProviderResult.Err(
                ProviderError(StreamPluginContract.ErrorCode.INTERNAL, "null response")
            )
        }
        return try {
            val o = JSONObject(raw)
            if (o.optBoolean("ok", false)) {
                ProviderResult.Ok(parser(o.getJSONObject("data")))
            } else {
                val e = o.optJSONObject("error")
                ProviderResult.Err(
                    ProviderError(
                        e?.optString("code")?.takeIf { it.isNotEmpty() }
                            ?: StreamPluginContract.ErrorCode.INTERNAL,
                        e?.optString("message") ?: "unspecified provider error",
                    )
                )
            }
        } catch (t: Throwable) {
            ProviderResult.Err(
                ProviderError(
                    StreamPluginContract.ErrorCode.INTERNAL,
                    "malformed envelope: ${t.message}",
                )
            )
        }
    }
}
