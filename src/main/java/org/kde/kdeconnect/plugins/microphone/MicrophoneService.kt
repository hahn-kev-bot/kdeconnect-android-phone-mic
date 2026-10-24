/*
 * SPDX-FileCopyrightText: 2026 Kevin Hahn <kevin_hahn@sil.org>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.microphone

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service (type "microphone") that owns the [AudioRecord] and feeds it
 * to the remote device as the payload of a single [MicrophonePlugin.PACKET_TYPE_MICROPHONE]
 * packet with payloadSize -1. The payload is pumped by the normal KDE Connect link code
 * ([org.kde.kdeconnect.backends.lan.LanLink.sendPayload]) on a background thread; this
 * service only has to keep the recorder alive and stop when the pump finishes.
 *
 * Only one stream is active at a time. Starting a stream for another device replaces it.
 */
class MicrophoneService : Service() {

    private class Session(val deviceId: String, val packet: NetworkPacket, val stream: MicrophoneStream)

    private var session: Session? = null // guarded by synchronized(this)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
        val device = KdeConnect.getInstance().getDevice(deviceId)

        // We were started with startForegroundService(), so we must call startForeground()
        // promptly on every path, including the stop action, or Android kills the app.
        if (!enterForeground(buildNotification(device))) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            stopSelf() // onDestroy() stops the stream
            return START_NOT_STICKY
        }

        if (device == null || !startStreaming(device)) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: SecurityException) {
            // Android 14+: missing FOREGROUND_SERVICE_MICROPHONE / RECORD_AUDIO, or type not allowed right now
            Log.e(LOG_TAG, "Couldn't start microphone foreground service", e)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+): started from the background
            Log.e(LOG_TAG, "Couldn't start microphone foreground service", e)
        }
        return false
    }

    private fun buildNotification(device: Device?): Notification {
        val stopIntent = Intent(this, MicrophoneService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 0, stopIntent, UPDATE_IMMUTABLE_FLAGS)
        } else {
            PendingIntent.getService(this, 0, stopIntent, UPDATE_IMMUTABLE_FLAGS)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = "org.kde.kdeconnect.MICROPHONE_NOTIFICATION"
            if (device != null) {
                putExtra(MainActivity.EXTRA_DEVICE_ID, device.deviceId)
            }
        }
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, UPDATE_IMMUTABLE_FLAGS)

        return NotificationCompat.Builder(this, NotificationHelper.Channels.MICROPHONE).apply {
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(getString(R.string.microphone_notification_title))
            if (device != null) {
                setContentText(getString(R.string.microphone_notification_text, device.name))
            }
            setContentIntent(openPendingIntent)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            addAction(R.drawable.ic_stop, getString(R.string.microphone_stop), stopPendingIntent)
        }.build()
    }

    /**
     * Opens the recorder and hands the packet to the device. Returns false if nothing was started.
     */
    private fun startStreaming(device: Device): Boolean {
        if (!device.isReachable || !device.supportsPacketType(MicrophonePlugin.PACKET_TYPE_MICROPHONE)) {
            Log.w(LOG_TAG, "${device.name} is not reachable or doesn't accept microphone streams")
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(LOG_TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        val newSession: Session
        synchronized(this) {
            stopStreamingLocked()

            val stream = MicrophoneStream.open() ?: return false
            val np = NetworkPacket(MicrophonePlugin.PACKET_TYPE_MICROPHONE)
            np["streamId"] = UUID.randomUUID().toString()
            np["sampleRate"] = MicrophoneStream.SAMPLE_RATE
            np["channels"] = MicrophoneStream.CHANNELS
            np["format"] = MicrophoneStream.FORMAT
            np.payload = NetworkPacket.Payload(stream, -1L)

            newSession = Session(device.deviceId, np, stream)
            // The link closes the payload when the pump ends: PC closed the socket, never
            // connected (10 s accept timeout), link lost or the packet was canceled.
            stream.onClosed = { onStreamEnded(newSession) }
            session = newSession
        }
        streamingDeviceId = device.deviceId
        notifyUi(device.deviceId)

        Log.i(LOG_TAG, "Starting microphone stream to ${device.name}")
        device.sendPacket(newSession.packet, object : Device.SendPacketStatusCallback() {
            override fun onSuccess() {
                // Called once the packet body is on the wire; the payload pump is still running.
            }

            override fun onFailure(e: Throwable) {
                Log.e(LOG_TAG, "Microphone packet failed", e)
                onStreamEnded(newSession)
            }
        })
        return true
    }

    /** Called from the payload pump thread (or the send callback) when the stream is over. */
    private fun onStreamEnded(ended: Session) {
        synchronized(this) {
            if (session !== ended) {
                return // already replaced or stopped by us
            }
            session = null
        }
        ended.stream.shutdown()
        streamingDeviceId = null
        notifyUi(ended.deviceId)
        Log.i(LOG_TAG, "Microphone stream ended")
        stopSelf()
    }

    /** User- or plugin-initiated stop. Safe to call from any thread and when nothing is running. */
    fun stopStreaming() {
        val deviceId: String
        synchronized(this) {
            deviceId = session?.deviceId ?: return
            stopStreamingLocked()
        }
        streamingDeviceId = null
        notifyUi(deviceId)
    }

    private fun stopStreamingLocked() {
        val current = session ?: return
        session = null
        // Canceling makes the pump loop exit on its next iteration; shutting the stream down
        // makes that iteration's read() return -1 at once and releases the recorder.
        current.packet.cancel()
        current.stream.shutdown()
        Log.i(LOG_TAG, "Stopped microphone stream")
    }

    private fun notifyUi(deviceId: String) {
        // Re-queries Plugin.getUiButtons() so the Start/Stop label follows the state
        KdeConnect.getInstance().getDevice(deviceId)?.onPluginsChanged()
    }

    /**
     * An [InputStream] over an [AudioRecord]. Every read() returns at most one 10 ms chunk, so a
     * call blocks only until that much audio is available, and returns -1 once the stream is shut down.
     */
    private class MicrophoneStream private constructor(private val record: AudioRecord) : InputStream() {
        @Volatile
        var onClosed: (() -> Unit)? = null

        private val lock = Any()
        private var released = false // guarded by lock
        private var flushed = false // guarded by lock
        private val closeReported = AtomicBoolean(false)

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }
            synchronized(lock) {
                if (released) {
                    return -1
                }
                if (!flushed) {
                    // The recorder has been running since Start, but the first read only happens once the
                    // remote end connected to the payload socket. Everything captured before that is stale
                    // and would become permanent delay, so throw it away.
                    flushed = true
                    val scratch = ByteArray(CHUNK_BYTES * 4)
                    var discarded = 0
                    while (true) {
                        val got = record.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING)
                        if (got <= 0) break
                        discarded += got
                        if (got < scratch.size) break
                    }
                    Log.i(LOG_TAG, "Discarded $discarded bytes of audio captured before the stream connected")
                }
                val n = record.read(b, off, min(len, CHUNK_BYTES))
                if (n < 0) {
                    Log.e(LOG_TAG, "AudioRecord.read failed: $n")
                    return -1
                }
                return n
            }
        }

        /** Stops and releases the recorder; subsequent reads return -1. Idempotent. */
        fun shutdown() {
            synchronized(lock) {
                if (released) {
                    return
                }
                released = true
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(LOG_TAG, "AudioRecord.stop failed", e)
                }
                record.release()
            }
        }

        /** Called by [NetworkPacket.Payload.close] when the pump is done. */
        override fun close() {
            shutdown()
            if (closeReported.compareAndSet(false, true)) {
                onClosed?.invoke()
            }
        }

        companion object {
            const val SAMPLE_RATE = 48000
            const val CHANNELS = 1
            const val FORMAT = "pcm_s16le"
            private const val BYTES_PER_FRAME = 2 * CHANNELS
            private const val CHUNK_MS = 10
            private const val CHUNK_BYTES = SAMPLE_RATE * CHUNK_MS / 1000 * BYTES_PER_FRAME // 960

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            fun open(): MicrophoneStream? {
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
                if (minBufferSize <= 0) {
                    Log.e(LOG_TAG, "48 kHz mono PCM16 capture is not supported on this device ($minBufferSize)")
                    return null
                }
                val bufferSize = max(minBufferSize * 2, CHUNK_BYTES * 8)
                val record = try {
                    AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channelConfig, encoding, bufferSize)
                } catch (e: IllegalArgumentException) {
                    Log.e(LOG_TAG, "Couldn't create AudioRecord", e)
                    return null
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(LOG_TAG, "AudioRecord failed to initialize")
                    record.release()
                    return null
                }
                try {
                    record.startRecording()
                } catch (e: IllegalStateException) {
                    Log.e(LOG_TAG, "AudioRecord.startRecording failed", e)
                    record.release()
                    return null
                }
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(LOG_TAG, "AudioRecord is not recording (microphone busy?)")
                    record.release()
                    return null
                }
                return MicrophoneStream(record)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "MicrophoneService"
        private const val NOTIFICATION_ID = 0x4D1C // "MIC"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val ACTION_STOP = "org.kde.kdeconnect.plugins.microphone.STOP"
        private const val UPDATE_IMMUTABLE_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        @Volatile
        private var instance: MicrophoneService? = null

        /** Device id of the active stream, or null. */
        @Volatile
        private var streamingDeviceId: String? = null

        fun isStreamingTo(deviceId: String): Boolean = streamingDeviceId == deviceId

        /**
         * Must be called while the app is in the foreground (a visible activity), or Android 12+
         * refuses the foreground service start and the service stops itself.
         */
        fun start(context: Context, deviceId: String) {
            val intent = Intent(context, MicrophoneService::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // Cancel the packet right away (works from any process state), then let the
            // service wind down. stopService() is a no-op if it's not running.
            instance?.stopStreaming()
            context.stopService(Intent(context, MicrophoneService::class.java))
        }
    }
}
