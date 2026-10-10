package com.galy.starvios

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.galy.starvios.Ui.dp

class StreamSetupActivity : AppCompatActivity() {

  private lateinit var prefs: Prefs
  private lateinit var camStatus: TextView
  private lateinit var camPreview: ImageView
  private lateinit var checklist: TextView
  private val ui = Handler(Looper.getMainLooper())
  private var testClient: MjpegClient? = null
  private var nsd: NsdManager? = null
  private var discovery: NsdManager.DiscoveryListener? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

  private val askCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
    if (res.resultCode == Activity.RESULT_OK && res.data != null) {
      val i = Intent(this, StreamService::class.java)
        .setAction(StreamService.ACTION_START)
        .putExtra(StreamService.EXTRA_CODE, res.resultCode)
        .putExtra(StreamService.EXTRA_DATA, res.data)
      ContextCompat.startForegroundService(this, i)
      Toast.makeText(this, "Listo. Ahora abre Mobile Legends 🎮", Toast.LENGTH_LONG).show()
      ui.postDelayed({ moveTaskToBack(true); finish() }, 900)
    } else {
      Toast.makeText(this, "Necesito el permiso de grabar pantalla para transmitir", Toast.LENGTH_LONG).show()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    prefs = Prefs(this)
    val col = Ui.page(this)
    col.addView(Ui.eyebrow(this, "Preparar directo"))
    col.addView(Ui.title(this, "Antes de salir en vivo"))

    val checks = Ui.card(this)
    checklist = Ui.label(this, "", 15f)
    checks.addView(checklist)
    checks.addView(Ui.button(this, "Dar permisos que faltan", primary = false) { requestMissing() })
    col.addView(checks)

    val camCard = Ui.card(this)
    camCard.addView(Ui.label(this, "📷 Cámara del otro teléfono", 17f, Ui.TEXT, true))
    camCard.addView(Ui.label(this, "En el otro teléfono abre esta app → «Ser la cámara». Los dos deben estar en el mismo Wi‑Fi. Se encuentra sola; si no, escribe la IP que muestra.", 13f, Ui.MUTED))
    camStatus = Ui.label(this, "Buscando cámara…", 15f, Ui.GOLD, true)
    camCard.addView(camStatus)
    camPreview = ImageView(this).apply {
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = Ui.rounded(0xFF000000.toInt(), dp(16).toFloat())
      clipToOutline = true
    }
    camCard.addView(camPreview, LinearLayout.LayoutParams(dp(150), dp(200)).apply { topMargin = dp(8) })
    val ipInput = Ui.input(this, "IP manual, ej: 192.168.1.35", prefs.camHost)
    camCard.addView(ipInput)
    camCard.addView(Ui.button(this, "Usar esta IP", primary = false) {
      prefs.camHost = ipInput.text.toString()
      Toast.makeText(this, "IP guardada", Toast.LENGTH_SHORT).show()
    })
    col.addView(camCard)

    val tips = Ui.card(this)
    tips.addView(Ui.label(this, "💡 Consejos", 17f, Ui.TEXT, true))
    tips.addView(Ui.label(this,
      "• Cuando Android pregunte qué grabar, elige «Una sola app» → Mobile Legends. Así la burbuja de control no sale en el directo.\n" +
      "• Activa el modo No molestar para que no salgan notificaciones.\n" +
      "• Usa la burbuja ● sobre el juego para mover la cámara, poner «Ya vuelvo» o terminar.", 14f, Ui.MUTED))
    col.addView(tips)

    col.addView(Ui.button(this, "🔴  INICIAR DIRECTO") { startLive() })

    startDiscovery()
    testClient = MjpegClient { prefs.camHost }.also { it.start() }
    ui.post(tick)
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  private fun hasOverlay() = Settings.canDrawOverlays(this)

  private fun refresh() {
    val ok = "✅"; val no = "❌"
    checklist.text = buildString {
      append("${if (prefs.streamKey.isNotBlank()) ok else no} Clave de emisión\n")
      append("${if (prefs.alertsUrl.isNotBlank()) ok else "⚠️"} URL de alertas (opcional)\n")
      append("${if (hasMic()) ok else no} Micrófono\n")
      append("${if (hasNotif()) ok else no} Notificaciones\n")
      append("${if (hasOverlay()) ok else no} Mostrar sobre otras apps (burbuja y alertas)")
    }
  }

  private fun requestMissing() {
    val perms = mutableListOf<String>()
    if (!hasMic()) perms += Manifest.permission.RECORD_AUDIO
    if (!hasNotif() && Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
    if (perms.isNotEmpty()) { askPerms.launch(perms.toTypedArray()); return }
    if (!hasOverlay()) {
      startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
      Toast.makeText(this, "Activa «Mostrar sobre otras apps» para Starvios Live", Toast.LENGTH_LONG).show()
      return
    }
    if (prefs.streamKey.isBlank()) startActivity(Intent(this, SettingsActivity::class.java))
  }

  private fun startLive() {
    if (prefs.streamKey.isBlank()) {
      Toast.makeText(this, "Primero pega tu clave de emisión", Toast.LENGTH_LONG).show()
      startActivity(Intent(this, SettingsActivity::class.java)); return
    }
    if (!hasMic() || !hasNotif() || !hasOverlay()) { requestMissing(); return }
    if (StreamService.isRunning) {
      Toast.makeText(this, "Ya estás transmitiendo", Toast.LENGTH_SHORT).show(); return
    }
    testClient?.stop(); testClient = null
    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    askCapture.launch(mpm.createScreenCaptureIntent())
  }

  private val tick = object : Runnable {
    override fun run() {
      val c = testClient
      if (c != null && c.isReceiving) {
        camStatus.text = "✅ Cámara conectada (${prefs.camHost})"
        camStatus.setTextColor(Ui.GREEN)
        c.latest?.let { camPreview.setImageBitmap(it) }
      } else {
        camStatus.text = if (prefs.camHost.isBlank()) "⏳ Buscando cámara en el Wi‑Fi…" else "⏳ Conectando a ${prefs.camHost}…"
        camStatus.setTextColor(Ui.GOLD)
      }
      ui.postDelayed(this, 200)
    }
  }

  // ---------- Búsqueda automática del teléfono cámara ----------
  private fun startDiscovery() {
    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    multicastLock = wifi.createMulticastLock("starvios-find").apply { setReferenceCounted(false); acquire() }
    val manager = getSystemService(NSD_SERVICE) as NsdManager
    nsd = manager
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) {}
      override fun onDiscoveryStopped(serviceType: String) {}
      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
      override fun onServiceLost(info: NsdServiceInfo) {}
      override fun onServiceFound(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        manager.resolveService(info, object : NsdManager.ResolveListener {
          override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
          override fun onServiceResolved(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val host = info.host?.hostAddress ?: return
            if (host.contains(":")) return // solo IPv4
            val spec = "$host:${info.port}"
            ui.post {
              if (testClient?.isReceiving != true) prefs.camHost = spec
            }
          }
        })
      }
    }
    discovery = listener
    runCatching { manager.discoverServices(Prefs.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
  }

  override fun onDestroy() {
    super.onDestroy()
    ui.removeCallbacks(tick)
    testClient?.stop()
    runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
    runCatching { multicastLock?.release() }
  }
}
