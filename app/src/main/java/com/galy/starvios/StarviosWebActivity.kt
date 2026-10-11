package com.galy.starvios

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.galy.starvios.Ui.dp
import java.io.File

/**
 * Starvios dentro de la app. Cuando la web pide «Subir archivo», la app ofrece
 * directamente tus clips o archivos de alerta, sin buscarlos en el teléfono.
 * Inicia sesión una sola vez; la sesión queda guardada.
 */
class StarviosWebActivity : AppCompatActivity() {

  companion object {
    private const val EXTRA_URL = "url"
    private const val EXTRA_FILES = "files"
    private const val EXTRA_HINT = "hint"

    fun open(ctx: Context, url: String, files: List<File>, hint: String) {
      ctx.startActivity(
        Intent(ctx, StarviosWebActivity::class.java)
          .putExtra(EXTRA_URL, url)
          .putExtra(EXTRA_FILES, files.map { it.path }.toTypedArray())
          .putExtra(EXTRA_HINT, hint)
      )
    }
  }

  private lateinit var web: WebView
  private var pendingCallback: ValueCallback<Array<Uri>>? = null
  private var files: List<File> = emptyList()
  private val used = mutableSetOf<String>()

  private val systemPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    pendingCallback?.onReceiveValue(uri?.let { arrayOf(it) })
    pendingCallback = null
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    files = intent.getStringArrayExtra(EXTRA_FILES)?.map { File(it) }?.filter { it.exists() } ?: emptyList()

    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
    val tip = TextView(this).apply {
      text = intent.getStringExtra(EXTRA_HINT) ?: ""
      setTextColor(Ui.TEXT)
      textSize = 13f
      background = Ui.rounded(0xFF2A0F24.toInt(), 0f)
      val p = dp(10)
      setPadding(p, dp(32), p, p)
    }
    col.addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    web = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = false
      webViewClient = WebViewClient()
      webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
          pendingCallback?.onReceiveValue(null)
          pendingCallback = callback
          offerFiles(params.acceptTypes.joinToString(","))
          return true
        }
      }
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    col.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(col)
    web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "https://starvios.com/studio")
  }

  private fun offerFiles(accept: String) {
    val wantsAudio = accept.contains("audio")
    val wantsVideo = accept.contains("video") || accept.contains("image")
    val candidates = files.filter {
      val isAudio = it.extension.lowercase() in setOf("wav", "mp3", "ogg")
      when {
        wantsAudio && !wantsVideo -> isAudio
        wantsVideo && !wantsAudio -> !isAudio
        else -> true
      }
    }
    if (candidates.isEmpty()) { systemPicker.launch(if (wantsAudio && !wantsVideo) "audio/*" else "*/*"); return }
    val labels = candidates.map { (if (it.path in used) "✅ " else "") + it.name }.toMutableList()
    labels += "📁 Elegir otro archivo del teléfono"
    AlertDialog.Builder(this)
      .setTitle("¿Qué archivo subo?")
      .setItems(labels.toTypedArray()) { _, which ->
        if (which == candidates.size) {
          systemPicker.launch("*/*")
        } else {
          val f = candidates[which]
          used += f.path
          pendingCallback?.onReceiveValue(arrayOf(MediaTools.uriFor(this, f)))
          pendingCallback = null
        }
      }
      .setOnCancelListener { pendingCallback?.onReceiveValue(null); pendingCallback = null }
      .show()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (web.canGoBack()) web.goBack() else super.onBackPressed()
  }

  override fun onPause() {
    super.onPause()
    CookieManager.getInstance().flush()
  }
}
