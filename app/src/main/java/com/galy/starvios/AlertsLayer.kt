package com.galy.starvios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Carga la «Fuente de navegador» de Starvios (alertas + chat) en un WebView casi invisible
 * y la copia ~15 veces por segundo a un Bitmap que el compositor pega encima del juego.
 * El sonido de las alertas sale por el teléfono y entra al directo con el audio interno.
 */
class AlertsLayer(private val context: Context, private val url: String) {

  // La fuente está pensada para 1920x1080; la renderizamos a la mitad para ahorrar batería.
  private val w = 960
  private val h = 540
  private val main = Handler(Looper.getMainLooper())
  private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private var webView: WebView? = null
  private var back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  private var front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  private val lock = Any()
  @Volatile private var running = false

  @SuppressLint("SetJavaScriptEnabled")
  fun start() {
    if (url.isBlank()) return
    running = true
    val wv = WebView(context).apply {
      setBackgroundColor(Color.TRANSPARENT)
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      settings.useWideViewPort = false
      settings.loadWithOverviewMode = false
      setInitialScale(50) // 1920 px CSS -> 960 px reales
      webViewClient = WebViewClient()
      webChromeClient = WebChromeClient()
      isVerticalScrollBarEnabled = false
      isHorizontalScrollBarEnabled = false
    }
    val lp = WindowManager.LayoutParams(
      w, h,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      alpha = 0.01f // prácticamente invisible en tu pantalla
    }
    runCatching { wm.addView(wv, lp) }
    wv.loadUrl(url)
    webView = wv
    main.post(capture)
  }

  private val capture = object : Runnable {
    override fun run() {
      val wv = webView ?: return
      if (!running) return
      try {
        back.eraseColor(Color.TRANSPARENT)
        val c = Canvas(back)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        wv.draw(c)
        synchronized(lock) {
          val t = front; front = back; back = t
        }
      } catch (_: Exception) {
      }
      main.postDelayed(this, 66)
    }
  }

  /** Dibuja la capa de alertas escalada a todo el lienzo (llamado desde el hilo del compositor). */
  fun drawTo(canvas: Canvas, dst: android.graphics.Rect, paint: android.graphics.Paint) {
    if (!running) return
    synchronized(lock) {
      canvas.drawBitmap(front, null, dst, paint)
    }
  }

  fun reload() {
    main.post { webView?.reload() }
  }

  fun stop() {
    running = false
    main.removeCallbacks(capture)
    main.post {
      webView?.let {
        runCatching { wm.removeView(it) }
        it.destroy()
      }
      webView = null
    }
  }
}
