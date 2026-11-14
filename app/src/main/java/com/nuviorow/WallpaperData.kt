package com.nuviorows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Scarica e decodifica immagini (ridimensionate in lettura per non pesare sulla memoria). */
object Imgs {
    fun load(url: String, targetW: Int): Bitmap? {
        return try {
            val out = ByteArrayOutputStream()
            Net.stream(url).use { input ->
                val buf = ByteArray(16384)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 8_000_000) throw IOException("immagine troppo grande")
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }
}

/** Elenco delle card pubblicate da Nuvio Rows: serve a ritrovare trama e immagini dal titolo. */
object CardIndex {

    data class Entry(
        val name: String,
        val id: String,
        val type: String,
        val poster: String,
        val background: String,
        val description: String,
        val icon: String
    )

    private const val FILE_NAME = "cards.json"

    @Volatile
    private var cache: List<Entry> = emptyList()

    @Volatile
    private var cacheStamp = -1L

    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("n", e.name)
            o.put("i", e.id)
            o.put("t", e.type)
            o.put("p", e.poster)
            o.put("b", e.background)
            o.put("d", e.description)
            o.put("c", e.icon)
            arr.put(o)
        }
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        val dest = File(ctx.filesDir, FILE_NAME)
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        cacheStamp = -1L
    }

    private fun entries(ctx: Context): List<Entry> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()
        val stamp = f.lastModified()
        if (stamp == cacheStamp) return cache
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        o.optString("n"), o.optString("i"), o.optString("t"),
                        o.optString("p"), o.optString("b"), o.optString("d"), o.optString("c")
                    )
                )
            }
        } catch (e: Exception) {
            // file corrotto: lista vuota
        }
        cache = out
        cacheStamp = stamp
        return out
    }

    fun find(ctx: Context, icon: String?, title: String): Entry? {
        val list = entries(ctx)
        if (list.isEmpty()) return null
        if (!icon.isNullOrEmpty()) {
            val byIcon = list.firstOrNull { it.icon == icon }
            if (byIcon != null) return byIcon
        }
        val n = norm(title)
        if (n.isEmpty()) return null
        return list.firstOrNull { norm(it.name) == n }
    }
}

/** Titolo, trama e immagini in italiano da TMDB (serve una chiave API v3 gratuita). */
object Tmdb {

    data class Info(
        val kind: String,
        val id: Int,
        val title: String,
        val overview: String,
        val year: String,
        val genres: List<String>,
        val runtime: Int,
        val rating: Double,
        val backdrop: String,
        val poster: String
    )

    private const val BASE = "https://api.themoviedb.org/3"
    private val YEAR_AT_END = Regex("\\s*\\((\\d{4})\\)\\s*$")

    fun cleanTitle(raw: String): String = raw.replace(YEAR_AT_END, "").trim()

    private fun yearOf(raw: String): String = YEAR_AT_END.find(raw)?.groupValues?.get(1) ?: ""

    private fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        return o.optString(key, "")
    }

    private fun getJson(path: String, key: String, params: String): JSONObject =
        JSONObject(Net.get("$BASE$path?api_key=$key&$params"))

    fun lookup(ctx: Context, key: String, imdbId: String?, rawTitle: String, kindHint: String?): Info? {
        val sp = ctx.getSharedPreferences("tmdbcache", Context.MODE_PRIVATE)
        val title = cleanTitle(rawTitle)
        val year = yearOf(rawTitle)
        val useImdb = !imdbId.isNullOrEmpty() && imdbId.startsWith("tt")
        val cacheKey = if (useImdb) "i:$imdbId" else "t:${kindHint ?: "any"}:${CardIndex.norm(title)}:$year"

        val cached = sp.getString(cacheKey, null)
        if (cached != null) {
            val parsed = fromJson(cached)
            if (parsed != null) return parsed
        }

        var found: Pair<String, Int>? = null
        if (useImdb && imdbId != null) found = findImdb(key, imdbId)
        if (found == null) found = search(key, title, year, kindHint)
        if (found == null) return null

        val info = details(key, found.first, found.second) ?: return null
        sp.edit().putString(cacheKey, toJson(info)).apply()
        return info
    }

    private fun findImdb(key: String, imdb: String): Pair<String, Int>? {
        val j = getJson("/find/$imdb", key, "external_source=imdb_id&language=it-IT")
        val m = j.optJSONArray("movie_results")
        if (m != null && m.length() > 0) return Pair("movie", m.getJSONObject(0).getInt("id"))
        val t = j.optJSONArray("tv_results")
        if (t != null && t.length() > 0) return Pair("tv", t.getJSONObject(0).getInt("id"))
        return null
    }

    private fun search(key: String, title: String, year: String, kindHint: String?): Pair<String, Int>? {
        if (title.isEmpty()) return null
        val q = Uri.encode(title)
        val kinds = when (kindHint) {
            "movie" -> listOf("movie")
            "tv" -> listOf("tv")
            else -> listOf("movie", "tv")
        }
        for (k in kinds) {
            val yearParam = if (year.isNotEmpty()) {
                if (k == "movie") "&year=$year" else "&first_air_date_year=$year"
            } else {
                ""
            }
            val j = getJson("/search/$k", key, "query=$q&language=it-IT&include_adult=false$yearParam")
            val arr = j.optJSONArray("results")
            if (arr != null && arr.length() > 0) return Pair(k, arr.getJSONObject(0).getInt("id"))
        }
        return null
    }

    private fun details(key: String, kind: String, id: Int): Info? {
        val j = getJson("/$kind/$id", key, "language=it-IT")
        var overview = str(j, "overview")
        if (overview.isBlank()) {
            try {
                overview = str(getJson("/$kind/$id", key, "language=en-US"), "overview")
            } catch (e: Exception) {
                // resta vuota
            }
        }
        val title = if (kind == "movie") str(j, "title") else str(j, "name")
        val date = if (kind == "movie") str(j, "release_date") else str(j, "first_air_date")
        val genres = ArrayList<String>()
        val ga = j.optJSONArray("genres")
        if (ga != null) {
            for (i in 0 until ga.length()) {
                val n = str(ga.getJSONObject(i), "name")
                if (n.isNotEmpty()) genres.add(n)
            }
        }
        val runtime = if (kind == "movie") {
            j.optInt("runtime", 0)
        } else {
            j.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0
        }
        return Info(
            kind = kind,
            id = id,
            title = title,
            overview = overview,
            year = if (date.length >= 4) date.substring(0, 4) else "",
            genres = genres,
            runtime = runtime,
            rating = j.optDouble("vote_average", 0.0),
            backdrop = str(j, "backdrop_path"),
            poster = str(j, "poster_path")
        )
    }

    private fun toJson(i: Info): String {
        val o = JSONObject()
        o.put("kind", i.kind)
        o.put("id", i.id)
        o.put("title", i.title)
        o.put("overview", i.overview)
        o.put("year", i.year)
        o.put("genres", JSONArray(i.genres))
        o.put("runtime", i.runtime)
        o.put("rating", i.rating)
        o.put("backdrop", i.backdrop)
        o.put("poster", i.poster)
        return o.toString()
    }

    private fun fromJson(s: String): Info? {
        return try {
            val o = JSONObject(s)
            val genres = ArrayList<String>()
            val ga = o.optJSONArray("genres")
            if (ga != null) for (i in 0 until ga.length()) genres.add(ga.getString(i))
            Info(
                o.getString("kind"), o.getInt("id"), o.optString("title"), o.optString("overview"),
                o.optString("year"), genres, o.optInt("runtime"), o.optDouble("rating"),
                o.optString("backdrop"), o.optString("poster")
            )
        } catch (e: Exception) {
            null
        }
    }
}
