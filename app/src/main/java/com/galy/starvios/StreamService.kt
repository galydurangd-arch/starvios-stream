package com.galy.starvios

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import java.io.File

/**
 * Servicio que transmite: pantalla (juego) + audio del juego y micrófono + capa de overlays → Starvios.
 */
class StreamService : Service(), ConnectChecker, FloatingControls.Actions {

  companion object {
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"
    private const val CHANNEL = "directo"
    private const val NOTIF_ID = 7
    @Volatile var isRunning = false
      private set
  }

  private val main = Handler(Looper.getMainLooper())
  private lateinit var prefs: Prefs
  private var projection: MediaProjection? = null
  private var stream: GenericStream? = null
  private var compositor: Compositor? = null
  private var cam: MjpegClient? = null
  private var alerts: AlertsLayer? = null
  private var controls: FloatingControls? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private val state = OverlayState()
  @Volatile private var statusText = "Conectando…"
  private var micMuted = false
  private var recording: File? = null
  private var recordStartMs = 0L
  private val clipMarks = mutableListOf<Double>()
  private var width = 1920
  private var height = 1080

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
      ACTION_START -> {
        if (isRunning) return START_NOT_STICKY
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        try {
          begin(code, data)
        } catch (e: Exception) {
          toast("No se pudo iniciar: ${e.message}")
          shutdown()
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun goForeground() {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Directo", NotificationManager.IMPORTANCE_LOW))
    val stopIntent = PendingIntent.getService(
      this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val openIntent = PendingIntent.getActivity(
      this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
    val n = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_notif)
      .setContentTitle("Starvios Live")
      .setContentText("Transmitiendo tu pantalla")
      .setOngoing(true)
      .setContentIntent(openIntent)
      .addAction(0, "Terminar directo", stopIntent)
      .build()
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    ServiceCompat.startForeground(this, NOTIF_ID, n, type)
  }

  private fun begin(code: Int, data: Intent) {
    prefs = Prefs(this)
    isRunning = true
    if (prefs.quality == 1) { width = 1920; height = 1080 } else { width = 1280; height = 720 }
    val bitrate = if (prefs.quality == 1) 4500 * 1000 else 3000 * 1000

    state.bannerTitle = prefs.bannerTitle
    state.bannerSubtitle = prefs.bannerSubtitle
    state.camCorner = prefs.camCorner
    state.camSize = prefs.camSize
    state.theme = prefs.theme

    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("sin permiso de captura")
    projection = mp
    val projectionCallback = object : MediaProjection.Callback() {
      override fun onStop() { main.post { shutdown() } }
    }

    val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val audio = if (hasMic) MixAudioSource(mp) else MicrophoneSource()
    val s = GenericStream(this, this, ScreenSource(applicationContext, mp, projectionCallback), audio)
    s.getGlInterface().setForceRender(true, 30)
    val okVideo = s.prepareVideo(width, height, bitrate, 30, 2, 0)
    var okAudio = runCatching { s.prepareAudio(44100, true, 128 * 1000) }.getOrDefault(false)
    if (!okAudio) {
      s.changeAudioSource(MicrophoneSource())
      okAudio = runCatching { s.prepareAudio(44100, true, 128 * 1000) }.getOrDefault(false)
    }
    if (!okVideo || !okAudio) throw IllegalStateException("tu teléfono no aceptó la calidad elegida, prueba 720p")
    if (micMuted) (audio as? MixAudioSource)?.mute()
    stream = s

    // Capa de overlays (cámara, banner, alertas) encima del juego
    cam = MjpegClient { prefs.camHost }.also { it.start() }
    alerts = AlertsLayer(this, prefs.alertsUrl).also { it.start() }
    val overlay = SurfaceFilterRender { st ->
      st.setDefaultBufferSize(width, height)
    }
    s.getGlInterface().addFilter(overlay)

    s.getStreamClient().setReTries(30)
    s.startStream(prefs.fullUrl)
    startCompositorWhenReady(overlay, 0)
    startRecording(s)

    controls = FloatingControls(this, this).also { runCatching { it.show() } }
    wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "starvios:live").apply { acquire(6 * 60 * 60 * 1000L) }
    main.post(statusTick)
  }

  /** Graba el directo (con cámara y alertas) para sacar los clips al final. */
  private fun startRecording(s: GenericStream) {
    if (!prefs.makeClips) return
    val dir = MediaTools.workDir(this, "grabaciones")
    dir.listFiles()?.forEach { it.delete() } // restos de directos anteriores
    if (dir.usableSpace < 1_500_000_000L) {
      toast("Poco espacio libre: este directo no generará clips")
      return
    }
    val f = File(dir, "directo_${System.currentTimeMillis()}.mp4")
    runCatching {
      s.startRecord(f.path, null) { status ->
        if (status == RecordController.Status.RECORDING && recordStartMs == 0L) recordStartMs = System.currentTimeMillis()
      }
      recording = f
      recordStartMs = System.currentTimeMillis()
    }
  }

  /** El Surface del filtro aparece cuando arranca OpenGL; esperamos a que exista. */
  private fun startCompositorWhenReady(filter: SurfaceFilterRender, tries: Int) {
    val surface = filter.surface
    if (surface != null && surface.isValid) {
      filter.surfaceTexture?.setDefaultBufferSize(width, height)
      compositor = Compositor(surface, width, height, cam, alerts, state).also { it.start() }
      return
    }
    if (tries < 100 && isRunning) main.postDelayed({ startCompositorWhenReady(filter, tries + 1) }, 100)
  }

  private val statusTick = object : Runnable {
    override fun run() {
      if (!isRunning) return
      controls?.setStatus(state.live, statusText, cam?.isReceiving == true)
      main.postDelayed(this, 1000)
    }
  }

  // ---------- ConnectChecker ----------
  override fun onConnectionStarted(url: String) { statusText = "Conectando…" }

  override fun onConnectionSuccess() {
    state.live = true
    statusText = "Conectado a Starvios"
    main.post { toast("🔴 ¡Estás en vivo en Starvios!") }
  }

  override fun onConnectionFailed(reason: String) {
    state.live = false
    val retrying = stream?.getStreamClient()?.reTry(4000, reason) == true
    statusText = if (retrying) "Reconectando…" else "Falló: $reason"
    if (!retrying) main.post {
      toast("No se pudo conectar a Starvios. Revisa la clave en Configuración.")
      shutdown()
    }
  }

  override fun onDisconnect() {
    state.live = false
    statusText = "Desconectado"
  }

  override fun onAuthError() {
    main.post { toast("Starvios rechazó la clave de emisión"); shutdown() }
  }

  override fun onAuthSuccess() {}

  // ---------- Botones de la burbuja ----------
  override fun toggleCam(): Boolean { state.showCam = !state.showCam; return state.showCam }
  override fun toggleBrb(): Boolean { state.brb = !state.brb; return state.brb }
  override fun toggleBanner(): Boolean { state.showBanner = !state.showBanner; return state.showBanner }

  override fun toggleMic(): Boolean {
    micMuted = !micMuted
    when (val a = stream?.audioSource) {
      is MixAudioSource -> if (micMuted) a.mute() else a.unMute()
      is MicrophoneSource -> if (micMuted) a.mute() else a.unMute()
    }
    return micMuted
  }

  override fun nextCorner() {
    state.camCorner = (state.camCorner + 1) % 4
    prefs.camCorner = state.camCorner
  }

  override fun nextSize() {
    state.camSize = (state.camSize + 1) % 3
    prefs.camSize = state.camSize
  }

  override fun reloadAlerts() { alerts?.reload() }

  override fun markClip() {
    if (recording == null || recordStartMs == 0L) { toast("La grabación para clips está desactivada"); return }
    clipMarks += (System.currentTimeMillis() - recordStartMs) / 1000.0
    toast("✂️ Jugada marcada (${clipMarks.size})")
  }

  override fun stop() { shutdown() }

  // ---------- Cierre ----------
  private fun shutdown() {
    main.removeCallbacks(statusTick)
    controls?.hide(); controls = null
    compositor?.stop(); compositor = null
    alerts?.stop(); alerts = null
    cam?.stop(); cam = null
    val rec = recording
    recording = null
    stream?.let {
      runCatching { if (it.isRecording) it.stopRecord() }
      runCatching { if (it.isStreaming) it.stopStream() }
      runCatching { it.release() }
    }
    stream = null
    runCatching { projection?.stop() }
    projection = null
    runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    isRunning = false
    if (rec != null && rec.exists() && rec.length() > 500_000) {
      runCatching { ClipsActivity.open(this, rec, clipMarks.toList()) }
    }
    clipMarks.clear()
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    if (isRunning) shutdown()
    super.onDestroy()
  }

  private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
