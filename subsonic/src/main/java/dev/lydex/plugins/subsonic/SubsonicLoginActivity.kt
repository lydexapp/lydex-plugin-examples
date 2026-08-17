package dev.lydex.plugins.subsonic

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Server + account entry. Plain Activity with hand-built Views: the plug-in
 * has no theme of its own, and AppCompat's delegate hard-requires one.
 *
 * "Connect" is a real round-trip, not just a save — a typo in the URL should
 * fail here with a readable reason rather than surfacing later as a broken
 * source in the host.
 */
class SubsonicLoginActivity : Activity() {

    private lateinit var api: SubsonicApi
    private lateinit var urlField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var signOutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = SubsonicApi(this)
        setContentView(buildUi())
        if (api.isConfigured) {
            urlField.setText(api.serverUrl)
            userField.setText(api.username)
            status.text = "Connected to ${api.serverType ?: "server"}" +
                if (api.isOpenSubsonic) " (OpenSubsonic)" else ""
            signOutButton.visibility = ViewGroup.VISIBLE
        }
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101012"))
            setPadding(48, 72, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "Subsonic server"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Works with Navidrome, Airsonic, Gonic, Ampache and " +
                "Jellyfin's Subsonic endpoint."
            textSize = 13f
            setTextColor(Color.parseColor("#9A9AA2"))
            setPadding(0, 12, 0, 28)
        })

        urlField = field("Server URL (http://host:4533)", InputType.TYPE_TEXT_VARIATION_URI)
        userField = field("Username", InputType.TYPE_CLASS_TEXT)
        passField = field(
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        root.addView(urlField)
        root.addView(userField)
        root.addView(passField)

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#9A9AA2"))
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { onConnect() }
        }
        root.addView(connectButton)

        signOutButton = Button(this).apply {
            text = "Sign out"
            visibility = ViewGroup.GONE
            setOnClickListener {
                api.signOut()
                passField.setText("")
                status.text = "Signed out."
                visibility = ViewGroup.GONE
            }
        }
        root.addView(signOutButton)
        return root
    }

    private fun field(hint: String, inputType: Int) = EditText(this).apply {
        this.hint = hint
        this.inputType = inputType
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#6A6A72"))
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun onConnect() {
        var url = urlField.text.toString().trim().trimEnd('/')
        val user = userField.text.toString().trim()
        val pass = passField.text.toString()
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            status.text = "Fill in the server URL, username and password."
            return
        }
        // A bare host is the common way people write it; default to http
        // since self-hosted servers on a LAN usually have no certificate.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        api.saveCredentials(url, user, pass)
        status.text = "Connecting…"
        connectButton.isEnabled = false
        thread(name = "subsonic-connect") {
            val result = runCatching {
                api.ping()
                api.openSubsonicExtensions()
            }
            runOnUiThread {
                connectButton.isEnabled = true
                result.fold(
                    onSuccess = { extensions ->
                        status.text = buildString {
                            append("Connected to ${api.serverType ?: "server"}")
                            if (api.isOpenSubsonic) append(" (OpenSubsonic)")
                            if (extensions.isNotEmpty()) {
                                append("\nExtensions: ${extensions.sorted().joinToString(", ")}")
                            }
                        }
                        signOutButton.visibility = ViewGroup.VISIBLE
                        // The host re-polls auth state on resume, so simply
                        // returning completes the handoff.
                        finish()
                    },
                    onFailure = { e ->
                        // Keep the entered values on screen so a typo can be
                        // corrected without retyping everything.
                        status.text = "Failed: ${e.message}"
                    }
                )
            }
        }
    }
}
