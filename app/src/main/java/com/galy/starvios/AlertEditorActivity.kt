package com.galy.starvios

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.galy.starvios.Ui.dp
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Convierte un video (por ejemplo uno guardado de TikTok) en archivos para las alertas de Starvios:
 *  - Video recortado (MP4) para «Imagen, GIF o vídeo de la alerta» (máx. 25 MB).
 *  - Sonido recortado (WAV) para «Sonido» (máx. 12 MB).
 */
class AlertEditorActivity : AppCompatActivity() {

  private val ui = Handler(Looper.getMainLooper())
  private lateinit var col: LinearLayout
  private lateinit var video: VideoView
  private lateinit var info: TextView
  private lateinit var startBar: SeekBar
  private lateinit var lenBar: SeekBar
  private lateinit var results: LinearLayout
  private var source: File? = null
  private var durationSec = 0.0
  private val made = mutableListOf<File>()

  private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { load(it) } }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    col = Ui.page(this)
    col.addView(Ui.eyebrow(this, "Alertas"))
    col.addView(Ui.title(this, "Crear alerta con un video"))
    col.addView(Ui.label(this,
      "1) En TikTok toca Compartir → «Guardar video».\n2) Elígelo aquí, recorta el momento y crea el video y/o el sonido.\n3) Súbelos en Starvios → Alertas OBS (para seguidor, sub, Starvies…).", 14f, Ui.MUTED))
    col.addView(Ui.button(this, "🎞  Elegir video de la galería") { pick.launch("video/*") })

    val editor = Ui.card(this)
    video = VideoView(this)
    editor.addView(video, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
    info = Ui.label(this, "Sin video", 14f, Ui.GOLD, true)
    editor.addView(info)
    editor.addView(Ui.label(this, "Inicio", 13f, Ui.MUTED))
    startBar = SeekBar(this).apply { max = 1000 }
    editor.addView(startBar)
    editor.addView(Ui.label(this, "Duración (1 a 15 s)", 13f, Ui.MUTED))
    lenBar = SeekBar(this).apply { max = 140; progress = 50 } // 1.0 s + progress/10
    editor.addView(lenBar)
    editor.addView(Ui.button(this, "▶  Ver recorte", primary = false) { preview() })
    col.addView(editor)

    col.addView(Ui.button(this, "🎬  Crear VIDEO de alerta") { make(video = true) })
    col.addView(Ui.button(this, "🔊  Crear SONIDO de alerta") { make(video = false) })

    results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(results)

    val listener = object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { updateInfo() }
      override fun onStartTrackingTouch(s: SeekBar) {}
      override fun onStopTrackingTouch(s: SeekBar) { preview() }
    }
    startBar.setOnSeekBarChangeListener(listener)
    lenBar.setOnSeekBarChangeListener(listener)

    // también acepta videos compartidos hacia la app
    @Suppress("DEPRECATION")
    (intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM))?.let { load(it) }
  }

  private fun startSec() = durationSec * startBar.progress / 1000.0
  private fun lenSec() = min(1.0 + lenBar.progress / 10.0, durationSec - startSec()).coerceAtLeast(0.5)

  private fun updateInfo() {
    if (source == null) return
    info.text = String.format(Locale.US, "Desde %.1f s · dura %.1f s (video total %.1f s)", startSec(), lenSec(), durationSec)
  }

  private fun load(uri: Uri) {
    info.text = "Cargando…"
    thread {
      try {
        val f = MediaTools.importUri(this, uri, File(MediaTools.workDir(this, "alertas"), "origen.mp4"))
        val d = Highlights.durationSec(f)
        ui.post {
          source = f; durationSec = d
          startBar.progress = 0
          video.setVideoURI(Uri.fromFile(f))
          updateInfo(); preview()
        }
      } catch (e: Exception) {
        ui.post { info.text = "No pude abrir ese video: ${e.message}" }
      }
    }
  }

  private val stopPreview = Runnable { if (video.isPlaying) video.pause() }

  private fun preview() {
    if (source == null) return
    ui.removeCallbacks(stopPreview)
    video.seekTo((startSec() * 1000).toInt())
    video.start()
    ui.postDelayed(stopPreview, (lenSec() * 1000).toLong())
  }

  private fun make(video: Boolean) {
    val src = source ?: run { toast("Primero elige un video"); return }
    val s = startSec(); val e = s + lenSec()
    val n = made.size + 1
    val out = File(MediaTools.workDir(this, "alertas"), if (video) "alerta_video_$n.mp4" else "alerta_sonido_$n.wav")
    toast("Creando…")
    thread {
      try {
        if (video) Highlights.cut(src, out, s, e) else MediaTools.extractWav(src, out, s, e)
        val mb = out.length() / 1_048_576.0
        val limit = if (video) 25.0 else 12.0
        ui.post {
          if (mb > limit) {
            toast(String.format(Locale.US, "Pesa %.1f MB y Starvios acepta hasta %.0f MB. Hazlo más corto.", mb, limit))
            out.delete()
          } else {
            made += out
            MediaTools.saveToPhone(this, out, if (video) "video/mp4" else "audio/wav", downloads = true)
            addResult(out, mb)
          }
        }
      } catch (ex: Exception) {
        ui.post { toast("Error: ${ex.message}") }
      }
    }
  }

  private fun addResult(f: File, mb: Double) {
    val card = Ui.card(this)
    card.addView(Ui.label(this, "✅ ${f.name}", 15f, Ui.TEXT, true))
    card.addView(Ui.label(this, String.format(Locale.US, "%.1f MB · copia guardada en Descargas/StarviosLive", mb), 12f, Ui.MUTED))
    card.addView(Ui.button(this, "⭐  Subir a Starvios (Alertas OBS)") {
      StarviosWebActivity.open(
        this, "https://starvios.com/studio", made.toList(),
        "🔔 Ve a Alertas OBS, elige la alerta (seguidor, sub, Starvies…) y toca «Subir archivo» o «Subir audio»: te muestro tus archivos para adjuntar."
      )
    })
    results.addView(card, 0)
  }

  override fun onPause() {
    super.onPause()
    if (video.isPlaying) video.pause()
  }

  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
