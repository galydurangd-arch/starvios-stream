package com.galy.starvios

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = Prefs(this)
    val col = Ui.page(this)
    col.addView(Ui.eyebrow(this, "Configuración"))
    col.addView(Ui.title(this, "Tu canal"))

    val conn = Ui.card(this)
    conn.addView(Ui.label(this, "Conexión (Starvios → Studio → Emitir en directo)", 15f, Ui.TEXT, true))
    conn.addView(Ui.label(this, "Servidor", 13f, Ui.MUTED))
    val server = Ui.input(this, Prefs.DEFAULT_SERVER, prefs.server)
    conn.addView(server)
    conn.addView(Ui.label(this, "Clave de emisión (copia con el botón de Starvios y pégala aquí)", 13f, Ui.MUTED))
    val key = Ui.input(this, "Pega tu clave", prefs.streamKey, secret = true)
    conn.addView(key)
    col.addView(conn)

    val alerts = Ui.card(this)
    alerts.addView(Ui.label(this, "Alertas y chat (Starvios → Alertas OBS)", 15f, Ui.TEXT, true))
    alerts.addView(Ui.label(this, "Toca «Copiar URL» en la fuente de navegador y pégala aquí. Así salen solos los seguidores, subs, Starvies, raids y el chat.", 13f, Ui.MUTED))
    val alertsUrl = Ui.input(this, "https://starvios.com/…", prefs.alertsUrl, secret = true)
    alerts.addView(alertsUrl)
    col.addView(alerts)

    val banner = Ui.card(this)
    banner.addView(Ui.label(this, "Banner", 15f, Ui.TEXT, true))
    val bTitle = Ui.input(this, "Nombre que sale en el banner", prefs.bannerTitle)
    banner.addView(bTitle)
    val bSub = Ui.input(this, "Texto pequeño (redes, frase…)", prefs.bannerSubtitle)
    banner.addView(bSub)
    col.addView(banner)

    val q = Ui.card(this)
    q.addView(Ui.label(this, "Calidad", 15f, Ui.TEXT, true))
    val group = RadioGroup(this)
    val r720 = RadioButton(this).apply { id = 720; text = "720p · 3000 kbps (internet lento)"; setTextColor(Ui.TEXT) }
    val r1080 = RadioButton(this).apply { id = 1080; text = "1080p · 4500 kbps (recomendado)"; setTextColor(Ui.TEXT) }
    group.addView(r1080); group.addView(r720)
    group.check(if (prefs.quality == 1) 1080 else 720)
    q.addView(group)
    col.addView(q)

    val look = Ui.card(this)
    look.addView(Ui.label(this, "Estilo del directo", 15f, Ui.TEXT, true))
    val themeGroup = RadioGroup(this)
    val t0 = RadioButton(this).apply { id = 500; text = "⭐ Starvios (rosa y dorado)"; setTextColor(Ui.TEXT) }
    val t1 = RadioButton(this).apply { id = 501; text = "🗡 Mercenario (rojo y negro)"; setTextColor(Ui.TEXT) }
    themeGroup.addView(t0); themeGroup.addView(t1)
    themeGroup.check(if (prefs.theme == 1) 501 else 500)
    look.addView(themeGroup)
    val clips = android.widget.CheckBox(this).apply {
      text = "🎬 Grabar el directo para crear clips al final"
      setTextColor(Ui.TEXT)
      isChecked = prefs.makeClips
    }
    look.addView(clips)
    col.addView(look)

    col.addView(Ui.button(this, "Guardar") {
      prefs.theme = if (themeGroup.checkedRadioButtonId == 501) 1 else 0
      prefs.makeClips = clips.isChecked
      prefs.server = server.text.toString().ifBlank { Prefs.DEFAULT_SERVER }
      prefs.streamKey = key.text.toString()
      prefs.alertsUrl = alertsUrl.text.toString()
      prefs.bannerTitle = bTitle.text.toString()
      prefs.bannerSubtitle = bSub.text.toString()
      prefs.quality = if (group.checkedRadioButtonId == 1080) 1 else 0
      Toast.makeText(this, "Guardado ✅", Toast.LENGTH_SHORT).show()
      finish()
    })
  }
}
