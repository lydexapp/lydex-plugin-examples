package dev.lydex.plugins.subsonic

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import dev.lydex.plugin.stream.ProviderException
import dev.lydex.plugin.stream.StreamPluginContract.ErrorCode
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic API client. One class holds the server config, the auth scheme,
 * and every request — a Subsonic server is a single host with a flat
 * `/rest/<method>` surface, so there is nothing to spread across files.
 *
 * Auth is the standard salted-token scheme: `t=md5(password + salt)` with a
 * fresh salt per request, so the password never travels. (Plain `p=` is also
 * in the spec but sends the password on every call — not used.)
 */
class SubsonicApi(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        private set(v) = prefs.edit().putString(KEY_URL, v.trimEnd('/')).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        private set(v) = prefs.edit().putString(KEY_USER, v).apply()

    /**
     * Stored because the salted-token scheme needs it to derive a token per
     * request. App-private storage; a Subsonic password unlocks only the
     * user's own media server.
     */
    private var password: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PASS, v).apply()

    /** Server-reported capabilities, filled in by [ping]. */
    var serverType: String?
        get() = prefs.getString(KEY_SERVER_TYPE, null)
        private set(v) = prefs.edit().putString(KEY_SERVER_TYPE, v).apply()

    var isOpenSubsonic: Boolean
        get() = prefs.getBoolean(KEY_OPEN_SUBSONIC, false)
        private set(v) = prefs.edit().putBoolean(KEY_OPEN_SUBSONIC, v).apply()

    val isConfigured: Boolean get() = serverUrl.isNotEmpty() && username.isNotEmpty()

    fun saveCredentials(url: String, user: String, pass: String) {
        serverUrl = url
        username = user
        password = pass
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }

    // ── request building ──────────────────────────────────────────────────

    /**
     * Builds a fully-authenticated URL for [method]. Public because resolve
     * hands stream URLs to the host, which fetches them from Rust — the
     * credentials must be embedded in the URL itself since the host's ingest
     * layer knows nothing about Subsonic auth.
     */
    fun url(method: String, params: Map<String, String> = emptyMap()): String =
        urlMulti(method, params.map { it.key to it.value })

    /** [url] allowing repeated parameter names; see [callMulti]. */
    fun urlMulti(method: String, params: List<Pair<String, String>>): String {
        if (!isConfigured) {
            throw ProviderException(ErrorCode.INTERNAL, "no server configured")
        }
        val salt = randomSalt()
        val token = md5(password + salt)
        val b = Uri.parse("$serverUrl/rest/$method").buildUpon()
            .appendQueryParameter("u", username)
            .appendQueryParameter("t", token)
            .appendQueryParameter("s", salt)
            .appendQueryParameter("v", API_VERSION)
            .appendQueryParameter("c", CLIENT_NAME)
            .appendQueryParameter("f", "json")
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.build().toString()
    }

    /**
     * Calls [method] and returns the `subsonic-response` object.
     *
     * Subsonic reports failures inside a 200 body (`status:"failed"` plus an
     * error code), so HTTP status alone is not enough — the codes are mapped
     * onto the host's typed errors here.
     */
    fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject =
        callMulti(method, params.map { it.key to it.value })

    /**
     * [call] for endpoints that take a REPEATED parameter — createPlaylist
     * takes many `songId`, updatePlaylist many `songIdToAdd` /
     * `songIndexToRemove`. A Map cannot express those, and collapsing them to
     * one value silently adds a single track instead of the whole selection.
     */
    fun callMulti(method: String, params: List<Pair<String, String>>): JSONObject {
        val target = urlMulti(method, params)
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ProviderException(ErrorCode.NETWORK, "$method: HTTP $code")
            }
            conn.inputStream.bufferedReader().readText()
        } catch (e: ProviderException) {
            throw e
        } catch (e: IOException) {
            throw ProviderException(ErrorCode.NETWORK, "$method: ${e.message}")
        } finally {
            conn.disconnect()
        }

        val response = try {
            JSONObject(body).getJSONObject("subsonic-response")
        } catch (e: Exception) {
            throw ProviderException(
                ErrorCode.INTERNAL,
                "$method: malformed response (${body.take(120)})"
            )
        }
        if (response.optString("status") != "ok") {
            val err = response.optJSONObject("error")
            val code = err?.optInt("code", -1) ?: -1
            val message = err?.optString("message") ?: "unknown error"
            Log.w(TAG, "$method failed: $code $message")
            // Codes per the Subsonic spec.
            throw when (code) {
                40 -> ProviderException(ErrorCode.AUTH_EXPIRED, "wrong username or password")
                41, 42, 43 -> ProviderException(
                    ErrorCode.AUTH_EXPIRED, "authentication mechanism rejected: $message"
                )
                50 -> ProviderException(ErrorCode.AUTH_EXPIRED, "user not authorized: $message")
                60 -> ProviderException(
                    ErrorCode.REGION_BLOCKED, "server trial expired: $message"
                )
                70 -> ProviderException(ErrorCode.NOT_FOUND, message)
                30 -> ProviderException(
                    ErrorCode.UNSUPPORTED, "server too old for this request: $message"
                )
                else -> ProviderException(ErrorCode.INTERNAL, "$code: $message")
            }
        }
        return response
    }

    /** Verifies credentials and records server capabilities. */
    fun ping(): JSONObject {
        val r = call("ping")
        serverType = r.optString("type").takeIf { it.isNotEmpty() }
        isOpenSubsonic = r.optBoolean("openSubsonic", false)
        return r
    }

    /**
     * Extensions the server advertises (OpenSubsonic only). Used to decide
     * whether `format=raw` is honoured; failure is not an error — plenty of
     * servers predate the endpoint.
     */
    fun openSubsonicExtensions(): Set<String> {
        if (!isOpenSubsonic) return emptySet()
        return try {
            val arr = call("getOpenSubsonicExtensions")
                .optJSONArray("openSubsonicExtensions") ?: return emptySet()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun randomSalt(): String {
        val buf = ByteArray(8)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "SubsonicApi"
        private const val PREFS = "subsonic_config"
        private const val KEY_URL = "server_url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_SERVER_TYPE = "server_type"
        private const val KEY_OPEN_SUBSONIC = "open_subsonic"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000

        /**
         * 1.16.1 is the last classic Subsonic revision and what
         * OpenSubsonic servers report; asking for more makes older servers
         * reject the request outright (error 30).
         */
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Lydex"
    }
}
