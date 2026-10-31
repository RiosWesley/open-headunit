package com.andrerinas.openheadunit.aap

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.openheadunit.decoder.video.ParameterSetInspector
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer

/**
 * Decodes the dedicated cluster video stream (AAP channel `ID_VID_CLUSTER`) onto whatever [Surface]
 * its owner hands it. Each sink that shows the cluster picture (the debug overlay on the main
 * projection, the instrument-cluster display) owns one instance with its *own* MediaCodec, so it
 * never touches the shared main-stream decoder.
 *
 * Fed one reassembled Annex-B access unit at a time on the reader thread. The decoder is built on
 * the first unit carrying an SPS, so a sink attached mid-stream shows nothing until the next
 * keyframe - its owner should ask for one when the surface arrives.
 *
 * @param tag log prefix naming the sink.
 * @param fallbackResolution used only when the SPS size cannot be read.
 */
internal class ClusterStreamDecoder(
    private val tag: String,
    private val fallbackResolution: () -> String,
) {

    @Volatile private var surface: Surface? = null
    private var codec: MediaCodec? = null
    @Volatile private var codecReady = false
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Sets (or clears, with null) the target surface. A new surface needs a new decoder. */
    fun setSurface(s: Surface?) {
        synchronized(this) {
            if (s === surface) return
            surface = s
        }
        releaseCodec()
    }

    fun release() = setSurface(null)

    fun onUnit(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || offset + length > data.size) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val s: Surface? = surface
        if (s == null || !s.isValid) return

        val unit = data.copyOfRange(offset, offset + length)
        ensureDecoder(s, unit)

        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inputBuffer = c.getInputBuffer(inIdx)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    if (inputBuffer.remaining() < unit.size) {
                        AppLog.w("$tag: access unit of ${unit.size} bytes exceeds input buffer")
                    } else {
                        inputBuffer.put(unit)
                        c.queueInputBuffer(inIdx, 0, unit.size, 0, 0)
                    }
                }
            }
            drainOutput(c)
        } catch (e: IllegalStateException) {
            // The surface went away under the codec (sink hidden mid-frame). The next surface
            // builds a fresh decoder.
            AppLog.w("$tag: decoder failed, releasing: ${e.message}")
            releaseCodec()
        }
    }

    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx < 0) return
            val render = bufferInfo.size > 0 &&
                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
            c.releaseOutputBuffer(outIdx, render)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    /** Builds the decoder on the first access unit carrying an SPS, sized off the SPS. */
    private fun ensureDecoder(s: Surface, firstUnit: ByteArray) {
        synchronized(this) {
            if (codecReady) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val sets = scanParameterSets(firstUnit) ?: return

            val (w, h) = if (sets.width in 160..7680 && sets.height in 160..4320) {
                Pair(sets.width, sets.height)
            } else {
                parseResolution(fallbackResolution())
            }

            val format = MediaFormat.createVideoFormat(sets.mime, w, h)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            if (sets.mime == "video/hevc") {
                val combined = (sets.vps ?: ByteArray(0)) + sets.sps + (sets.pps ?: ByteArray(0))
                format.setByteBuffer("csd-0", ByteBuffer.wrap(combined))
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sets.sps))
                sets.pps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }

            val c = MediaCodec.createDecoderByType(sets.mime)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            codecReady = true
            AppLog.i("$tag: decoder ready (${sets.mime}, ${w}x$h)")
        }
    }

    private fun releaseCodec() {
        synchronized(this) {
            codecReady = false
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
            codec = null
        }
    }

    private class ParsedSets(
        val mime: String,
        val sps: ByteArray,
        val pps: ByteArray?,
        val vps: ByteArray?,
        val width: Int,
        val height: Int,
    )

    /** Collects the parameter sets and picture size out of one access unit; null if no SPS yet. */
    private fun scanParameterSets(unit: ByteArray): ParsedSets? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var vps: ByteArray? = null
        var width = 0
        var height = 0

        forEachNal(unit) { nal, headerOffset ->
            val first = nal[headerOffset].toInt()
            val avcType = first and 0x1F
            val hevcType = (first and 0x7E) shr 1
            when {
                avcType == 7 -> {
                    sps = nal
                    ParameterSetInspector.parseH264Sps(nal, headerOffset, nal.size - headerOffset)
                        ?.let { width = it.width; height = it.height }
                }
                avcType == 8 -> pps = nal
                hevcType == 32 -> vps = nal
                hevcType == 33 -> {
                    sps = nal
                    ParameterSetInspector.parseHevcSps(nal, headerOffset, nal.size - headerOffset)
                        ?.let { width = it.width; height = it.height }
                }
                hevcType == 34 -> pps = nal
            }
        }

        val spsNal = sps ?: return null
        val mime = if ((spsNal[4].toInt() and 0x7E) shr 1 == 33) "video/hevc" else "video/avc"
        return ParsedSets(mime, spsNal, pps, vps, width, height)
    }

    /**
     * Invokes [cb] for each NAL in an Annex-B buffer. [cb] receives data starting with a normalised
     * 4-byte start code and the NAL header byte's offset within it (always 4).
     */
    private fun forEachNal(unit: ByteArray, cb: (ByteArray, Int) -> Unit) {
        val n = unit.size
        var i = 0
        while (i < n) {
            var zeros = 0
            while (i + zeros < n && unit[i + zeros] == 0.toByte()) zeros++
            if (i + zeros < n && unit[i + zeros] == 1.toByte() && zeros >= 2) {
                val start = i
                val header = i + zeros
                var end = n
                var j = header
                while (j + 3 < n) {
                    val c = unit[j].toInt()
                    if (c == 0 && unit[j + 1].toInt() == 0 &&
                        (unit[j + 2].toInt() == 1 ||
                            (j + 3 < n && unit[j + 2].toInt() == 0 && unit[j + 3].toInt() == 1))
                    ) {
                        end = j
                        break
                    }
                    j++
                }
                val raw = unit.copyOfRange(start, end)
                val fixed = if (zeros == 2) {
                    ByteArray(raw.size + 1).apply {
                        this[0] = 0
                        System.arraycopy(raw, 0, this, 1, raw.size)
                    }
                } else raw
                cb(fixed, 4)
                i = end
            } else {
                i++
            }
        }
    }

    private fun parseResolution(res: String): Pair<Int, Int> {
        val parts = res.split("x", "X")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1280
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 720
        return if (w > 0 && h > 0) Pair(w, h) else Pair(1280, 720)
    }
}
