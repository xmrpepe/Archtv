package dev.archdock.audio

import dev.archdock.core.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Plays the guest's PulseAudio output on Android.
 *
 * In the guest, /opt/archdock/config/pulse/archdock.pa makes a null sink the
 * default output, exposes module-native-protocol-tcp on 127.0.0.1:4713 for
 * clients, and streams the null sink's monitor as raw s16le/48 kHz/stereo
 * with module-simple-protocol-tcp on 127.0.0.1:4714. This class reads that
 * stream and writes it to a low-latency AAudio stream; Android routes media
 * to HDMI while a TV is connected, so sound comes out of the TV.
 */
class AudioBridge(private val port: Int = 4714) {
    data class Status(val connected: Boolean = false, val dropped: Long = 0, val bufferFrames: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var socket: Socket? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-bridge", isDaemon = true, priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        worker?.interrupt()
        worker = null
        _status.value = Status()
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var backoff = 250L
        while (running) {
            var handle = 0L
            try {
                val s = Socket().apply { tcpNoDelay = true; receiveBufferSize = 64 * 1024 }
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                socket = s
                handle = NativeAudio.open(RATE, CHANNELS)
                if (handle == 0L) throw IOException("AAudio stream could not be opened")
                Logs.i(Logs.Channel.AUDIO, "PulseAudio stream connected (AAudio buffer ${NativeAudio.latencyFrames(handle)} frames)")
                _status.value = Status(connected = true, bufferFrames = NativeAudio.latencyFrames(handle))
                backoff = 250L
                val input = s.getInputStream()
                // 5 ms chunks keep latency low; partial frames are carried over.
                val buf = ByteArray(RATE / 200 * FRAME_BYTES)
                var have = 0
                var lastReport = System.currentTimeMillis()
                while (running) {
                    val r = input.read(buf, have, buf.size - have)
                    if (r < 0) throw IOException("PulseAudio closed the stream")
                    have += r
                    val whole = have - have % FRAME_BYTES
                    if (whole > 0) {
                        val w = NativeAudio.write(handle, buf, whole)
                        if (w < 0) Logs.w(Logs.Channel.AUDIO, "AAudio write error $w")
                        System.arraycopy(buf, whole, buf, 0, have - whole)
                        have -= whole
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 2000) {
                        _status.value = Status(true, NativeAudio.dropped(handle), NativeAudio.latencyFrames(handle))
                        lastReport = now
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    _status.value = Status()
                    Thread.sleep(backoff)
                    backoff = minOf(backoff * 2, 4000L)
                }
            } finally {
                runCatching { socket?.close() }
                if (handle != 0L) NativeAudio.close(handle)
            }
        }
    }

    companion object {
        const val RATE = 48000
        const val CHANNELS = 2
        const val FRAME_BYTES = CHANNELS * 2
    }
}
