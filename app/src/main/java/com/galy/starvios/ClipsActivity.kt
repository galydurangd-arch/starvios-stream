package com.galy.starvios

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.galy.starvios.Ui.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Se abre al terminar el directo: busca las jugadas destacadas, corta al menos 5 clips
 * y pregunta si quieres subirlos a TikTok y/o a Starvios.
 */
class ClipsActivity : AppCompatActivity() {

  companion object {
    private const val EXTRA_REC = "rec"
    private const val EXTRA_MARKS = "marks"
    private val TIKTOK = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

    fun open(ctx: Context, recording: File, marks: List<Double>) {
      ctx.startActivity(
        Intent(ctx, ClipsActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra(EXTRA_REC, recording.path)
          .putExtra(EXTRA_MARKS, marks.toDoubleArray())
      )
    }
  }

  private data class Item(val file: File, val window: ClipWindow, val check: CheckBox)

  private val ui = Handler(Looper.getMainLooper())
  private lateinit var col: LinearLayout
  private lateinit var status: TextView
  private val items = mutableListOf<Item>()
  private val tiktokQueue = ArrayDeque<File>()
  private var processing = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    col = Ui.page(this)
    col.addView(Ui.eyebrow(this, "Directo terminado"))
    col.addView(Ui.title(this, "Tus jugadas destacadas"))
    status = Ui.label(this, "Buscando los mejores momentos…", 15f, Ui.GOLD, true)
    col.addView(status)

    val rec = intent.getStringExtra(EXTRA_REC)?.let { File(it) }
    if (savedInstanceState == null && rec != null && rec.exists()) process(rec, intent.getDoubleArrayExtra(EXTRA_MARKS)?.toList() ?: emptyList())
    else showExisting()
  }

  private fun process(rec: File, marks: List<Double>) {
    processing = true
    val outDir = MediaTools.workDir(this, "clips")
    thread(name = "clips") {
      val result = mutableListOf<Pair<File, ClipWindow>>()
      try {
        // la grabación se termina de escribir en segundo plano: esperar a que deje de crecer
        ui.post { status.text = "Cerrando la grabación…" }
        var last = -1L
        var stable = 0
        while (stable < 3) {
          Thread.sleep(500)
          val len = rec.length()
          if (len == last) stable++ else { stable = 0; last = len }
        }
        val dur = Highlights.durationSec(rec)
        val loud = Highlights.loudnessPerSecond(rec) { p ->
          ui.post { status.text = "Buscando los mejores momentos… ${(p * 100).toInt()}%" }
        }
        val windows = Highlights.pick(loud, dur, marks)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        windows.forEachIndexed { i, w ->
          ui.post { status.text = "Cortando clip ${i + 1} de ${windows.size}…" }
          val f = File(outDir, "clip_${stamp}_${i + 1}.mp4")
          Highlights.cut(rec, f, w.startSec, w.endSec)
          result += f to w
        }
      } catch (e: Exception) {
        ui.post { status.text = "Error al crear clips: ${e.message}" }
      } finally {
        rec.delete() // la grabación completa ocupa mucho; nos quedamos solo con los clips
      }
      ui.post {
        processing = false
        result.forEach { (f, w) -> addItem(f, w) }
        afterProcessing()
      }
    }
  }

  private fun showExisting() {
    val dir = MediaTools.workDir(this, "clips")
    dir.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() }?.take(10)?.forEach {
      addItem(it, ClipWindow(0.0, Highlights.durationSec(it), "🎬 Clip guardado"))
    }
    afterProcessing()
  }

  private fun afterProcessing() {
    if (items.isEmpty()) { status.text = "No hay clips (el directo fue muy corto)."; return }
    status.text = "✅ ${items.size} clips listos. Marca los que quieras subir."
    status.setTextColor(Ui.GREEN)

    col.addView(Ui.button(this, "🎵  Subir a TikTok") { uploadTikTok() })
    col.addView(Ui.button(this, "⭐  Subir a Starvios") { uploadStarvios() })
    col.addView(Ui.button(this, "💾  Guardar en la galería", primary = false) { saveGallery() })
    col.addView(Ui.button(this, "No subir ahora", primary = false) { finish() })

    AlertDialog.Builder(this)
      .setTitle("🎬 ${items.size} jugadas destacadas")
      .setMessage("¿Quieres subir tus clips?")
      .setPositiveButton("TikTok") { _, _ -> uploadTikTok() }
      .setNeutralButton("Starvios") { _, _ -> uploadStarvios() }
      .setNegativeButton("Revisar primero", null)
      .show()
  }

  private fun addItem(f: File, w: ClipWindow) {
    val card = Ui.card(this)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val thumb = ImageView(this).apply {
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = Ui.rounded(0xFF000000.toInt(), dp(12).toFloat())
      clipToOutline = true
      MediaTools.thumbnail(f, (w.endSec - w.startSec) / 2)?.let { setImageBitmap(it) }
      setOnClickListener { play(f) }
    }
    row.addView(thumb, LinearLayout.LayoutParams(dp(128), dp(72)))
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    texts.addView(Ui.label(this, w.reason, 15f, Ui.TEXT, true))
    val secs = (w.endSec - w.startSec).toInt()
    texts.addView(Ui.label(this, "Min ${fmt(w.startSec)} · ${secs}s · toca para ver", 12f, Ui.MUTED))
    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val check = CheckBox(this).apply { isChecked = true }
    row.addView(check)
    card.addView(row)
    col.addView(card, col.indexOfChild(status) + 1 + items.size)
    items += Item(f, w, check)
  }

  private fun fmt(s: Double): String = String.format(Locale.US, "%d:%02d", (s / 60).toInt(), (s % 60).toInt())

  private fun selected() = items.filter { it.check.isChecked }.map { it.file }

  private fun play(f: File) {
    runCatching {
      startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(MediaTools.uriFor(this, f), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
  }

  // ---------- TikTok: se comparte de a uno; al volver se ofrece el siguiente ----------
  private fun uploadTikTok() {
    val sel = selected()
    if (sel.isEmpty()) { toast("Marca al menos un clip"); return }
    tiktokQueue.clear(); tiktokQueue.addAll(sel)
    nextTikTok()
  }

  private fun nextTikTok() {
    val f = tiktokQueue.removeFirstOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "video/mp4"
      putExtra(Intent.EXTRA_STREAM, MediaTools.uriFor(this@ClipsActivity, f))
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val pkg = TIKTOK.firstOrNull { isInstalled(it) }
    if (pkg != null) send.setPackage(pkg)
    runCatching { startActivity(if (pkg != null) send else Intent.createChooser(send, "Subir clip")) }
      .onFailure { toast("No encontré TikTok instalado") }
  }

  override fun onResume() {
    super.onResume()
    if (tiktokQueue.isNotEmpty() && !processing) {
      AlertDialog.Builder(this)
        .setMessage("¿Subimos el siguiente clip a TikTok? (quedan ${tiktokQueue.size})")
        .setPositiveButton("Sí") { _, _ -> nextTikTok() }
        .setNegativeButton("Ya no") { _, _ -> tiktokQueue.clear() }
        .show()
    }
  }

  private fun isInstalled(pkg: String) = try {
    packageManager.getPackageInfo(pkg, 0); true
  } catch (_: PackageManager.NameNotFoundException) { false }

  // ---------- Starvios: dentro de la app, con los clips listos para adjuntar ----------
  private fun uploadStarvios() {
    val sel = selected()
    if (sel.isEmpty()) { toast("Marca al menos un clip"); return }
    StarviosWebActivity.open(
      this, "https://starvios.com/studio", sel,
      "⭐ Ve a Crear → Subir vídeo. Al tocar «elegir archivo» te muestro tus clips para adjuntarlos directo."
    )
  }

  private fun saveGallery() {
    val sel = selected()
    var ok = 0
    sel.forEach { if (MediaTools.saveToPhone(this, it, "video/mp4", downloads = false) != null) ok++ }
    toast("Guardados $ok clips en Galería → Movies/StarviosLive")
  }

  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
