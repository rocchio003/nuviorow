package com.nuviorows

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class ConnStream(private val conn: HttpURLConnection, ins: InputStream) : FilterInputStream(ins) {
    override fun close() {
        try {
            super.close()
        } finally {
            conn.disconnect()
        }
    }
}

object Net {

    // Apre un flusso (anche file grandi). Se il contenuto è gzip lo decomprime da solo.
    fun stream(urlStr: String, maxRedirects: Int = 5): InputStream {
        var current = urlStr
        for (i in 0..maxRedirects) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "NuvioRows/1.0")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException("Redirect senza Location")
                current = URL(URL(current), loc).toString()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            val raw = BufferedInputStream(ConnStream(conn, conn.inputStream), 65536)
            raw.mark(2)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw) else raw
        }
        throw IOException("Troppi redirect")
    }

    fun get(urlStr: String, maxRedirects: Int = 5): String {
        var current = urlStr
        for (i in 0..maxRedirects) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", "NuvioRows/1.0")
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect senza Location")
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Troppi redirect")
    }
}

data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String,
    val posterShape: String,
    val background: String,
    val description: String
)

object Stremio {

    fun normalizeBase(input: String): String {
        var u = input.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) {
            u = "https://" + u.substring("stremio://".length)
        }
        if (u.endsWith("/manifest.json", ignoreCase = true)) {
            u = u.substring(0, u.length - "/manifest.json".length)
        }
        return u.trimEnd('/')
    }

    fun loadAddon(input: String): AddonCfg {
        val base = normalizeBase(input)
        val json = JSONObject(Net.get("$base/manifest.json"))
        val name = json.optString("name", base).ifEmpty { base }
        val cats = mutableListOf<CatalogCfg>()
        val arr = json.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val type = c.optString("type")
            val id = c.optString("id")
            if (type.isEmpty() || id.isEmpty()) continue
            if (requiresExtra(c)) continue
            cats.add(CatalogCfg(type, id, c.optString("name", id).ifEmpty { id }, false))
        }
        return AddonCfg(base, name, cats)
    }

    // I cataloghi che richiedono un parametro obbligatorio (es. solo ricerca) non si possono mostrare come riga.
    private fun requiresExtra(c: JSONObject): Boolean {
        val extra = c.optJSONArray("extra")
        if (extra != null) {
            for (i in 0 until extra.length()) {
                if (extra.optJSONObject(i)?.optBoolean("isRequired", false) == true) return true
            }
        }
        val req = c.optJSONArray("extraRequired")
        if (req != null && req.length() > 0) return true
        return false
    }

    // org.json restituisce la stringa "null" per i valori null: qui li trasformo in stringa vuota.
    private fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        return o.optString(key, "")
    }

    fun fetchCatalog(base: String, type: String, id: String, limit: Int): List<Meta> {
        val url = "$base/catalog/${Uri.encode(type)}/${Uri.encode(id)}.json"
        val json = JSONObject(Net.get(url))
        val metas = json.optJSONArray("metas") ?: return emptyList()
        val out = mutableListOf<Meta>()
        for (i in 0 until metas.length()) {
            if (out.size >= limit) break
            val m = metas.optJSONObject(i) ?: continue
            val poster = str(m, "poster")
            val mid = str(m, "id")
            val name = str(m, "name")
            if (!poster.startsWith("http") || mid.isEmpty() || name.isEmpty()) continue
            out.add(
                Meta(
                    id = mid,
                    type = str(m, "type").ifEmpty { type },
                    name = name,
                    poster = poster,
                    posterShape = str(m, "posterShape").ifEmpty { "poster" },
                    background = str(m, "background"),
                    description = str(m, "description")
                )
            )
        }
        return out
    }
}
