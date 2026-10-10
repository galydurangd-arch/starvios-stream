package com.galy.starvios

import android.content.Context

/** Ajustes guardados solo en este teléfono. */
class Prefs(context: Context) {
  private val sp = context.getSharedPreferences("starvios", Context.MODE_PRIVATE)

  var server: String
    get() = sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
    set(v) = sp.edit().putString("server", v.trim()).apply()

  var streamKey: String
    get() = sp.getString("key", "") ?: ""
    set(v) = sp.edit().putString("key", v.trim()).apply()

  var alertsUrl: String
    get() = sp.getString("alerts", "") ?: ""
    set(v) = sp.edit().putString("alerts", v.trim()).apply()

  var bannerTitle: String
    get() = sp.getString("bannerTitle", "galydurangd") ?: ""
    set(v) = sp.edit().putString("bannerTitle", v).apply()

  var bannerSubtitle: String
    get() = sp.getString("bannerSub", "¡Sígueme en Starvios!") ?: ""
    set(v) = sp.edit().putString("bannerSub", v).apply()

  /** 0 = 720p, 1 = 1080p */
  var quality: Int
    get() = sp.getInt("quality", 1)
    set(v) = sp.edit().putInt("quality", v).apply()

  var camHost: String
    get() = sp.getString("camHost", "") ?: ""
    set(v) = sp.edit().putString("camHost", v.trim()).apply()

  /** 0 abajo-izq, 1 abajo-der, 2 arriba-izq, 3 arriba-der */
  var camCorner: Int
    get() = sp.getInt("camCorner", 0)
    set(v) = sp.edit().putInt("camCorner", v).apply()

  /** 0 pequeña, 1 mediana, 2 grande */
  var camSize: Int
    get() = sp.getInt("camSize", 1)
    set(v) = sp.edit().putInt("camSize", v).apply()

  var useFrontCamera: Boolean
    get() = sp.getBoolean("front", true)
    set(v) = sp.edit().putBoolean("front", v).apply()

  val fullUrl: String
    get() {
      val s = server.trimEnd('/')
      return "$s/$streamKey"
    }

  companion object {
    const val DEFAULT_SERVER = "rtmps://live.cloudflare.com:443/live/"
    const val CAM_PORT = 8090
    const val NSD_TYPE = "_starvioscam._tcp."
  }
}
