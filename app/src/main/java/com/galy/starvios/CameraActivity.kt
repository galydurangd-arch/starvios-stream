package com.galy.starvios

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.galy.starvios.Ui.dp
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Teléfono que hace de cámara: envía su cámara por Wi‑Fi (MJPEG) al teléfono que transmite.
 */
class CameraActivity : AppCompatActivity() {

  private val server = MjpegServer(Prefs.CAM_PORT)
  private val analysisExecutor = Executors.newSingleThreadExecutor()
  private lateinit var prefs: Prefs
  private lateinit var previewView: PreviewView
  private lateinit var status: TextView
  private var nsdManager: NsdManager? = null
  private var nsdListener: NsdManager.RegistrationListener? = null
  private var multicastLock: WifiManager.MulticastLock? = null
  private val ui = Handler(Looper.getMainLooper())
  @Volatile private var lastFrameAt = 0L
  private var dimmed = false

  private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    if (ok) startCamera() else status.text = "Necesito permiso de cámara para funcionar."
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prefs = Prefs(this)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
    previewView = PreviewView(this).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      background = Ui.rounded(0xCC0B0B10.toInt(), dp(22).toFloat())
      val p = dp(16)
      setPadding(p, p, p, p)
    }
    panel.addView(Ui.eyebrow(this, "Modo cámara"))
    status = Ui.label(this, "Iniciando…", 15f)
    panel.addView(status)

    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val flip = Ui.button(this, "🔄 Girar", primary = false) {
      prefs.useFrontCamera = !prefs.useFrontCamera
      startCamera()
    }
    val dim = Ui.button(this, "🌙 Ahorrar batería", primary = false) { toggleDim() }
    row.addView(flip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
    row.addView(dim, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
    panel.addView(row)

    root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
      val m = dp(14); setMargins(m, m, m, dp(28))
    })
    root.setOnClickListener { if (dimmed) toggleDim() }
    setContentView(root)

    server.start()
    registerNsd()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    else permission.launch(Manifest.permission.CAMERA)
    ui.post(statusTick)
  }

  private val statusTick = object : Runnable {
    override fun run() {
      val ip = localIp() ?: "sin Wi‑Fi"
      val n = server.clients.get()
      val live = System.currentTimeMillis() - lastFrameAt < 1500
      status.text = buildString {
        append(if (n > 0) "✅ Conectado al teléfono que transmite\n" else "⏳ Esperando al teléfono que transmite…\n")
        append("IP de este teléfono: $ip\n")
        append(if (live) "Cámara: enviando" else "Cámara: sin imagen")
        append("\nDeja este teléfono con la pantalla encendida.")
      }
      ui.postDelayed(this, 1000)
    }
  }

  private fun toggleDim() {
    dimmed = !dimmed
    val lp = window.attributes
    lp.screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = lp
    previewView.alpha = if (dimmed) 0f else 1f
  }

  private fun startCamera() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
      val provider = future.get()
      provider.unbindAll()
      val front = prefs.useFrontCamera
      val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

      val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

      val resolution = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()
      val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(resolution)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

      val jpegOut = ByteArrayOutputStream(256 * 1024)
      analysis.setAnalyzer(analysisExecutor) { image ->
        try {
          val now = System.currentTimeMillis()
          // ~25 fps como máximo y solo si alguien está mirando
          if (server.clients.get() > 0 && now - lastFrameAt >= 38) {
            val src = image.toBitmap()
            val m = Matrix()
            m.postRotate(image.imageInfo.rotationDegrees.toFloat())
            if (front) m.postScale(-1f, 1f)
            // reducir a ~720 px de lado mayor
            val maxSide = maxOf(src.width, src.height)
            if (maxSide > 720) {
              val s = 720f / maxSide
              m.postScale(s, s)
            }
            val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            jpegOut.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, jpegOut)
            server.publish(jpegOut.toByteArray())
            if (bmp !== src) bmp.recycle()
            src.recycle()
            lastFrameAt = now
          } else if (server.clients.get() == 0) {
            lastFrameAt = now
          }
        } catch (_: Exception) {
        } finally {
          image.close()
        }
      }
      try {
        provider.bindToLifecycle(this, selector, preview, analysis)
      } catch (e: Exception) {
        status.text = "No se pudo abrir la cámara: ${e.message}"
      }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun registerNsd() {
    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    multicastLock = wifi.createMulticastLock("starvios-cam").apply { setReferenceCounted(false); acquire() }
    val info = NsdServiceInfo().apply {
      serviceName = "Starvios Cam ${Build.MODEL}"
      serviceType = Prefs.NSD_TYPE
      port = Prefs.CAM_PORT
    }
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) {}
      override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
      override fun onServiceUnregistered(info: NsdServiceInfo) {}
      override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }
    nsdListener = listener
    nsdManager = (getSystemService(NSD_SERVICE) as NsdManager).also {
      runCatching { it.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    ui.removeCallbacks(statusTick)
    runCatching { nsdListener?.let { nsdManager?.unregisterService(it) } }
    runCatching { multicastLock?.release() }
    server.stop()
    analysisExecutor.shutdown()
  }

  companion object {
    fun localIp(): String? = runCatching {
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress
    }.getOrNull()
  }
}
