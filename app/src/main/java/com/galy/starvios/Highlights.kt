package com.galy.starvios

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Un tramo elegido del directo. */
data class ClipWindow(val startSec: Double, val endSec: Double, val reason: String)

/**
 * Busca las jugadas destacadas de la grabación:
 *  - Momentos marcados con el botón ✂️ (siempre entran).
 *  - Picos de audio: narrador de MLBB, efectos y tus gritos suben el volumen de golpe.
 * Siempre devuelve al menos [minCount] tramos (si el directo alcanza).
 */
object Highlights {

  /** Volumen (dB aprox.) de cada segundo de la grabación. */
  fun loudnessPerSecond(src: File, onProgress: (Float) -> Unit = {}): FloatArray {
    val ex = MediaExtractor()
    ex.setDataSource(src.path)
    var track = -1
    var format: MediaFormat? = null
    for (i in 0 until ex.trackCount) {
      val f = ex.getTrackFormat(i)
      if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
    }
    if (track < 0 || format == null) { ex.release(); return FloatArray(0) }
    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    ex.selectTrack(track)
    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(format, null, null, 0)
    codec.start()

    val seconds = max(1, (durationUs / 1_000_000L).toInt() + 2)
    val sumSq = DoubleArray(seconds)
    val counts = LongArray(seconds)
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    var lastReport = 0L
    while (!outputDone) {
      if (!inputDone) {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
          val buf = codec.getInputBuffer(inIdx)!!
          val size = ex.readSampleData(buf, 0)
          if (size < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
            ex.advance()
          }
        }
      }
      val outIdx = codec.dequeueOutputBuffer(info, 10_000)
      if (outIdx >= 0) {
        if (info.size > 0) {
          val out: ByteBuffer = codec.getOutputBuffer(outIdx)!!
          out.position(info.offset); out.limit(info.offset + info.size)
          val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
          val sec = (info.presentationTimeUs / 1_000_000L).toInt().coerceIn(0, seconds - 1)
          var acc = 0.0
          val n = shorts.remaining()
          // muestreo 1 de cada 4 para ir más rápido
          var i = 0
          while (i < n) { val v = shorts.get(i).toDouble(); acc += v * v; i += 4 }
          sumSq[sec] += acc
          counts[sec] += ((n + 3) / 4).toLong()
          if (durationUs > 0 && info.presentationTimeUs - lastReport > 5_000_000) {
            lastReport = info.presentationTimeUs
            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
          }
        }
        codec.releaseOutputBuffer(outIdx, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
      }
    }
    codec.stop(); codec.release(); ex.release()

    return FloatArray(seconds) { s ->
      if (counts[s] == 0L) 0f else (20 * log10(sqrt(sumSq[s] / counts[s]) + 1.0)).toFloat()
    }
  }

  fun pick(
    loud: FloatArray,
    durationSec: Double,
    marks: List<Double>,
    clipLen: Double = 30.0,
    minCount: Int = 5,
    maxCount: Int = 8,
  ): List<ClipWindow> {
    if (durationSec < 4) return emptyList()
    // Si el directo fue corto, achicamos los clips para que salgan 5 igual.
    val len = min(clipLen, max(6.0, durationSec / minCount))
    val before = len * 0.7 // la jugada suele construirse antes del pico
    val chosen = mutableListOf<ClipWindow>()

    fun window(center: Double, reason: String): ClipWindow {
      var s = center - before
      var e = s + len
      if (s < 0) { e -= s; s = 0.0 }
      if (e > durationSec) { s -= (e - durationSec); e = durationSec; s = max(0.0, s) }
      return ClipWindow(s, e, reason)
    }
    fun overlaps(w: ClipWindow, gap: Double) = chosen.any { w.startSec < it.endSec + gap && it.startSec < w.endSec + gap }

    // 1) Tus marcas ✂️
    for (m in marks.sorted()) {
      val w = window(m, "✂️ Marcado por ti")
      if (!overlaps(w, 0.0)) chosen += w
    }

    // 2) Picos de emoción en el audio
    val n = loud.size
    if (n > 0) {
      val smooth = FloatArray(n) { i ->
        var a = 0f; var c = 0
        for (j in max(0, i - 1)..min(n - 1, i + 1)) { a += loud[j]; c++ }
        a / c
      }
      val base = FloatArray(n)
      var acc = 0.0
      val win = 60
      for (i in 0 until n) {
        acc += smooth[i]
        if (i >= win) acc -= smooth[i - win]
        base[i] = (acc / min(i + 1, win)).toFloat()
      }
      val score = FloatArray(n) { smooth[it] - base[it] }
      val order = (0 until n).sortedByDescending { score[it] }
      for (i in order) {
        if (chosen.size >= maxCount) break
        val strong = score[i] >= 6f
        if (chosen.size >= minCount && !strong) break
        if (i >= durationSec) continue
        val w = window(i.toDouble(), if (strong) "🔥 Jugada intensa" else "⭐ Buen momento")
        if (!overlaps(w, 3.0)) chosen += w
      }
    }

    // 3) Si aún faltan, repartir tramos a lo largo del directo
    var k = 0
    while (chosen.size < minCount && k < 50) {
      val center = durationSec * (k + 0.5) / minCount
      val w = window(center, "🎮 Momento del directo")
      if (!overlaps(w, 0.0)) chosen += w
      k++
    }
    return chosen.sortedBy { it.startSec }
  }

  /** Corta [startSec, endSec] sin recomprimir (empieza en el fotograma clave anterior). */
  fun cut(src: File, dst: File, startSec: Double, endSec: Double) {
    val ex = MediaExtractor()
    ex.setDataSource(src.path)
    val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val map = HashMap<Int, Int>()
    var maxSize = 1024 * 1024
    for (i in 0 until ex.trackCount) {
      val f = ex.getTrackFormat(i)
      val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("video/") || mime.startsWith("audio/")) {
        ex.selectTrack(i)
        map[i] = muxer.addTrack(f)
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = max(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
      }
    }
    muxer.start()
    val startUs = (startSec * 1_000_000).toLong()
    val endUs = (endSec * 1_000_000).toLong()
    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    val buf = ByteBuffer.allocate(maxSize)
    val info = MediaCodec.BufferInfo()
    var baseUs = -1L
    while (true) {
      val track = ex.sampleTrackIndex
      if (track < 0) break
      val t = ex.sampleTime
      if (t > endUs) break
      if (baseUs < 0) baseUs = t
      val size = ex.readSampleData(buf, 0)
      if (size < 0) break
      val pts = t - baseUs
      if (pts >= 0) {
        info.offset = 0
        info.size = size
        info.presentationTimeUs = pts
        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        map[track]?.let { muxer.writeSampleData(it, buf, info) }
      }
      ex.advance()
    }
    runCatching { muxer.stop() }
    muxer.release()
    ex.release()
  }

  fun durationSec(file: File): Double {
    val ex = MediaExtractor()
    return try {
      ex.setDataSource(file.path)
      var d = 0L
      for (i in 0 until ex.trackCount) {
        val f = ex.getTrackFormat(i)
        if (f.containsKey(MediaFormat.KEY_DURATION)) d = max(d, f.getLong(MediaFormat.KEY_DURATION))
      }
      d / 1_000_000.0
    } catch (_: Exception) { 0.0 } finally { ex.release() }
  }
}
