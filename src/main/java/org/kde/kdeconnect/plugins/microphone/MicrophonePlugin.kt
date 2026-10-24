/*
 * SPDX-FileCopyrightText: 2026 Kevin Hahn <kevin_hahn@sil.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.microphone

import android.Manifest
import android.app.Activity
import android.util.Log
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.LifecycleHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

/**
 * Streams this device's microphone to the remote device as an endless
 * (payloadSize = -1) raw PCM payload. See docs/protocol.md in the workspace.
 *
 * The capture and the payload pump live in [MicrophoneService], a foreground
 * service with the "microphone" type, because Android does not allow
 * recording from a plain background process.
 */
@LoadablePlugin
class MicrophonePlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_microphone)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_microphone_desc)

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_MICROPHONE_REQUEST)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_MICROPHONE)

    override val requiredPermissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    override val permissionExplanation: Int = R.string.microphone_permission_explanation

    private val isStreaming: Boolean
        get() = isDeviceInitialized && MicrophoneService.isStreamingTo(device.deviceId)

    override fun getUiButtons(): List<PluginUiButton> {
        return if (isStreaming) {
            listOf(PluginUiButton(context.getString(R.string.microphone_stop), R.drawable.ic_stop) { _: Activity? ->
                stopStreaming()
            })
        } else {
            listOf(PluginUiButton(context.getString(R.string.microphone_start), R.drawable.ic_mic_black) { _: Activity? ->
                startStreaming()
            })
        }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PACKET_TYPE_MICROPHONE_REQUEST) {
            Log.e(LOG_TAG, "Unexpected packet type ${np.type}")
            return false
        }
        val start = np.getBoolean("start", true)
        if (!start) {
            // Stopping never needs a foreground service start, so always honour it.
            stopStreaming()
            return true
        }
        if (!LifecycleHelper.isInForeground) {
            // Starting a microphone foreground service from the background is not allowed
            // (Android 11+ while-in-use rules, stricter on 14+), so only start when an
            // activity of ours is visible.
            Log.i(LOG_TAG, "Ignoring microphone start request: app is not in the foreground")
            return false
        }
        startStreaming()
        return true
    }

    override fun onDestroy() {
        // Device went away, got unpaired or the plugin was disabled: never leave the mic open.
        stopStreaming()
    }

    private fun startStreaming() {
        if (!isDeviceInitialized) {
            return
        }
        MicrophoneService.start(context, device.deviceId)
    }

    private fun stopStreaming() {
        if (!isStreaming) {
            return
        }
        MicrophoneService.stop(context)
    }

    companion object {
        /**
         * Phone -> PC. Sent once per stream; the payload is the audio and lasts
         * until either side closes the payload socket.
         *
         * Body:
         * {
         *   "streamId": "a1b2c3",   // unique per start
         *   "sampleRate": 48000,
         *   "channels": 1,
         *   "format": "pcm_s16le"
         * }
         * payloadSize is -1 (endless).
         */
        const val PACKET_TYPE_MICROPHONE: String = "kdeconnect.microphone"

        /**
         * PC -> phone (reserved for v2). Body: { "start": true|false }
         */
        const val PACKET_TYPE_MICROPHONE_REQUEST: String = "kdeconnect.microphone.request"

        private const val LOG_TAG = "MicrophonePlugin"
    }
}
