package dev.lydex.plugins.subsonic

import android.content.Intent
import android.util.Log
import dev.lydex.plugin.stream.AuthState
import dev.lydex.plugin.stream.BrowsePage
import dev.lydex.plugin.stream.ProviderCapabilities
import dev.lydex.plugin.stream.ProviderException
import dev.lydex.plugin.stream.ResolveOptions
import dev.lydex.plugin.stream.StreamHandle
import dev.lydex.plugin.stream.StreamNode
import dev.lydex.plugin.stream.StreamPluginContract.Container
import dev.lydex.plugin.stream.StreamPluginContract.ErrorCode
import dev.lydex.plugin.stream.StreamPluginContract.NodeKind
import dev.lydex.plugin.stream.StreamPluginContract.Quality
import dev.lydex.plugin.stream.StreamPluginContract.Tier
import dev.lydex.plugin.stream.StreamProviderService
import dev.lydex.plugin.stream.StreamSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Subsonic provider: user's own server, so everything is plain reads over an
 * open protocol.
 *
 * Container ids are `type:id` strings, opaque to the host and stable across
 * sessions (Subsonic ids are server-persistent), which is what the contract
 * requires since they land in the persisted play queue.
 */
class SubsonicProviderService : StreamProviderService() {

    private val api by lazy { SubsonicApi(this) }

    override fun onGetCapabilities() = ProviderCapabilities(
        tiers = listOf(Tier.DIRECT_URL),
        supportsSearch = true,
        supportsFavorite = true,
        supportsRating = true,
        supportsPlaylistEdit = true,
        // No cover-upload endpoint exists anywhere in the Subsonic family;
        // the host hides the affordance rather than keeping a local override.
        supportsArtworkUpload = false,
        server = api.serverInfo(),
    )

    override fun onGetAuthState(): AuthState {
        if (!api.isConfigured) return AuthState.LoggedOut
        return try {
            api.ping()
            AuthState.LoggedIn(
                displayName = "${api.username}@${api.serverUrl.substringAfter("://")}",
                // The user's own files: whatever the library holds is what
                // they get, so there is no entitlement tier to report.
                maxQuality = Quality.HI_RES,
            )
        } catch (e: ProviderException) {
            // Distinguish "credentials rejected" from "server unreachable":
            // only the former should push the user back to the login screen.
            if (e.code == ErrorCode.AUTH_EXPIRED) AuthState.LoggedOut else throw e
        }
    }

    override fun onGetLoginIntent(): Intent =
        Intent(this, SubsonicLoginActivity::class.java)

    // ── browse ───────────────────────────────────────────────────────────

    override fun onBrowse(containerId: String?, pageToken: String?, pageSize: Int): BrowsePage {
        val offset = pageToken?.toIntOrNull() ?: 0
        val limit = pageSize.coerceIn(1, 500)

        if (containerId == null) {
            // Order is the plug-in's call — the host renders roots as given.
            // Albums leads because it is what a self-hosted library is browsed
            // by most; Starred sits last since it is often empty on a fresh
            // server and a landing tab with nothing in it reads as broken.
            return BrowsePage(
                items = listOf(
                    StreamNode("albums", "Albums", NodeKind.ALBUMS),
                    StreamNode("artists", "Artists", NodeKind.ARTISTS),
                    StreamNode("playlists", "Playlists", NodeKind.PLAYLISTS),
                    StreamNode("starred", "Starred", NodeKind.FAVORITES),
                )
            )
        }

        val (kind, id) = containerId.split(':', limit = 2)
            .let { it[0] to it.getOrNull(1).orEmpty() }

        return when (kind) {
            "starred" -> starredPage()
            "albums" -> albumListPage(offset, limit)
            "artists" -> artistsPage()
            "playlists" -> playlistsPage()
            "album" -> albumTracksPage(id)
            "artist" -> artistAlbumsPage(id)
            "playlist" -> playlistTracksPage(id)
            else -> throw ProviderException(ErrorCode.NOT_FOUND, "unknown container $containerId")
        }
    }

    /** Starred is returned whole by the server — no paging to thread. */
    private fun starredPage(): BrowsePage {
        val s = api.call("getStarred2").optJSONObject("starred2") ?: JSONObject()
        val items = mutableListOf<StreamNode>()
        items += s.optJSONArray("song").objects().map(::trackNode)
        items += s.optJSONArray("album").objects().map(::albumNode)
        items += s.optJSONArray("artist").objects().map(::artistNode)
        return BrowsePage(items = items)
    }

    private fun albumListPage(offset: Int, limit: Int): BrowsePage {
        val list = api.call(
            "getAlbumList2",
            mapOf("type" to "alphabeticalByName", "size" to "$limit", "offset" to "$offset")
        ).optJSONObject("albumList2")?.optJSONArray("album").objects()
        val next = if (list.size == limit) "${offset + limit}" else null
        return BrowsePage(items = list.map(::albumNode), nextPageToken = next)
    }

    /** getArtists returns the whole index; flatten its alphabet buckets. */
    private fun artistsPage(): BrowsePage {
        val index = api.call("getArtists").optJSONObject("artists")
            ?.optJSONArray("index").objects()
        val artists = index.flatMap { it.optJSONArray("artist").objects() }
        return BrowsePage(items = artists.map(::artistNode))
    }

    private fun playlistsPage(): BrowsePage {
        val list = api.call("getPlaylists").optJSONObject("playlists")
            ?.optJSONArray("playlist").objects()
        return BrowsePage(items = list.map(::playlistNode))
    }

    private fun albumTracksPage(albumId: String): BrowsePage {
        val songs = api.call("getAlbum", mapOf("id" to albumId))
            .optJSONObject("album")?.optJSONArray("song").objects()
        return BrowsePage(items = songs.map(::trackNode))
    }

    private fun artistAlbumsPage(artistId: String): BrowsePage {
        val albums = api.call("getArtist", mapOf("id" to artistId))
            .optJSONObject("artist")?.optJSONArray("album").objects()
        return BrowsePage(items = albums.map(::albumNode))
    }

    private fun playlistTracksPage(playlistId: String): BrowsePage {
        val songs = api.call("getPlaylist", mapOf("id" to playlistId))
            .optJSONObject("playlist")?.optJSONArray("entry").objects()
        return BrowsePage(items = songs.map(::trackNode))
    }

    override fun onSearch(query: String, pageToken: String?, pageSize: Int): BrowsePage {
        val offset = pageToken?.toIntOrNull() ?: 0
        val limit = pageSize.coerceIn(1, 200)
        val r = api.call(
            "search3",
            mapOf(
                "query" to query,
                "songCount" to "$limit", "songOffset" to "$offset",
                "albumCount" to "$limit", "albumOffset" to "$offset",
                "artistCount" to "$limit", "artistOffset" to "$offset",
            )
        ).optJSONObject("searchResult3") ?: JSONObject()
        val items = mutableListOf<StreamNode>()
        items += r.optJSONArray("song").objects().map(::trackNode)
        items += r.optJSONArray("album").objects().map(::albumNode)
        items += r.optJSONArray("artist").objects().map(::artistNode)
        val next = if (items.size >= limit) "${offset + limit}" else null
        return BrowsePage(items = items, nextPageToken = next)
    }

    // ── writes ───────────────────────────────────────────────────────────
    //
    // Container ids the host holds are prefixed ("album:x", "playlist:y")
    // because browse ids must be self-describing; the Subsonic endpoints
    // want the bare id, so every write strips the prefix. Passing a prefixed
    // id straight through is the bug this helper exists to prevent.

    private fun bareId(id: String): String = id.substringAfter(':', id)

    override fun onSetFavorite(itemId: String, favorite: Boolean) {
        val method = if (favorite) "star" else "unstar"
        val bare = bareId(itemId)
        // star/unstar take different parameter names per item type; the host
        // does not tell us the type, so infer it from the browse prefix.
        val param = when {
            itemId.startsWith("album:") -> "albumId"
            itemId.startsWith("artist:") -> "artistId"
            else -> "id"
        }
        api.call(method, mapOf(param to bare))
    }

    override fun onSetRating(itemId: String, rating: Int) {
        api.call("setRating", mapOf("id" to bareId(itemId), "rating" to "$rating"))
    }

    override fun onCreatePlaylist(name: String, trackIds: List<String>): StreamNode {
        val params = mutableListOf("name" to name)
        trackIds.forEach { params += "songId" to bareId(it) }
        val created = api.callMulti("createPlaylist", params).optJSONObject("playlist")
            ?: throw ProviderException(ErrorCode.INTERNAL, "createPlaylist returned no playlist")
        return playlistNode(created)
    }

    override fun onUpdatePlaylist(
        playlistId: String,
        addTrackIds: List<String>,
        removeTrackIds: List<String>,
    ) {
        val params = mutableListOf("playlistId" to bareId(playlistId))
        addTrackIds.forEach { params += "songIdToAdd" to bareId(it) }
        if (removeTrackIds.isNotEmpty()) {
            // Subsonic removes by INDEX, not by id, so the current entry list
            // has to be read to translate. Indices are resolved against that
            // snapshot and sent descending: removing a low index first would
            // shift every later one.
            val entries = api.call("getPlaylist", mapOf("id" to bareId(playlistId)))
                .optJSONObject("playlist")?.optJSONArray("entry").objects()
            val targets = removeTrackIds.map { bareId(it) }.toSet()
            entries.mapIndexedNotNull { index, e ->
                index.takeIf { e.optString("id") in targets }
            }.sortedDescending().forEach { params += "songIndexToRemove" to "$it" }
        }
        api.callMulti("updatePlaylist", params)
    }

    override fun onRenamePlaylist(playlistId: String, name: String) {
        api.call("updatePlaylist", mapOf("playlistId" to bareId(playlistId), "name" to name))
    }

    override fun onDeletePlaylist(playlistId: String) {
        api.call("deletePlaylist", mapOf("id" to bareId(playlistId)))
    }

    // ── node mapping ─────────────────────────────────────────────────────

    private fun trackNode(o: JSONObject) = StreamNode(
        id = o.getString("id"),
        title = o.optString("title", "Unknown"),
        kind = NodeKind.TRACK,
        artist = o.optString("artist").takeIf { it.isNotEmpty() },
        album = o.optString("album").takeIf { it.isNotEmpty() },
        durationMs = o.optLong("duration", 0L).takeIf { it > 0 }?.times(1000),
        artworkUrl = o.optString("coverArt").takeIf { it.isNotEmpty() }?.let(::coverUrl),
        codec = o.optString("suffix").takeIf { it.isNotEmpty() }?.uppercase(),
        // OpenSubsonic reports these; classic servers omit them and the host
        // simply shows no format badge until playback determines the truth.
        bitDepth = o.optInt("bitDepth", 0).takeIf { it > 0 },
        sampleRateHz = o.optInt("samplingRate", 0).takeIf { it > 0 },
        genre = o.optString("genre").takeIf { it.isNotEmpty() },
        year = o.optInt("year", 0).takeIf { it > 0 },
        trackNumber = o.optInt("track", 0).takeIf { it > 0 },
        rating = o.optInt("userRating", 0).takeIf { it > 0 },
    )

    private fun albumNode(o: JSONObject) = StreamNode(
        id = "album:${o.getString("id")}",
        title = o.optString("name").ifEmpty { o.optString("album", "Unknown") },
        kind = NodeKind.TRACK_LIST,
        artist = o.optString("artist").takeIf { it.isNotEmpty() },
        artworkUrl = o.optString("coverArt").takeIf { it.isNotEmpty() }?.let(::coverUrl),
        childCount = o.optInt("songCount", 0).takeIf { it > 0 },
    )

    private fun artistNode(o: JSONObject) = StreamNode(
        id = "artist:${o.getString("id")}",
        title = o.optString("name", "Unknown"),
        kind = NodeKind.ALBUMS,
        artworkUrl = o.optString("coverArt").takeIf { it.isNotEmpty() }?.let(::coverUrl),
        childCount = o.optInt("albumCount", 0).takeIf { it > 0 },
    )

    private fun playlistNode(o: JSONObject) = StreamNode(
        id = "playlist:${o.getString("id")}",
        title = o.optString("name", "Playlist"),
        kind = NodeKind.TRACK_LIST,
        artworkUrl = o.optString("coverArt").takeIf { it.isNotEmpty() }?.let(::coverUrl),
        childCount = o.optInt("songCount", 0).takeIf { it > 0 },
    )

    private fun coverUrl(coverArt: String): String =
        api.url("getCoverArt", mapOf("id" to coverArt, "size" to "640"))

    // ── resolve ──────────────────────────────────────────────────────────

    /**
     * Hands the host a direct URL for the original file.
     *
     * Endpoint choice is the whole bit-perfect question. `download` is
     * specified to return the stored bytes untouched, whereas `stream`
     * transcodes according to server policy — measured on Navidrome, a
     * `stream` call with a bitrate cap comes back as Ogg/Opus where the
     * source was FLAC. So `download` is preferred, with `stream&format=raw`
     * (OpenSubsonic) as the fallback for accounts that lack the download
     * permission. Either way the declared container below lets the host's
     * container check catch a server that transcoded anyway.
     */
    override fun onResolve(trackId: String, options: ResolveOptions): StreamHandle {
        val song = api.call("getSong", mapOf("id" to trackId)).optJSONObject("song")
            ?: throw ProviderException(ErrorCode.NOT_FOUND, "no song $trackId")
        val suffix = song.optString("suffix").lowercase()
        val useDownload = canDownload()
        val url = if (useDownload) {
            api.url("download", mapOf("id" to trackId))
        } else {
            api.url("stream", mapOf("id" to trackId, "format" to "raw", "maxBitRate" to "0"))
        }
        Log.i(TAG, "resolve $trackId via ${if (useDownload) "download" else "stream?format=raw"} suffix=$suffix")
        return StreamHandle(
            segments = listOf(
                StreamSegment(
                    url = url,
                    byteLenHint = song.optLong("size", 0L).takeIf { it > 0 },
                )
            ),
            container = containerFor(suffix),
            totalDurationMs = song.optLong("duration", 0L).takeIf { it > 0 }?.times(1000),
            bitDepth = song.optInt("bitDepth", 0).takeIf { it > 0 },
            sampleRateHz = song.optInt("samplingRate", 0).takeIf { it > 0 },
        )
    }

    /**
     * Probes the download permission once and caches the verdict for the
     * process. A rejection is a per-account role, not a transient failure,
     * so re-probing per track would only add latency.
     */
    private fun canDownload(): Boolean {
        downloadAllowed?.let { return it }
        val allowed = try {
            // getUser reports the account's roles directly when available.
            val user = api.call("getUser", mapOf("username" to api.username))
                .optJSONObject("user")
            user?.optBoolean("downloadRole", true) ?: true
        } catch (e: Exception) {
            // Servers that don't expose getUser to non-admins: assume yes,
            // and let a 403 on first fetch drive the fallback next launch.
            true
        }
        downloadAllowed = allowed
        Log.i(TAG, "download permission: $allowed")
        return allowed
    }

    private fun containerFor(suffix: String): String = when (suffix) {
        "flac" -> Container.FLAC
        "mp3" -> Container.MP3
        "m4a", "aac", "mp4" -> Container.MP4_AAC
        "ogg", "oga", "opus" -> Container.OGG_VORBIS
        "wav", "wave" -> Container.WAV
        "ape" -> Container.APE
        "dsf" -> Container.DSF
        // Say "unknown" instead of guessing. Declaring FLAC for anything
        // unrecognized is what made APE files fail as FLAC parse errors
        // rather than as the unsupported container they were.
        else -> Container.UNKNOWN
    }

    private companion object {
        const val TAG = "SubsonicProvider"

        @Volatile
        var downloadAllowed: Boolean? = null
    }
}

/** Null-safe JSONArray → List<JSONObject>; Subsonic omits empty arrays. */
private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
