package com.nuviorows

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.BaseColumns
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat

data class RefreshResult(val newChannelIds: List<Long>, val message: String)

object RefreshEngine {

    @Synchronized
    fun refresh(context: Context): RefreshResult {
        val ctx = context.applicationContext
        val store = Store(ctx)
        val resolver = ctx.contentResolver
        val addons = store.loadAddons()
        val channelIds = store.loadChannelIds()

        val wanted = LinkedHashMap<String, Pair<AddonCfg, CatalogCfg>>()
        for (a in addons) {
            for (c in a.catalogs) {
                if (c.enabled) wanted["${a.baseUrl}|${c.key}"] = Pair(a, c)
            }
        }

        // Rimuovo le righe che non sono più selezionate
        val iter = channelIds.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!wanted.containsKey(e.key)) {
                try {
                    resolver.delete(TvContractCompat.buildChannelUri(e.value), null, null)
                } catch (ex: Exception) {
                    // ignora
                }
                iter.remove()
            }
        }

        val newIds = mutableListOf<Long>()
        var ok = 0
        val errors = mutableListOf<String>()

        for ((key, pair) in wanted) {
            val addon = pair.first
            val cat = pair.second
            try {
                var channelId = channelIds[key] ?: -1L
                if (channelId < 0 || !channelExists(ctx, channelId)) {
                    channelId = createChannel(ctx, store, displayName(addon, cat))
                    if (channelId < 0) throw IllegalStateException("canale non creato (permessi TV?)")
                    channelIds[key] = channelId
                    newIds.add(channelId)
                }
                val metas = Stremio.fetchCatalog(addon.baseUrl, cat.type, cat.id, store.maxItems)
                replacePrograms(ctx, store, channelId, metas)
                ok++
            } catch (ex: Exception) {
                errors.add("${cat.name}: ${ex.message}")
            }
        }

        store.saveChannelIds(channelIds)

        val msg = when {
            wanted.isEmpty() -> "Nessun catalogo selezionato: righe rimosse dalla home."
            errors.isEmpty() -> "Aggiornate $ok righe."
            else -> "Aggiornate $ok righe, errori: " + errors.joinToString("; ")
        }
        return RefreshResult(newIds, msg)
    }

    private fun typeLabel(type: String): String = when (type) {
        "movie" -> "Film"
        "series" -> "Serie"
        else -> type
    }

    private fun displayName(addon: AddonCfg, cat: CatalogCfg): String =
        "${cat.name} · ${typeLabel(cat.type)} · ${addon.name}"

    private fun channelExists(ctx: Context, id: Long): Boolean {
        return try {
            ctx.contentResolver.query(
                TvContractCompat.buildChannelUri(id),
                arrayOf(BaseColumns._ID),
                null, null, null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun createChannel(ctx: Context, store: Store, name: String): Long {
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(name)
            .setAppLinkIntent(launchIntent(ctx, store))
            .build()
        val uri = ctx.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            channel.toContentValues()
        ) ?: return -1L
        val id = ContentUris.parseId(uri)
        try {
            ChannelLogoUtils.storeChannelLogo(ctx, id, logoBitmap())
        } catch (e: Exception) {
            // il logo è opzionale
        }
        return id
    }

    private fun replacePrograms(ctx: Context, store: Store, channelId: Long, metas: List<Meta>) {
        val resolver = ctx.contentResolver
        resolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        for (m in metas) {
            val type = when (m.type) {
                "movie" -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                "series" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                else -> TvContractCompat.PreviewPrograms.TYPE_CLIP
            }
            val ratio = when (m.posterShape) {
                "landscape" -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                "square" -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
                else -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            }
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(type)
                .setTitle(m.name)
                .setPosterArtUri(Uri.parse(m.poster))
                .setPosterArtAspectRatio(ratio)
                .setIntent(programIntent(ctx, store, m))
                .setInternalProviderId(m.id)
            if (m.description.isNotEmpty()) builder.setDescription(m.description.take(300))
            resolver.insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                builder.build().toContentValues()
            )
        }
    }

    private fun launchIntent(ctx: Context, store: Store): Intent {
        val pkg = store.appPackage
        if (pkg.isNotEmpty()) {
            val pm = ctx.packageManager
            val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return i
            }
        }
        return Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun programIntent(ctx: Context, store: Store, m: Meta): Intent {
        val template = store.deepLink
        if (template.isNotEmpty()) {
            val uriStr = template
                .replace("{type}", Uri.encode(m.type))
                .replace("{id}", Uri.encode(m.id, ":"))
                .replace("{name}", Uri.encode(m.name))
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            if (store.appPackage.isNotEmpty()) i.setPackage(store.appPackage)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return i
        }
        return launchIntent(ctx, store)
    }

    private fun logoBitmap(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF7C4DFF.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = Color.WHITE
        p.textSize = 56f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText("N", size / 2f, size / 2f + 20f, p)
        return bmp
    }
}
