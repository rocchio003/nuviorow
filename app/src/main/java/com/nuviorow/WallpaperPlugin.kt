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
import android.os.Process
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
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

/** Serve a Projectivy i file generati (content://com.nuviorows.wallpapers/nome.png o nome.jpg). */
class WallpaperFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String {
        // Le card delle app sono PNG (arte piatta), film e serie restano JPEG.
        val name = uri.lastPathSegment.orEmpty()
        return if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
    }

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
    // v6: lo sfondo dei titoli senza backdrop usa quello ricavato da TMDB in fase di refresh.
    // Il numero fa parte della chiave: senza il cambio resterebbero gli sfondi vecchi verticali.
    private const val RENDER_VERSION = "v6"
    // ~1 MB a PNG e ~250 KB a JPEG: 60 file stanno sotto i 60 MB, su una TV con 800 MB liberi.
    private const val MAX_FILES = 60

    private const val OK = 1
    private const val FAIL = 0
    private const val SKIPPED = -1

    // Un solo thread, a bassa priorità, per non rubare CPU all'interfaccia della TV.
    private val pool: ExecutorService = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (e: Exception) {
                // ignora
            }
            r.run()
        }
    })
    private val inflight = ConcurrentHashMap<String, FutureTask<Int>>()
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

    // Le card delle app (chiave "a_") sono arte piatta: in PNG non perdono nulla.
    // Film e serie sono foto: lì il JPEG 92 è indistinguibile e pesa un quarto.
    private fun extFor(key: String): String = if (key.startsWith("a_")) "png" else "jpg"

    private fun wallpaper(f: File, title: String): Wallpaper {
        val uri = Uri.parse("content://$AUTHORITY/${f.name}")
        return Wallpaper(
            uri = uri.toString(),
            type = WallpaperType.IMAGE,
            displayMode = WallpaperDisplayMode.CROP,
            title = title,
            source = "Nuvio Rows"
        )
    }

    private fun neutral(ctx: Context): List<Wallpaper> {
        val f = File(dir(ctx), "neutral_$RENDER_VERSION.png")
        if (!f.exists()) {
            val bmp = WallpaperRenderer.neutral()
            try {
                WallpaperRenderer.save(bmp, f)
            } finally {
                WallpaperRenderer.release(bmp)
            }
        }
        return if (f.exists()) listOf(wallpaper(f, "Nuvio Rows")) else emptyList()
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

    /**
     * Se il file c'è già lo restituisce subito. Altrimenti lo fa disegnare in background
     * dopo un breve debounce e, se non è pronto entro [waitMs], lascia lo sfondo attuale:
     * a disegno finito avvisa Projectivy, che richiede lo sfondo e trova il file pronto.
     *
     * Le card delle app si disegnano in locale in pochi ms, quindi lì si aspetta di più e
     * l'immagine arriva al primo colpo; per i film il disegno scarica da TMDB, quindi si
     * aspetta poco e si lascia fare al secondo giro.
     */
    private fun serve(
        ctx: Context,
        key: String,
        title: String,
        debounceMs: Long,
        waitMs: Long,
        render: (File) -> Int
    ): List<Wallpaper> {
        latest = key
        val f = File(dir(ctx), "$key." + extFor(key))
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            return listOf(wallpaper(f, title))
        }
        if (failed.contains(key)) return neutral(ctx)

        val task = FutureTask<Int>(Callable {
            try {
                Thread.sleep(debounceMs)
            } catch (e: InterruptedException) {
                // ignora
            }
            if (latest != key) SKIPPED else render(f)
        })
        val existing = inflight.putIfAbsent(key, task)
        val fut: Future<Int>
        if (existing == null) {
            fut = task
            pool.execute {
                task.run()
                val r = try {
                    task.get()
                } catch (e: Exception) {
                    FAIL
                }
                if (r == FAIL) failed.add(key)
                inflight.remove(key)
                if (r != SKIPPED && latest == key) notifyUpdate(ctx)
            }
        } else {
            fut = existing
        }

        val r = try {
            fut.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            return emptyList()
        } catch (e: Exception) {
            FAIL
        }
        if (r == OK && f.exists()) return listOf(wallpaper(f, title))
        if (r == FAIL) {
            failed.add(key)
            return neutral(ctx)
        }
        return emptyList()
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
        // 900 ms e non 320: l'encoding PNG di un 1920x1080 richiede qualche centinaio di ms sul
        // SoC della TV, quindi con 320 ms la prima richiesta andava sempre in timeout e serviva
        // un secondo giro (era questo a far sembrare più lenta la comparsa delle card).
        return serve(ctx, key, label, 60L, 900L) { out ->
            val bmp = WallpaperRenderer.composeApp(ctx, pkg, label, dark)
            val ok = try {
                WallpaperRenderer.save(bmp, out)
            } finally {
                WallpaperRenderer.release(bmp)
            }
            if (ok) {
                prune(ctx)
                OK
            } else {
                FAIL
            }
        }
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
        return serve(ctx, key, title, 200L, 150L) { out -> renderProgram(ctx, ev, key, out) }
    }

    private fun renderProgram(ctx: Context, ev: Event.ProgramCardFocused, key: String, out: File): Int {
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
        if (latest != key) return SKIPPED

        val title = info?.title?.takeIf { it.isNotBlank() } ?: entry?.name ?: Tmdb.cleanTitle(rawTitle)
        val plot = info?.overview?.takeIf { it.isNotBlank() } ?: entry?.description.orEmpty()
        val meta = if (info != null) WallpaperRenderer.formatMeta(info) else ""

        val backUrl: String? = info?.backdrop?.takeIf { it.isNotEmpty() }
            ?.let { "https://image.tmdb.org/t/p/w780$it" }
            ?: entry?.background?.takeIf { it.startsWith("http") }
        val posterUrl: String? = info?.poster?.takeIf { it.isNotEmpty() }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
            ?: entry?.poster?.takeIf { it.startsWith("http") }
            ?: ev.iconUri?.takeIf { it.startsWith("http") }

        if (backUrl == null && posterUrl == null && plot.isEmpty()) return FAIL

        val back = if (backUrl != null) Imgs.load(backUrl, 960) else null
        if (latest != key) return SKIPPED
        val poster = if (back == null && posterUrl != null) Imgs.load(posterUrl, 600) else null
        if (back == null && poster == null && plot.isEmpty()) return FAIL
        if (latest != key) return SKIPPED

        // back e poster arrivano dalla cache in memoria di Imgs: non vanno riciclati
        val composed = WallpaperRenderer.composeInfo(title, meta, plot, back, poster)
        val ok = try {
            WallpaperRenderer.save(composed, out)
        } finally {
            WallpaperRenderer.release(composed)
        }
        if (ok) prune(ctx)
        return if (ok) OK else FAIL
    }
}
