package com.galy.starvios

import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Servidor MJPEG mínimo: cada cliente recibe un flujo multipart de fotos JPEG.
 * También se puede abrir en un navegador (http://IP:8090) para probar.
 */
class MjpegServer(private val port: Int) {
  private val lock = Object()
  @Volatile private var latest: ByteArray? = null
  @Volatile private var frameId = 0L
  @Volatile private var running = false
  private var server: ServerSocket? = null
  val clients = AtomicInteger(0)

  fun start() {
    running = true
    thread(name = "mjpeg-accept", isDaemon = true) {
      try {
        val ss = ServerSocket(port).apply { reuseAddress = true }
        server = ss
        while (running) {
          val s = ss.accept()
          thread(name = "mjpeg-client", isDaemon = true) { serve(s) }
        }
      } catch (_: Exception) {
      }
    }
  }

  fun publish(jpeg: ByteArray) {
    synchronized(lock) {
      latest = jpeg
      frameId++
      lock.notifyAll()
    }
  }

  private fun serve(socket: Socket) {
    clients.incrementAndGet()
    try {
      socket.tcpNoDelay = true
      socket.soTimeout = 0
      // leer y descartar la petición HTTP
      val input = socket.getInputStream()
      val buf = ByteArray(2048)
      input.read(buf)
      val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
      out.write(
        ("HTTP/1.0 200 OK\r\n" +
          "Cache-Control: no-cache\r\n" +
          "Connection: close\r\n" +
          "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").toByteArray()
      )
      out.flush()
      var lastSent = -1L
      while (running && !socket.isClosed) {
        var frame: ByteArray? = null
        synchronized(lock) {
          while (frameId == lastSent && running) lock.wait(1000)
          frame = latest
          lastSent = frameId
        }
        val f = frame ?: continue
        out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
        out.write(f)
        out.write("\r\n".toByteArray())
        out.flush()
      }
    } catch (_: Exception) {
    } finally {
      clients.decrementAndGet()
      runCatching { socket.close() }
    }
  }

  fun stop() {
    running = false
    runCatching { server?.close() }
    synchronized(lock) { lock.notifyAll() }
  }
}
