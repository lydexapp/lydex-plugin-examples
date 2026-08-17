package dev.lydex.plugin.stream

/**
 * Constants shared by the Lydex host and stream-provider plug-ins.
 *
 * Discovery mirrors the DSP plug-in mechanism: the plug-in APK declares a
 * no-op BroadcastReceiver with [DISCOVERY_ACTION] so PackageManager queries
 * can find the package, plus `<meta-data>` entries on `<application>`
 * describing the plug-in. Unlike DSP plug-ins (wasm payload), a stream
 * plug-in's payload is a bound service ([META_SERVICE_CLASS]) implementing
 * [ILydexStreamProvider].
 */
object StreamPluginContract {
    /**
     * Bump only for changes the JSON/append evolution rules can't absorb.
     *
     * v2 adds sample-accurate slicing to StreamHandle (cue tracks and gapless
     * trimming), artwork upload, and playback reporting. A v1 plug-in keeps
     * working unchanged: every added field is optional and every added verb
     * has an UNSUPPORTED default, and the registry only rejects plug-ins
     * newer than the host.
     */
    const val PROTOCOL_VERSION = 3

    const val DISCOVERY_ACTION = "dev.lydex.intent.action.STREAM_PLUGIN"

    // <meta-data> keys on the plug-in's <application> element.
    const val META_ID = "dev.lydex.plugin.id"
    const val META_KIND = "dev.lydex.plugin.kind"
    const val META_DISPLAY_NAME = "dev.lydex.plugin.displayName"
    const val META_PROTOCOL_VERSION = "dev.lydex.plugin.protocolVersion"
    const val META_SERVICE_CLASS = "dev.lydex.plugin.serviceClass"

    const val KIND_STREAM = "stream"

    /** `kind` values for browse nodes. Hosts render unknown kinds as FOLDER. */
    object NodeKind {
        const val TRACK = "TRACK"
        const val FAVORITES = "FAVORITES"
        const val PLAYLISTS = "PLAYLISTS"
        const val ALBUMS = "ALBUMS"
        const val ARTISTS = "ARTISTS"
        const val TRACK_LIST = "TRACK_LIST"
        const val FOLDER = "FOLDER"

        /**
         * v2. Whole-catalog containers behind the Favorites tab's TOP_RATED,
         * TOP_100 and RECENT_PLAYS sub-tabs.
         *
         * These exist because the Subsonic family cannot express them: it has
         * no "list every rated song" endpoint, and no per-track play count or
         * last-played time at all, so the host was left rendering TOP_RATED
         * over just the favourites pool and hiding the other two entirely. A
         * provider that advertises [ProviderCapabilities.supportsPlayStats]
         * answers these over the whole library.
         */
        const val TOP_RATED = "TOP_RATED"
        const val TOP_PLAYED = "TOP_PLAYED"
        const val RECENT_PLAYS = "RECENT_PLAYS"
    }

    /**
     * `container` values in StreamHandle.
     *
     * Declare what the bytes actually are. The host dispatches decoders off
     * this string, so a wrong or guessed value surfaces as a bitstream
     * error blamed on the file. When the container is genuinely unknown,
     * send [UNKNOWN] and let the host probe rather than naming a format.
     */
    object Container {
        const val FLAC = "FLAC"
        const val MP4_FLAC = "MP4_FLAC"
        const val MP4_AAC = "MP4_AAC"
        const val MP3 = "MP3"
        const val AAC = "AAC"
        const val OGG_VORBIS = "OGG_VORBIS"
        const val WAV = "WAV"
        /** Monkey's Audio. Host requires a declared Content-Length. */
        const val APE = "APE"
        /** DSD in a DSF container; follows the host's DoP/native/PCM setting. */
        const val DSF = "DSF"
        const val UNKNOWN = "UNKNOWN"
    }

    /** Well-known quality labels (maxQuality / preferredQuality). */
    object Quality {
        const val LOW = "LOW"
        const val HIGH = "HIGH"
        const val LOSSLESS = "LOSSLESS"
        const val HI_RES = "HI_RES"
    }

    /** Delivery tiers a provider supports (ProviderCapabilities.tiers). */
    object Tier {
        const val DIRECT_URL = "direct-url"
        const val FD_STREAM = "fd-stream"
    }

    object ErrorCode {
        const val AUTH_EXPIRED = "AUTH_EXPIRED"

        /**
         * v2. The session is valid but this account may not do that.
         *
         * Distinct from [AUTH_EXPIRED], which sends the user back to the
         * login screen, and from [UNSUPPORTED], which says the provider
         * cannot do it at all. On a multi-user server the same call succeeds
         * for one account and is refused for another, and re-authenticating
         * will not help.
         */
        const val FORBIDDEN = "FORBIDDEN"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val NETWORK = "NETWORK"
        const val NOT_FOUND = "NOT_FOUND"
        const val REGION_BLOCKED = "REGION_BLOCKED"
        const val UNSUPPORTED = "UNSUPPORTED"
        const val INTERNAL = "INTERNAL"
    }
}

/**
 * Thrown by plug-in handlers to signal a typed failure; the service base
 * class converts it into an error envelope. Anything else thrown becomes
 * [StreamPluginContract.ErrorCode.INTERNAL].
 */
class ProviderException(
    val code: String,
    message: String,
) : Exception(message)
