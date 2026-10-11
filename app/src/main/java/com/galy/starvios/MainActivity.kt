package com.galy.starvios

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    render()
  }

  override fun onResume() {
    super.onResume()
    render()
  }

  private fun render() {
    val col = Ui.page(this)
    col.addView(Ui.eyebrow(this, "Starvios Live"))
    col.addView(Ui.title(this, "¿Qué hacemos hoy?"))

    if (StreamService.isRunning) {
      val live = Ui.card(this)
      live.addView(Ui.label(this, "🔴 Transmisión en curso", 18f, Ui.RED, true))
      live.addView(Ui.label(this, "Usa la burbuja flotante para controlar el directo.", 14f, Ui.MUTED))
      live.addView(Ui.button(this, "⏹  Detener transmisión") {
        startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        it.postDelayed({ render() }, 400)
      })
      col.addView(live)
    }

    val streamCard = Ui.card(this)
    streamCard.addView(Ui.label(this, "🎮  Transmitir", 20f, Ui.TEXT, true))
    streamCard.addView(Ui.label(this, "Usa este modo en el teléfono donde juegas. Envía el juego + la cámara del otro teléfono + alertas a Starvios.", 14f, Ui.MUTED))
    streamCard.addView(Ui.button(this, "Preparar directo") {
      startActivity(Intent(this, StreamSetupActivity::class.java))
    })
    streamCard.addView(Ui.button(this, "👁  Modo prueba (ver cómo se verá)", primary = false) {
      startActivity(Intent(this, PreviewActivity::class.java))
    })
    col.addView(streamCard)

    val camCard = Ui.card(this)
    camCard.addView(Ui.label(this, "📷  Modo cámara", 20f, Ui.TEXT, true))
    camCard.addView(Ui.label(this, "Usa este modo en el otro teléfono. Su cámara aparecerá en tu directo por Wi‑Fi.", 14f, Ui.MUTED))
    camCard.addView(Ui.button(this, "Ser la cámara", primary = false) {
      startActivity(Intent(this, CameraActivity::class.java))
    })
    col.addView(camCard)

    col.addView(Ui.button(this, "🔔  Mis alertas (videos de TikTok)", primary = false) {
      startActivity(Intent(this, AlertKitActivity::class.java))
    })
    col.addView(Ui.button(this, "🎬  Mis clips", primary = false) {
      startActivity(Intent(this, ClipsActivity::class.java))
    })
    col.addView(Ui.button(this, "⚙️  Configuración (clave, alertas, banner)", primary = false) {
      startActivity(Intent(this, SettingsActivity::class.java))
    })
  }
}
