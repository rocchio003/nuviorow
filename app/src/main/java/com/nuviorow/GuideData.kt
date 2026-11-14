package com.nuviorows

import android.content.Context
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.ZoneOffset

data class GProgram(val start: Long, val stop: Long, val title: String, val desc: String)

data class GChannel(
    val name: String,
    val logo: String,
    val group: String,
    val url: String,
    val tvgId: String,
    val programs: List<GProgram>
)

object GuideData {

    private const val FILE_NAME = "guide.json"
    private const val BEFORE_MS = 3L * 3600_000L
    private const val AFTER_MS = 30L * 3600_000L
    private const val MAX_CHANNELS = 1500
    private const val MAX_M3U_ENTRIES = 20000

    private class M3uChannel(
        val name: String,
        val logo: String,
        val group: String,
        val url: String,
        val tvgId: String,
        val tvgName: String
    )

    private class M3uResult(val channels: List<M3uChannel>, val epgUrl: String)

    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun updatedAt(ctx: Context): Long {
        val f = file(ctx)
        return if (f.exists()) f.lastModified() else 0L
    }

    // Scarica M3U ed EPG, e salva la guida in una cache locale. Da chiamare in background.
    @Synchronized
    fun refresh(ctx: Context): String {
        val store = Store(ctx.applicationContext)
        val m3uUrl = store.m3uUrl
        if (m3uUrl.isEmpty()) return "Link M3U non impostato."

        val m3u = try {
            parseM3u(m3uUrl)
        } catch (e: Exception) {
            return "Errore M3U: ${e.message}"
        }
        if (m3u.channels.isEmpty()) return "Nessun canale trovato nell'M3U."

        val epgUrl = store.epgUrl.ifEmpty { m3u.epgUrl }
        var epgError = ""
        var programs: Array<MutableList<GProgram>?> = arrayOfNulls(m3u.channels.size)
        if (epgUrl.isNotEmpty()) {
            try {
                programs = parseEpg(epgUrl, m3u.channels)
            } catch (e: Exception) {
                epgError = " Errore EPG: ${e.message}"
            }
        } else {
            epgError = " Nessun link EPG: imposta quello XMLTV."
        }

        val withEpg = ArrayList<GChannel>()
        val all = ArrayList<GChannel>()
        for (i in m3u.channels.indices) {
            val c = m3u.channels[i]
            val p = programs[i]
            val gc = GChannel(c.name, c.logo, c.group, c.url, c.tvgId, p ?: emptyList())
            if (p != null && p.isNotEmpty()) withEpg.add(gc)
            if (all.size < MAX_CHANNELS) all.add(gc)
        }
        val finalList: List<GChannel> = if (withEpg.isNotEmpty()) withEpg.take(MAX_CHANNELS) else all

        save(ctx, finalList)

        return "Guida aggiornata: ${finalList.size} canali." +
            (if (withEpg.isEmpty()) " Nessun programma trovato." else "") + epgError
    }

    fun load(ctx: Context): List<GChannel>? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            JsonReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8)).use { r ->
                val out = ArrayList<GChannel>()
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "channels") {
                        r.beginArray()
                        while (r.hasNext()) out.add(readChannel(r))
                        r.endArray()
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readChannel(r: JsonReader): GChannel {
        var n = ""
        var l = ""
        var g = ""
        var u = ""
        var i = ""
        val p = ArrayList<GProgram>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "n" -> { n = r.nextString() }
                "l" -> { l = r.nextString() }
                "g" -> { g = r.nextString() }
                "u" -> { u = r.nextString() }
                "i" -> { i = r.nextString() }
                "p" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        val s = r.nextLong()
                        val e = r.nextLong()
                        val t = r.nextString()
                        val d = r.nextString()
                        r.endArray()
                        p.add(GProgram(s, e, t, d))
                    }
                    r.endArray()
                }
                else -> { r.skipValue() }
            }
        }
        r.endObject()
        return GChannel(n, l, g, u, i, p)
    }

    private fun save(ctx: Context, list: List<GChannel>) {
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        JsonWriter(OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("updated").value(System.currentTimeMillis())
            w.name("channels")
            w.beginArray()
            for (c in list) {
                w.beginObject()
                w.name("n").value(c.name)
                w.name("l").value(c.logo)
                w.name("g").value(c.group)
                w.name("u").value(c.url)
                w.name("i").value(c.tvgId)
                w.name("p")
                w.beginArray()
                for (p in c.programs) {
                    w.beginArray()
                    w.value(p.start)
                    w.value(p.stop)
                    w.value(p.title)
                    w.value(p.desc)
                    w.endArray()
                }
                w.endArray()
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        val dest = file(ctx)
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    // ---------- M3U ----------

    private fun attrs(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (r in ATTR.findAll(line)) {
            m[r.groupValues[1].lowercase()] = r.groupValues[2]
        }
        return m
    }

    private fun parseM3u(url: String): M3uResult {
        val out = ArrayList<M3uChannel>()
        var epg = ""
        Net.stream(url).bufferedReader().use { br ->
            var pending: Map<String, String>? = null
            var pendingName = ""
            while (true) {
                val line = br.readLine() ?: break
                val t = line.trim()
                if (t.isEmpty()) continue
                if (t.startsWith("#EXTM3U")) {
                    val a = attrs(t)
                    epg = (a["url-tvg"] ?: a["x-tvg-url"] ?: "").substringBefore(",").trim()
                    continue
                }
                if (t.startsWith("#EXTINF")) {
                    pending = attrs(t)
                    pendingName = ATTR.replace(t, "").substringAfterLast(",").trim()
                    continue
                }
                if (t.startsWith("#")) continue
                val a = pending
                if (a != null) {
                    val name = pendingName.ifEmpty { a["tvg-name"] ?: "" }
                    out.add(
                        M3uChannel(
                            name = name,
                            logo = a["tvg-logo"] ?: "",
                            group = a["group-title"] ?: "",
                            url = t,
                            tvgId = a["tvg-id"] ?: "",
                            tvgName = a["tvg-name"] ?: ""
                        )
                    )
                    pending = null
                    if (out.size >= MAX_M3U_ENTRIES) break
                }
            }
        }
        return M3uResult(out, epg)
    }

    // ---------- XMLTV ----------

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun parseTime(s: String?): Long {
        if (s == null || s.length < 14) return 0L
        return try {
            val ldt = LocalDateTime.of(
                s.substring(0, 4).toInt(),
                s.substring(4, 6).toInt(),
                s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(),
                s.substring(10, 12).toInt(),
                s.substring(12, 14).toInt()
            )
            var off = 0
            val rest = s.substring(14).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                off = sign * (rest.substring(1, 3).toInt() * 3600 + rest.substring(3, 5).toInt() * 60)
            }
            (ldt.toEpochSecond(ZoneOffset.UTC) - off) * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseEpg(epgUrl: String, channels: List<M3uChannel>): Array<MutableList<GProgram>?> {
        val result: Array<MutableList<GProgram>?> = arrayOfNulls(channels.size)

        // chiavi per collegare i canali dell'M3U a quelli dell'XMLTV
        val byKey = HashMap<String, MutableList<Int>>()
        fun addKey(k: String, idx: Int) {
            if (k.isEmpty()) return
            byKey.getOrPut(k) { ArrayList() }.add(idx)
        }
        for (i in channels.indices) {
            val c = channels[i]
            if (c.tvgId.isNotEmpty()) addKey("id:" + c.tvgId.lowercase(), i)
            addKey("n:" + norm(c.tvgName), i)
            addKey("n:" + norm(c.name), i)
        }

        val now = System.currentTimeMillis()
        val from = now - BEFORE_MS
        val to = now + AFTER_MS
        val resolved = HashMap<String, MutableSet<Int>>()

        val parser = Xml.newPullParser()
        Net.stream(epgUrl).use { input ->
            parser.setInput(input, null)
            var curChan: String? = null
            var inProg = false
            var targets: List<Int> = emptyList()
            var pStart = 0L
            var pStop = 0L
            var pTitle = ""
            var pDesc = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id")
                            curChan = id
                            if (id != null) {
                                val set = resolved.getOrPut(id) { LinkedHashSet() }
                                val direct = byKey["id:" + id.lowercase()]
                                if (direct != null) set.addAll(direct)
                            }
                        }
                        "display-name" -> {
                            val cid = curChan
                            if (cid != null) {
                                val name = parser.nextText()
                                val byName = byKey["n:" + norm(name)]
                                if (byName != null) {
                                    resolved.getOrPut(cid) { LinkedHashSet() }.addAll(byName)
                                }
                            }
                        }
                        "programme" -> {
                            val cid = parser.getAttributeValue(null, "channel") ?: ""
                            pStart = parseTime(parser.getAttributeValue(null, "start"))
                            pStop = parseTime(parser.getAttributeValue(null, "stop"))
                            val tg: Collection<Int>? = resolved[cid] ?: byKey["id:" + cid.lowercase()]
                            inProg = tg != null && tg.isNotEmpty() && pStop > from && pStart < to && pStart > 0L
                            targets = if (inProg && tg != null) tg.toList() else emptyList()
                            pTitle = ""
                            pDesc = ""
                        }
                        "title" -> {
                            if (inProg && pTitle.isEmpty()) pTitle = parser.nextText().trim()
                        }
                        "desc" -> {
                            if (inProg && pDesc.isEmpty()) pDesc = parser.nextText().trim().take(300)
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "channel" -> { curChan = null }
                        "programme" -> {
                            if (inProg && pTitle.isNotEmpty()) {
                                val prog = GProgram(pStart, pStop, pTitle, pDesc)
                                for (i in targets) {
                                    var list = result[i]
                                    if (list == null) {
                                        list = ArrayList()
                                        result[i] = list
                                    }
                                    list.add(prog)
                                }
                            }
                            inProg = false
                        }
                    }
                }
                event = parser.next()
            }
        }

        // ordino e tolgo i duplicati
        for (i in result.indices) {
            val l = result[i] ?: continue
            l.sortBy { it.start }
            val dedup = ArrayList<GProgram>()
            var last = -1L
            for (p in l) {
                if (p.start != last) {
                    dedup.add(p)
                    last = p.start
                }
            }
            result[i] = dedup
        }
        return result
    }
}
