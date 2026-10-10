package com.galy.starvios

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pequeños ayudantes para construir la interfaz por código (estilo Starvios). */
object Ui {
  const val BG = 0xFF0B0B10.toInt()
  const val CARD = 0xFF16161D.toInt()
  const val PINK = 0xFFFF5D9E.toInt()
  const val GOLD = 0xFFF7C948.toInt()
  const val TEXT = 0xFFFFFFFF.toInt()
  const val MUTED = 0xFF9A9AA8.toInt()
  const val RED = 0xFFFF3B4E.toInt()
  const val GREEN = 0xFF2BD47D.toInt()

  fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

  fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
      setColor(color)
      cornerRadius = radius
      if (stroke > 0) setStroke(stroke, strokeColor)
    }

  fun page(activity: Activity): LinearLayout {
    val scroll = ScrollView(activity).apply { setBackgroundColor(BG); isFillViewport = true }
    val col = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      val p = activity.dp(20)
      setPadding(p, activity.dp(36), p, p)
    }
    scroll.addView(col)
    activity.setContentView(scroll)
    return col
  }

  fun label(ctx: Context, text: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
    TextView(ctx).apply {
      this.text = text
      textSize = size
      setTextColor(color)
      if (bold) typeface = Typeface.DEFAULT_BOLD
      setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    }

  fun eyebrow(ctx: Context, text: String) = label(ctx, text.uppercase(), 12f, PINK, true).apply {
    letterSpacing = 0.25f
  }

  fun title(ctx: Context, text: String) = label(ctx, text, 30f, TEXT, true)

  fun button(ctx: Context, text: String, primary: Boolean = true, onClick: (View) -> Unit): Button =
    Button(ctx).apply {
      this.text = text
      isAllCaps = false
      textSize = 17f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(if (primary) Color.BLACK else TEXT)
      background = rounded(if (primary) PINK else CARD, ctx.dp(28).toFloat(), if (primary) 0 else ctx.dp(1), 0xFF2A2A35.toInt())
      minHeight = ctx.dp(58)
      setOnClickListener(onClick)
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = ctx.dp(10)
      }
    }

  fun input(ctx: Context, hint: String, value: String, secret: Boolean = false): EditText =
    EditText(ctx).apply {
      this.hint = hint
      setText(value)
      setTextColor(TEXT)
      setHintTextColor(MUTED)
      textSize = 15f
      isSingleLine = true
      inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      background = rounded(CARD, ctx.dp(16).toFloat(), ctx.dp(1), 0xFF2A2A35.toInt())
      val p = ctx.dp(14)
      setPadding(p, p, p, p)
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = ctx.dp(6)
      }
    }

  fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(CARD, ctx.dp(22).toFloat(), ctx.dp(1), 0xFF2A2A35.toInt())
    val p = ctx.dp(18)
    setPadding(p, p, p, p)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
      topMargin = ctx.dp(14)
    }
    gravity = Gravity.START
  }
}
