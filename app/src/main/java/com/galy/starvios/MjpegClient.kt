package com.galy.starvios

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Recibe la cámara del otro teléfono (MJPEG por Wi‑Fi) y guarda el último fotograma.
 * Se reconecta solo si se corta.
 */
class MjpegClient(private val hostProvider: () -> String) {
  @Volatile var latest: Bitmap? = null
    private set
  @Volatile var lastFrameAt = 0L
    private set
  @Volatile private var running = false
  private var socket: Socket? = null

  val isReceiving: Boolean get() = System.currentTimeMillis() - lastFrameAt < 2000

  fun start() {
    if (running) return
    running = true
    thread(name = "mjpeg-client", isDaemon = true) {
      while (running) {
        val host = hostProvider().trim()
        if (host.isEmpty()) { Thread.sleep(1000); continue }
        try {
          readStream(host)
        } catch (_: Exception) {
        }
        runCatching { socket?.close() }
        if (running) Thread.sleep(1000)
      }
    }
  }

  private fun readStream(hostSpec: String) {
    val parts = hostSpec.split(":")
    val host = parts[0]
    val port = parts.getOrNull(1)?.toIntOrNull() ?: Prefs.CAM_PORT
    val s = Socket()
    socket = s
    s.tcpNoDelay = true
    s.connect(InetSocketAddress(host, port), 3000)
    s.soTimeout = 5000
    s.getOutputStream().apply {
      write("GET / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
      flush()
    }
    val input = BufferedInputStream(s.getInputStream(), 128 * 1024)
    // cabeceras HTTP
    while (true) {
      val line = readLine(input) ?: return
      if (line.isEmpty()) break
    }
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    while (running) {
      var length = -1
      // cabeceras de cada parte
      while (true) {
        val line = readLine(input) ?: return
        if (line.isEmpty()) {
          if (length >= 0) break else continue
        }
        if (line.startsWith("Content-Length", ignoreCase = true)) {
          length = line.substringAfter(":").trim().toIntOrNull() ?: -1
        }
      }
      val data = ByteArray(length)
      var read = 0
      while (read < length) {
        val n = input.read(data, read, length - read)
        if (n < 0) return
        read += n
      }
      val bmp = BitmapFactory.decodeByteArray(data, 0, length, opts) ?: continue
      latest = bmp // el compositor dibuja siempre el último; el anterior lo recoge el GC
      lastFrameAt = System.currentTimeMillis()
    }
  }

  private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
      val c = input.read()
      if (c < 0) return if (sb.isEmpty()) null else sb.toString()
      if (c == '\n'.code) break
      if (c != '\r'.code) sb.append(c.toChar())
    }
    return sb.toString()
  }

  fun stop() {
    running = false
    runCatching { socket?.close() }
  }
}
