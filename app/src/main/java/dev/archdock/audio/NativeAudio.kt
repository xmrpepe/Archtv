package dev.archdock.audio

/** JNI bridge to the AAudio sink in libarchdock.so (audio_sink.cpp). */
object NativeAudio {
    init { System.loadLibrary("archdock") }

    @JvmStatic external fun open(rate: Int, channels: Int): Long
    @JvmStatic external fun write(handle: Long, data: ByteArray, length: Int): Int
    @JvmStatic external fun dropped(handle: Long): Long
    @JvmStatic external fun latencyFrames(handle: Long): Int
    @JvmStatic external fun close(handle: Long)
}
