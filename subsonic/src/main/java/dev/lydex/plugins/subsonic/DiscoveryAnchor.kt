package dev.lydex.plugins.subsonic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Discovery anchor: gives the manifest's STREAM_PLUGIN intent-filter a real
 * component so PackageManager queries can find this APK. Never invoked.
 */
class DiscoveryAnchor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty.
    }
}
