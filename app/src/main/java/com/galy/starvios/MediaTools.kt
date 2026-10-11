package com.galy.starvios

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MediaTools {

  fun workDir(ctx: Context, name: String): File =
    File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), name).apply { mkdirs() }

  fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

  /** Copia un archivo elegido (galería) a la carpeta de la app para poder cortarlo. */
  fun importUri(ctx: Context, uri: Uri, dst: File): File {
    ctx.contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
    return dst
  }

  fun thumbnail(f: File, atSec: Double): Bitmap? = runCatching {
    val r = MediaMetadataRetriever()
    r.setDataSource(f.path)
    val bmp = r.getScaledFrameAtTime((atSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
    r.release()
    bmp
  }.getOrNull()

  /** Guarda una copia visible en el teléfono (Movies/StarviosLive o Download/StarviosLive). */
  fun saveToPhone(ctx: Context, f: File, mime: String, downloads: Boolean): Uri? = runCatching {
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
      put(MediaStore.MediaColumns.MIME_TYPE, mime)
      put(MediaStore.MediaColumns.RELATIVE_PATH, (if (downloads) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_MOVIES) + "/StarviosLive")
    }
    val collection = when {
      downloads -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
      mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
      else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val uri = ctx.contentResolver.insert(collection, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    uri
  }.getOrNull()

  /**
   * Saca el audio de [startSec, endSec] como WAV (Starvios acepta MP3, WAV u OGG).
   * Hace un fundido corto al final para que no corte en seco.
   */
  fun extractWav(src: File, dst: File, startSec: Double, endSec: Double) {
    val ex = MediaExtractor()
    ex.setDataSource(src.path)
    var track = -1
    var format: MediaFormat? = null
    for (i in 0 until ex.trackCount) {
      val f = ex.getTrackFormat(i)
      if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
    }
    require(track >= 0 && format != null) { "El video no tiene audio" }
    ex.selectTrack(track)
    val startUs = (startSec * 1_000_000).toLong()
    val endUs = (endSec * 1_000_000).toLong()
    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(format, null, null, 0)
    codec.start()
    var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

    val raf = RandomAccessFile(dst, "rw")
    raf.setLength(0)
    raf.write(ByteArray(44)) // cabecera WAV, se rellena al final
    var dataBytes = 0L
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var done = false
    while (!done) {
      if (!inputDone) {
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx >= 0) {
          val b = codec.getInputBuffer(idx)!!
          val size = ex.readSampleData(b, 0)
          if (size < 0 || ex.sampleTime > endUs) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
            ex.advance()
          }
        }
      }
      val out = codec.dequeueOutputBuffer(info, 10_000)
      if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        val f = codec.outputFormat
        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      } else if (out >= 0) {
        val t = info.presentationTimeUs
        if (info.size > 0 && t >= startUs && t <= endUs) {
          val bb = codec.getOutputBuffer(out)!!
          bb.position(info.offset); bb.limit(info.offset + info.size)
          val bytes = ByteArray(info.size)
          bb.get(bytes)
          raf.write(bytes)
          dataBytes += bytes.size
        }
        codec.releaseOutputBuffer(out, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
      }
    }
    codec.stop(); codec.release(); ex.release()

    // fundido de salida de ~250 ms
    val frameBytes = 2 * channels
    val fadeFrames = minOf((sampleRate * 0.25).toLong(), dataBytes / frameBytes)
    if (fadeFrames > 0) {
      val fadeStart = 44 + dataBytes - fadeFrames * frameBytes
      val buf = ByteArray((fadeFrames * frameBytes).toInt())
      raf.seek(fadeStart); raf.readFully(buf)
      val sb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
      for (fi in 0 until fadeFrames) {
        val gain = 1f - fi.toFloat() / fadeFrames
        for (c in 0 until channels) {
          val pos = ((fi * channels + c) * 2).toInt()
          sb.putShort(pos, (sb.getShort(pos) * gain).toInt().toShort())
        }
      }
      raf.seek(fadeStart); raf.write(buf)
    }

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()); header.putInt((36 + dataBytes).toInt())
    header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
    header.putInt(16); header.putShort(1); header.putShort(channels.toShort())
    header.putInt(sampleRate); header.putInt(sampleRate * channels * 2)
    header.putShort((channels * 2).toShort()); header.putShort(16)
    header.put("data".toByteArray()); header.putInt(dataBytes.toInt())
    raf.seek(0); raf.write(header.array())
    raf.close()
  }
}
