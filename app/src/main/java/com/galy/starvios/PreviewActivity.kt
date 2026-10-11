package com.galy.starvios

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.galy.starvios.Ui.dp
import java.io.File

/**
 * Modo prueba: muestra en el teléfono cómo se verá el directo (fondo del juego + cámara +
 * banner + alertas reales de Starvios) SIN transmitir. Abajo está Starvios para tocar «Probar».
 */
class PreviewActivity : AppCompatActivity() {

  private lateinit var prefs: Prefs
  private val state = OverlayState()
  private var compositor: Compositor? = null
  private var surface: Surface? = null
  private var cam: MjpegClient? = null
  private var alerts: AlertsLayer? = null
  private lateinit var bg: ImageView
  private lateinit var web: WebView
  private val bgFile by lazy { File(filesDir, "preview_bg.jpg") }

  private val pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    uri ?: return@registerForActivityResult
    runCatching {
      contentResolver.openInputStream(uri)!!.use { i -> bgFile.outputStream().use { i.copyTo(it) } }
      loadBg()
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prefs = Prefs(this)
    state.bannerTitle = prefs.bannerTitle
    state.bannerSubtitle = prefs.bannerSubtitle
    state.camCorner = prefs.camCorner
    state.camSize = prefs.camSize
    state.theme = prefs.theme
    state.live = true

    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
    val header = TextView(this).apply {
      text = "👁  MODO PRUEBA · así lo verán tus espectadores (no estás en vivo)"
      setTextColor(Ui.TEXT); textSize = 12f
      setPadding(dp(12), dp(30), dp(12), dp(6))
    }
    col.addView(header)

    // Lienzo 16:9 igual que el directo
    val stage = object : FrameLayout(this) {
      override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = width * 9 / 16
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
      }
    }
    stage.setBackgroundColor(0xFF000000.toInt())
    bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    stage.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val texture = TextureView(this).apply { isOpaque = false }
    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startComposer(st, w, h)
      override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { stopComposer(); startComposer(st, w, h) }
      override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stopComposer(); return true }
      override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }
    stage.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    col.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // Botones de prueba
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
    fun chip(label: String, onClick: (TextView) -> Unit) {
      chips.addView(TextView(this).apply {
        text = label; textSize = 13f; setTextColor(Ui.TEXT)
        background = Ui.rounded(Ui.CARD, dp(18).toFloat(), dp(1), 0xFF2A2A35.toInt())
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { onClick(this) }
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
    }
    chip("🖼 Fondo del juego") { pickBg.launch("image/*") }
    chip("↘ Mover cámara") { state.camCorner = (state.camCorner + 1) % 4; prefs.camCorner = state.camCorner }
    chip("⤢ Tamaño cámara") { state.camSize = (state.camSize + 1) % 3; prefs.camSize = state.camSize }
    chip("📷 Cámara") { state.showCam = !state.showCam }
    chip("🏷 Banner") { state.showBanner = !state.showBanner }
    chip("☕ Ya vuelvo") { state.brb = !state.brb }
    chip("🎨 Tema") { state.theme = (state.theme + 1) % 2; prefs.theme = state.theme }
    chip("🔔 Recargar alertas") { alerts?.reload() }
    col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

    val hint = TextView(this).apply {
      setTextColor(Ui.GOLD); textSize = 13f
      setPadding(dp(12), 0, dp(12), dp(6))
      text = when {
        prefs.alertsUrl.isBlank() -> "⚠️ Pega la URL de Alertas OBS en Configuración para ver las alertas aquí."
        !Settings.canDrawOverlays(this@PreviewActivity) -> "⚠️ Activa «Mostrar sobre otras apps» (Preparar directo → Dar permisos) para ver las alertas."
        else -> "👇 En Starvios entra a Alertas OBS y toca Seguidor, Suscripción, Raid… La alerta aparece arriba con su sonido."
      }
    }
    col.addView(hint)

    // Starvios abajo para tocar los botones de prueba
    web = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      webViewClient = WebViewClient()
      webChromeClient = WebChromeClient()
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    col.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(col)
    web.loadUrl("https://starvios.com/studio")

    loadBg()
    cam = MjpegClient { prefs.camHost }.also { it.start() }
    if (Settings.canDrawOverlays(this)) alerts = AlertsLayer(this, prefs.alertsUrl).also { it.start() }
  }

  private fun loadBg() {
    if (bgFile.exists()) {
      val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
      bg.setImageBitmap(BitmapFactory.decodeFile(bgFile.path, opts))
    } else {
      bg.setImageDrawable(android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF0F2A3A.toInt(), 0xFF1B4D2E.toInt(), 0xFF3A2A10.toInt())
      ))
    }
  }

  private fun startComposer(st: SurfaceTexture, w: Int, h: Int) {
    val s = Surface(st)
    surface = s
    compositor = Compositor(s, w, h, cam, alerts, state).also { it.start() }
  }

  private fun stopComposer() {
    compositor?.stop(); compositor = null
    surface?.release(); surface = null
  }

  override fun onDestroy() {
    stopComposer()
    alerts?.stop(); alerts = null
    cam?.stop(); cam = null
    web.destroy()
    super.onDestroy()
  }
}
