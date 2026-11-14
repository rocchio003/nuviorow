package com.nuviorows

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CatalogCfg(
    val type: String,
    val id: String,
    val name: String,
    var enabled: Boolean
) {
    val key: String get() = "$type|$id"
}

data class AddonCfg(
    val baseUrl: String,
    val name: String,
    val catalogs: MutableList<CatalogCfg>
)

class Store(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("nuviorows", Context.MODE_PRIVATE)

    var appPackage: String
        get() = sp.getString("appPackage", "") ?: ""
        set(v) = sp.edit().putString("appPackage", v.trim()).apply()

    var deepLink: String
        get() = sp.getString("deepLink", "") ?: ""
        set(v) = sp.edit().putString("deepLink", v.trim()).apply()

    // "landscape" = immagini orizzontali 16:9 (se disponibili), "poster" = locandine verticali 2:3
    var cardStyle: String
        get() = sp.getString("cardStyle", "landscape") ?: "landscape"
        set(v) = sp.edit().putString("cardStyle", v).apply()

    var m3uUrl: String
        get() = sp.getString("m3uUrl", "") ?: ""
        set(v) = sp.edit().putString("m3uUrl", v.trim()).apply()

    var epgUrl: String
        get() = sp.getString("epgUrl", "") ?: ""
        set(v) = sp.edit().putString("epgUrl", v.trim()).apply()

    var sparklePackage: String
        get() = (sp.getString("sparklePackage", "") ?: "").ifBlank { "se.hedekonsult.sparkle" }
        set(v) = sp.edit().putString("sparklePackage", v.trim()).apply()

    // Opzionale: deep link per aprire un canale in Sparkle TV. Segnaposto: {name} {id} {url}
    var sparkleLink: String
        get() = sp.getString("sparkleLink", "") ?: ""
        set(v) = sp.edit().putString("sparkleLink", v.trim()).apply()

    var maxItems: Int
        get() = sp.getInt("maxItems", 20)
        set(v) = sp.edit().putInt("maxItems", v).apply()

    fun loadAddons(): MutableList<AddonCfg> {
        val out = mutableListOf<AddonCfg>()
        try {
            val arr = JSONArray(sp.getString("addons", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cats = mutableListOf<CatalogCfg>()
                val ca = o.getJSONArray("catalogs")
                for (j in 0 until ca.length()) {
                    val c = ca.getJSONObject(j)
                    cats.add(
                        CatalogCfg(
                            c.getString("type"),
                            c.getString("id"),
                            c.optString("name"),
                            c.optBoolean("enabled", false)
                        )
                    )
                }
                out.add(AddonCfg(o.getString("url"), o.optString("name"), cats))
            }
        } catch (e: Exception) {
            // dati corrotti: riparto da lista vuota
        }
        return out
    }

    fun saveAddons(list: List<AddonCfg>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("url", a.baseUrl)
            o.put("name", a.name)
            val ca = JSONArray()
            for (c in a.catalogs) {
                val co = JSONObject()
                co.put("type", c.type)
                co.put("id", c.id)
                co.put("name", c.name)
                co.put("enabled", c.enabled)
                ca.put(co)
            }
            o.put("catalogs", ca)
            arr.put(o)
        }
        sp.edit().putString("addons", arr.toString()).apply()
    }

    fun loadChannelIds(): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        try {
            val o = JSONObject(sp.getString("channels", "{}"))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.getLong(k)
            }
        } catch (e: Exception) {
            // ignora
        }
        return out
    }

    fun saveChannelIds(map: Map<String, Long>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        sp.edit().putString("channels", o.toString()).apply()
    }
}
