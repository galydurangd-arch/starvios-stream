package com.galy.starvios

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.galy.starvios.Ui.dp
import kotlin.math.abs

/**
 * Burbuja flotante sobre el juego para controlar el directo sin salir de MLBB.
 */
class FloatingControls(
  private val ctx: Context,
  private val actions: Actions,
) {
  interface Actions {
    fun toggleCam(): Boolean
    fun toggleBrb(): Boolean
    fun toggleBanner(): Boolean
    fun toggleMic(): Boolean // devuelve true si quedó silenciado
    fun nextCorner()
    fun nextSize()
    fun reloadAlerts()
    fun stop()
  }

  private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private lateinit var root: LinearLayout
  private lateinit var bubble: TextView
  private lateinit var panel: LinearLayout
  private lateinit var statusLine: TextView
  private lateinit var lp: WindowManager.LayoutParams
  private var expanded = false

  @SuppressLint("ClickableViewAccessibility")
  fun show() {
    root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    bubble = TextView(ctx).apply {
      text = "●"
      textSize = 22f
      gravity = Gravity.CENTER
      setTextColor(Ui.MUTED)
      background = Ui.rounded(0xEE0B0B10.toInt(), ctx.dp(26).toFloat(), ctx.dp(2), Ui.PINK)
    }
    root.addView(bubble, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))

    panel = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = Ui.rounded(0xF20B0B10.toInt(), ctx.dp(18).toFloat(), ctx.dp(1), 0xFF2A2A35.toInt())
      val p = ctx.dp(10)
      setPadding(p, p, p, p)
      visibility = View.GONE
    }
    statusLine = TextView(ctx).apply {
      textSize = 13f
      setTextColor(Ui.TEXT)
      typeface = Typeface.DEFAULT_BOLD
      setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(6))
    }
    panel.addView(statusLine)
    panel.addView(item("📷 Cámara: SÍ") { v -> v.text = if (actions.toggleCam()) "📷 Cámara: SÍ" else "📷 Cámara: NO" })
    panel.addView(item("↘ Mover cámara") { actions.nextCorner() })
    panel.addView(item("⤢ Tamaño cámara") { actions.nextSize() })
    panel.addView(item("☕ Ya vuelvo: NO") { v -> v.text = if (actions.toggleBrb()) "☕ Ya vuelvo: SÍ" else "☕ Ya vuelvo: NO" })
    panel.addView(item("🏷 Banner: SÍ") { v -> v.text = if (actions.toggleBanner()) "🏷 Banner: SÍ" else "🏷 Banner: NO" })
    panel.addView(item("🎤 Micrófono: ON") { v -> v.text = if (actions.toggleMic()) "🎤 Micrófono: OFF" else "🎤 Micrófono: ON" })
    panel.addView(item("🔔 Recargar alertas") { actions.reloadAlerts() })
    panel.addView(item("⏹ Terminar directo", danger = true) { actions.stop() })
    root.addView(panel, LinearLayout.LayoutParams(ctx.dp(200), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6) })

    lp = WindowManager.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = ctx.dp(8)
      y = ctx.dp(120)
    }

    var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
    bubble.setOnTouchListener { _, e ->
      when (e.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - downX; val dy = e.rawY - downY
          if (abs(dx) > 12 || abs(dy) > 12) moved = true
          if (moved) {
            lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
            runCatching { wm.updateViewLayout(root, lp) }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) toggle()
          true
        }
        else -> false
      }
    }
    wm.addView(root, lp)
  }

  private fun item(label: String, danger: Boolean = false, onClick: (TextView) -> Unit): TextView =
    TextView(ctx).apply {
      text = label
      textSize = 15f
      setTextColor(if (danger) Ui.RED else Ui.TEXT)
      val p = ctx.dp(10)
      setPadding(p, p, p, p)
      background = Ui.rounded(0xFF16161D.toInt(), ctx.dp(12).toFloat())
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = ctx.dp(5)
      }
      setOnClickListener { onClick(this) }
    }

  private fun toggle() {
    expanded = !expanded
    panel.visibility = if (expanded) View.VISIBLE else View.GONE
  }

  /** Actualiza el color de la burbuja y la línea de estado. */
  fun setStatus(live: Boolean, text: String, camOk: Boolean) {
    if (!::bubble.isInitialized) return
    bubble.setTextColor(if (live) Ui.RED else Ui.GOLD)
    statusLine.text = "${if (live) "🔴 EN VIVO" else "⏳"} $text\n${if (camOk) "📷 cámara conectada" else "📷 sin señal de cámara"}"
  }

  fun hide() {
    if (::root.isInitialized) runCatching { wm.removeView(root) }
  }
}
