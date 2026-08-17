package dev.lydex.plugins.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Exists only to give the manifest's intent-filter a real component to attach
 * to, so PackageManager queries can find this APK. We never broadcast
 * `dev.lydex.intent.action.DSP_PLUGIN` — the host scans the package list,
 * reads the meta-data, and loads the .wasm out of assets/ directly.
 */
class DiscoveryAnchor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty.
    }
}
