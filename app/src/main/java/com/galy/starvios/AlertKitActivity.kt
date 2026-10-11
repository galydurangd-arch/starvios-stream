package com.galy.starvios

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.galy.starvios.Ui.dp
import java.io.File

/** Los tipos de alerta de Starvios. */
enum class AlertType(val id: String, val emoji: String, val label: String) {
  FOLLOW("seguidor", "❤️", "Seguidor nuevo"),
  SUB("suscripcion", "⭐", "Suscripción"),
  GIFT("regalo", "🎁", "Regalo"),
  STARVIES("starvies", "✨", "Starvies"),
  RAID("raid", "🚀", "Raid");

  fun videoFile(ctx: Context) = File(MediaTools.workDir(ctx, "alertas"), "alerta_${id}_video.mp4")
  fun soundFile(ctx: Context) = File(MediaTools.workDir(ctx, "alertas"), "alerta_${id}_sonido.wav")
}

/**
 * «Mis alertas»: una tarjeta por tipo (seguidor, sub, regalo, Starvies, raid)
 * con su video y su sonido hechos desde videos de TikTok.
 */
class AlertKitActivity : AppCompatActivity() {

  private var player: MediaPlayer? = null

  override fun onResume() {
    super.onResume()
    render()
  }

  private fun render() {
    val col = Ui.page(this)
    col.addView(Ui.eyebrow(this, "Mis alertas"))
    col.addView(Ui.title(this, "Una alerta para cada cosa"))
    col.addView(Ui.label(this, "Toca «Editar» y elige un video guardado de TikTok. Recorta el momento para el video y para el sonido.", 14f, Ui.MUTED))

    for (t in AlertType.values()) {
      val v = t.videoFile(this)
      val s = t.soundFile(this)
      val card = Ui.card(this)
      val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
      val thumb = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(0xFF000000.toInt(), dp(12).toFloat())
        clipToOutline = true
        if (v.exists()) MediaTools.thumbnail(v, 0.5)?.let { setImageBitmap(it) }
        setOnClickListener { if (v.exists()) play(v) }
      }
      row.addView(thumb, LinearLayout.LayoutParams(dp(96), dp(54)))
      val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
      texts.addView(Ui.label(this, "${t.emoji}  ${t.label}", 17f, Ui.TEXT, true))
      texts.addView(Ui.label(this,
        "${if (v.exists()) "✅" else "➖"} Video   ${if (s.exists()) "✅" else "➖"} Sonido", 13f, Ui.MUTED))
      row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      card.addView(row)

      val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
      val edit = Ui.button(this, "✏️ Editar", primary = !(v.exists() || s.exists())) {
        startActivity(Intent(this, AlertEditorActivity::class.java).putExtra(AlertEditorActivity.EXTRA_TYPE, t.name))
      }
      buttons.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
      if (s.exists()) {
        val hear = Ui.button(this, "🔊 Oír", primary = false) { hear(s) }
        buttons.addView(hear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
      }
      if (v.exists() || s.exists()) {
        val up = Ui.button(this, "⭐ Subir", primary = true) { upload(t) }
        buttons.addView(up, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
      }
      card.addView(buttons)
      col.addView(card)
    }
  }

  private fun upload(t: AlertType) {
    val files = listOf(t.videoFile(this), t.soundFile(this)).filter { it.exists() }
    StarviosWebActivity.open(
      this, "https://starvios.com/studio", files,
      "${t.emoji} ${t.label}: entra a Alertas OBS, elige «${t.label}» en la lista y toca «Subir archivo» / «Subir audio». Te muestro los archivos de esta alerta."
    )
  }

  private fun play(f: File) {
    runCatching {
      startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(MediaTools.uriFor(this, f), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
  }

  private fun hear(f: File) {
    player?.release()
    player = MediaPlayer().apply {
      setDataSource(f.path)
      setOnCompletionListener { it.release(); if (player === it) player = null }
      prepare(); start()
    }
  }

  override fun onPause() {
    super.onPause()
    player?.release(); player = null
  }
}
