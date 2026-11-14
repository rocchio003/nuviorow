package com.nuviorows

import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.tvprovider.media.tv.TvContractCompat
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Servizio che Projectivy collega per chiedere lo sfondo a ogni cambio di card. */
class NuvioWallpaperService : Service() {

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> =
            WallpaperEngine.handle(applicationContext, event)

        override fun getPreferences(): String = ""

        override fun setPreferences(params: String?) {
            // nessuna preferenza gestita da Projectivy
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/** Serve a Projectivy i file JPEG generati (content://com.nuviorows.wallpapers/nome.jpg). */
class WallpaperFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!Regex("[A-Za-z0-9_.-]+").matches(name)) throw FileNotFoundException()
        val f = File(WallpaperEngine.dir(ctx), name)
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

object WallpaperEngine {

    const val AUTHORITY = "com.nuviorows.wallpapers"
    const val PROVIDER_UUID = "88a263c3-01fa-47bf-ab07-f4e3566d9157"
    private const val PROJECTIVY_PKG = "com.spocky.projengmenu"
    private const val RENDER_VERSION = "v1"
    private const val MAX_FILES = 150

    private val pool = Executors.newFixedThreadPool(2)
    private val inflight = ConcurrentHashMap<String, FutureTask<Boolean>>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var latest = ""

    fun dir(ctx: Context): File {
        val d = File(ctx.cacheDir, "wall")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun handle(ctx: Context, event: Event?): List<Wallpaper> {
        return try {
            when (event) {
                is Event.CardFocused -> forApp(ctx, event)
                is Event.ProgramCardFocused -> forProgram(ctx, event)
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun wallpaper(ctx: Context, f: File, title: String): Wallpaper {
        val uri = Uri.parse("content://$AUTHORITY/${f.name}")
        try {
            ctx.grantUriPermission(PROJECTIVY_PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // il provider è comunque esportato
        }
        f.setLastModified(System.currentTimeMillis())
        return Wallpaper(
            uri = uri.toString(),
            type = WallpaperType.IMAGE,
            displayMode = WallpaperDisplayMode.CROP,
            title = title,
            source = "Nuvio Rows"
        )
    }

    private fun neutral(ctx: Context): List<Wallpaper> {
        val f = File(dir(ctx), "neutral_$RENDER_VERSION.jpg")
        if (!f.exists()) WallpaperRenderer.save(WallpaperRenderer.neutral(), f)
        return if (f.exists()) listOf(wallpaper(ctx, f, "Nuvio Rows")) else emptyList()
    }

    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles() ?: return
        if (files.size <= MAX_FILES) return
        val sorted = files.sortedBy { it.lastModified() }
        for (i in 0 until sorted.size - MAX_FILES) sorted[i].delete()
    }

    private fun notifyUpdate(ctx: Context) {
        try {
            val i = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED)
            i.setPackage(PROJECTIVY_PKG)
            i.putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, PROVIDER_UUID)
            i.putExtra(
                WallpaperProviderContract.EXTRA_UPDATE_REASON,
                WallpaperProviderContract.UpdateReason.DATA_CHANGED
            )
            ctx.sendBroadcast(i)
        } catch (e: Exception) {
            // ignora
        }
    }

    // ---------- card di app ----------

    private fun forApp(ctx: Context, ev: Event.CardFocused): List<Wallpaper> {
        val pkg = ev.packageName
        if (pkg.isNullOrBlank() || pkg == ctx.packageName) return emptyList()
        val label = if (!ev.title.isNullOrBlank()) ev.title else appLabel(ctx, pkg)
        return appWallpaper(ctx, pkg, label.orEmpty(), ev.darkColor)
    }

    private fun appLabel(ctx: Context, pkg: String): String {
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun appWallpaper(ctx: Context, pkg: String, label: String, dark: Int): List<Wallpaper> {
        val key = "a_" + hash("$RENDER_VERSION|$pkg|$label|$dark")
        latest = key
        val f = File(dir(ctx), "$key.jpg")
        if (!f.exists()) {
            val ok = WallpaperRenderer.save(WallpaperRenderer.composeApp(ctx, pkg, label, dark), f)
            if (!ok) return neutral(ctx)
            prune(ctx)
        }
        return listOf(wallpaper(ctx, f, label))
    }

    // ---------- card di film e serie ----------

    private fun forProgram(ctx: Context, ev: Event.ProgramCardFocused): List<Wallpaper> {
        val title = ev.title?.trim().orEmpty()
        if (title.isEmpty()) return neutral(ctx)
        val type = ev.type ?: 0
        if (type >= 100) return emptyList() // card "Now Playing": non la gestiamo
        if (title == "Guida TV" || ev.iconUri?.startsWith("android.resource") == true) {
            return appWallpaper(ctx, ctx.packageName, title, 0)
        }

        val store = Store(ctx)
        val key = "p_" + hash("$RENDER_VERSION|$title|${ev.iconUri}|${store.tmdbKey.isNotEmpty()}")
        latest = key
        val f = File(dir(ctx), "$key.jpg")
        if (f.exists()) return listOf(wallpaper(ctx, f, title))
        if (failed.contains(key)) return neutral(ctx)

        val task = FutureTask<Boolean>(Callable { renderProgram(ctx, ev, f) })
        val existing = inflight.putIfAbsent(key, task)
        val fut: Future<Boolean>
        if (existing == null) {
            fut = task
            pool.execute {
                task.run()
                val ok = try {
                    task.get()
                } catch (e: Exception) {
                    false
                }
                if (!ok) failed.add(key)
                inflight.remove(key)
                if (latest == key) notifyUpdate(ctx)
            }
        } else {
            fut = existing
        }

        // Se l'immagine non è pronta in fretta, tengo lo sfondo attuale: appena pronta avviso Projectivy.
        val ok = try {
            fut.get(400, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            return emptyList()
        } catch (e: Exception) {
            false
        }
        if (ok && f.exists()) return listOf(wallpaper(ctx, f, title))
        failed.add(key)
        return neutral(ctx)
    }

    private fun renderProgram(ctx: Context, ev: Event.ProgramCardFocused, out: File): Boolean {
        val store = Store(ctx)
        val rawTitle = ev.title?.trim().orEmpty()
        val entry = CardIndex.find(ctx, ev.iconUri, rawTitle)

        val kindHint: String? = when {
            entry != null && entry.type == "series" -> "tv"
            entry != null && entry.type == "movie" -> "movie"
            ev.type == TvContractCompat.PreviewPrograms.TYPE_MOVIE -> "movie"
            ev.type == TvContractCompat.PreviewPrograms.TYPE_TV_SERIES -> "tv"
            ev.type == TvContractCompat.PreviewPrograms.TYPE_TV_SEASON -> "tv"
            ev.type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE -> "tv"
            else -> null
        }

        var info: Tmdb.Info? = null
        if (store.tmdbKey.isNotEmpty()) {
            val imdb = entry?.id?.takeIf { it.startsWith("tt") }
            info = try {
                Tmdb.lookup(ctx, store.tmdbKey, imdb, entry?.name ?: rawTitle, kindHint)
            } catch (e: Exception) {
                null
            }
        }

        val title = info?.title?.takeIf { it.isNotBlank() } ?: entry?.name ?: Tmdb.cleanTitle(rawTitle)
        val plot = info?.overview?.takeIf { it.isNotBlank() } ?: entry?.description.orEmpty()
        val meta = if (info != null) WallpaperRenderer.formatMeta(info) else ""

        val backUrl: String? = info?.backdrop?.takeIf { it.isNotEmpty() }
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
            ?: entry?.background?.takeIf { it.startsWith("http") }
        val posterUrl: String? = info?.poster?.takeIf { it.isNotEmpty() }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
            ?: entry?.poster?.takeIf { it.startsWith("http") }
            ?: ev.iconUri?.takeIf { it.startsWith("http") }

        if (backUrl == null && posterUrl == null && plot.isEmpty()) return false

        val back = if (backUrl != null) Imgs.load(backUrl, 1280) else null
        val poster = if (back == null && posterUrl != null) Imgs.load(posterUrl, 600) else null
        if (back == null && poster == null && plot.isEmpty()) return false

        val ok = WallpaperRenderer.save(WallpaperRenderer.composeInfo(title, meta, plot, back, poster), out)
        if (ok) prune(ctx)
        return ok
    }
}
