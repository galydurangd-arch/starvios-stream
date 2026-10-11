package com.galy.starvios

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlin.math.min

/**
 * Dibuja ~30 veces por segundo la capa que va ENCIMA del juego:
 * cámara del otro teléfono, banner, pantalla de «Ya vuelvo» y alertas de Starvios.
 * Todo se dibuja en un lienzo de 1920x1080 lógico y se escala al tamaño real.
 */
class Compositor(
  private val surface: Surface,
  private val outW: Int,
  private val outH: Int,
  private val cam: MjpegClient?,
  private val alerts: AlertsLayer?,
  private val state: OverlayState,
) {
  private val thread = HandlerThread("compositor").apply { start() }
  private val handler = Handler(thread.looper)
  @Volatile private var running = false

  private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
  private val clipPath = Path()
  private val full = Rect(0, 0, 1920, 1080)

  // Colores según el tema elegido en Configuración
  private val merc get() = state.theme == 1
  private val accent1 get() = if (merc) 0xFFE0141E.toInt() else Ui.PINK
  private val accent2 get() = if (merc) 0xFF1A1A1A.toInt() else Ui.GOLD
  private val accentText get() = if (merc) 0xFFFF4D4D.toInt() else Ui.PINK

  fun start() {
    running = true
    handler.post(frame)
  }

  private val frame = object : Runnable {
    override fun run() {
      if (!running) return
      val started = System.nanoTime()
      try {
        val canvas = surface.lockHardwareCanvas()
        try {
          canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
          canvas.save()
          canvas.scale(outW / 1920f, outH / 1080f)
          draw(canvas)
          canvas.restore()
        } finally {
          surface.unlockCanvasAndPost(canvas)
        }
      } catch (_: Exception) {
      }
      val elapsedMs = (System.nanoTime() - started) / 1_000_000
      handler.postDelayed(this, (33 - elapsedMs).coerceAtLeast(5))
    }
  }

  private fun draw(c: Canvas) {
    if (state.brb) drawBrb(c)
    if (state.showBanner) drawBanner(c)
    if (state.showCam && !state.brb) drawCam(c)
    alerts?.drawTo(c, full, bmpPaint)
  }

  // ---------- Cámara (recuadro redondeado con borde rosa) ----------
  private fun drawCam(c: Canvas) {
    val bmp: Bitmap = cam?.latest ?: return
    if (cam.isReceiving.not() && System.currentTimeMillis() - cam.lastFrameAt > 5000) return
    val boxH = when (state.camSize) { 0 -> 280f; 2 -> 520f; else -> 390f }
    val aspect = bmp.width.toFloat() / bmp.height
    val boxW = boxH * aspect.coerceIn(0.56f, 1.78f)
    val m = 28f
    val bannerSpace = if (state.showBanner) 130f else 0f
    val left = if (state.camCorner == 1 || state.camCorner == 3) 1920f - m - boxW else m
    val top = if (state.camCorner >= 2) m else 1080f - m - boxH - bannerSpace
    val r = RectF(left, top, left + boxW, top + boxH)
    val radius = 34f

    // sombra
    fill.color = 0x66000000
    c.drawRoundRect(RectF(r.left + 6, r.top + 10, r.right + 6, r.bottom + 10), radius, radius, fill)

    c.save()
    clipPath.reset()
    clipPath.addRoundRect(r, radius, radius, Path.Direction.CW)
    c.clipPath(clipPath)
    // recorte tipo "cover"
    val srcAspect = bmp.width.toFloat() / bmp.height
    val dstAspect = r.width() / r.height()
    val src = if (srcAspect > dstAspect) {
      val w = (bmp.height * dstAspect).toInt()
      val x = (bmp.width - w) / 2
      Rect(x, 0, x + w, bmp.height)
    } else {
      val h = (bmp.width / dstAspect).toInt()
      val y = (bmp.height - h) / 2
      Rect(0, y, bmp.width, y + h)
    }
    c.drawBitmap(bmp, src, r, bmpPaint)
    c.restore()

    stroke.strokeWidth = if (merc) 10f else 7f
    stroke.shader = LinearGradient(r.left, r.top, r.right, r.bottom, accent1, accent2, Shader.TileMode.CLAMP)
    c.drawRoundRect(r, radius, radius, stroke)
    stroke.shader = null
    if (merc) {
      // franjas diagonales rojas en la esquina, estilo "mercenario"
      fill.color = accent1
      for (k in 0 until 3) {
        val x = r.right - 70f + k * 22f
        c.drawRect(x, r.top - 12f, x + 10f, r.top + 4f, fill)
      }
    }
  }

  // ---------- Banner inferior ----------
  private fun drawBanner(c: Canvas) {
    val title = state.bannerTitle
    val sub = state.bannerSubtitle
    if (title.isBlank() && sub.isBlank()) return
    text.textSize = 44f
    val tW = text.measureText(title)
    text.textSize = 30f
    val sW = text.measureText(sub)
    val w = min(1920f - 56f, maxOf(tW, sW) + 150f)
    val h = 104f
    val left = 28f
    val top = 1080f - 28f - h
    val r = RectF(left, top, left + w, top + h)

    fill.shader = if (merc) LinearGradient(r.left, r.top, r.right, r.top, 0xF0000000.toInt(), 0xF0300406.toInt(), Shader.TileMode.CLAMP)
    else LinearGradient(r.left, r.top, r.right, r.top, 0xEE0B0B10.toInt(), 0xEE1E1424.toInt(), Shader.TileMode.CLAMP)
    c.drawRoundRect(r, h / 2, h / 2, fill)
    fill.shader = null
    stroke.strokeWidth = 4f
    stroke.shader = LinearGradient(r.left, r.top, r.right, r.bottom, accent1, if (merc) 0xFF7A0A0F.toInt() else accent2, Shader.TileMode.CLAMP)
    c.drawRoundRect(r, h / 2, h / 2, stroke)
    stroke.shader = null

    // punto "EN VIVO"
    fill.color = if (state.live) Ui.RED else Ui.MUTED
    c.drawCircle(left + 52f, top + h / 2, 16f, fill)
    fill.color = 0x55FF3B4E
    if (state.live && (System.currentTimeMillis() / 600) % 2 == 0L) c.drawCircle(left + 52f, top + h / 2, 26f, fill)

    text.color = Color.WHITE
    text.textSize = 44f
    c.drawText(title, left + 96f, top + 50f, text)
    text.color = accentText
    text.textSize = 30f
    c.drawText(sub, left + 96f, top + 88f, text)
    text.color = Color.WHITE
  }

  // ---------- Pantalla "Ya vuelvo" ----------
  private fun drawBrb(c: Canvas) {
    fill.shader = if (merc) LinearGradient(0f, 0f, 1920f, 1080f, 0xFF050505.toInt(), 0xFF3A0508.toInt(), Shader.TileMode.CLAMP)
    else LinearGradient(0f, 0f, 1920f, 1080f, 0xFF140A1A.toInt(), 0xFF2A0F24.toInt(), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, 1920f, 1080f, fill)
    fill.shader = null
    // estrellas decorativas
    fill.color = if (merc) 0x40E0141E else 0x33FF5D9E
    val t = System.currentTimeMillis() / 40
    for (i in 0 until 28) {
      val x = ((i * 397 + t * (1 + i % 3)) % 1920).toFloat()
      val y = ((i * 211) % 1080).toFloat()
      c.drawCircle(x, y, (3 + i % 5).toFloat(), fill)
    }
    text.textAlign = Paint.Align.CENTER
    text.color = accentText
    text.textSize = 40f
    text.letterSpacing = 0.3f
    c.drawText(if (merc) "MODO MERCENARIO" else "STARVIOS", 960f, 400f, text)
    text.letterSpacing = 0f
    text.color = Color.WHITE
    text.textSize = 130f
    c.drawText(if (merc) "Vuelvo en un toque" else state.brbText, 960f, 560f, text)
    if (merc) {
      text.textSize = 44f
      text.color = 0xFFBBBBBB.toInt()
      c.drawText("no cambies de canal... te estoy viendo 👀", 960f, 630f, text)
    }
    text.color = if (merc) accentText else Ui.GOLD
    text.textSize = 42f
    c.drawText(state.bannerTitle, 960f, if (merc) 720f else 650f, text)
    text.textAlign = Paint.Align.LEFT
    text.color = Color.WHITE
  }

  fun stop() {
    running = false
    handler.removeCallbacksAndMessages(null)
    thread.quitSafely()
  }
}

/** Estado que cambian los botones de la burbuja flotante. */
class OverlayState {
  @Volatile var showCam = true
  @Volatile var showBanner = true
  @Volatile var brb = false
  @Volatile var brbText = "Ya vuelvo"
  @Volatile var live = false
  @Volatile var camCorner = 0
  @Volatile var camSize = 1
  @Volatile var bannerTitle = ""
  @Volatile var bannerSubtitle = ""
  @Volatile var theme = 0
}
