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

    private const val GUIDE_KEY = "__guide__"

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

        val guideWanted = store.m3uUrl.isNotEmpty()

        // Rimuovo le righe che non sono più selezionate
        val iter = channelIds.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!wanted.containsKey(e.key) && !(e.key == GUIDE_KEY && guideWanted)) {
                try {
                    resolver.delete(TvContractCompat.buildChannelUri(e.value), null, null)
                } catch (ex: Exception) {
                    // ignora
                }
                iter.remove()
            }
        }

        val newIds = mutableListOf<Long>()
        val index = ArrayList<CardIndex.Entry>()
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
                replacePrograms(ctx, store, channelId, metas, index)
                ok++
            } catch (ex: Exception) {
                errors.add("${cat.name}: ${ex.message}")
            }
        }

        if (guideWanted) {
            try {
                ensureGuideChannel(ctx, channelIds, newIds)
            } catch (ex: Exception) {
                errors.add("Guida TV: ${ex.message}")
            }
        }

        store.saveChannelIds(channelIds)
        if (index.isNotEmpty()) {
            try {
                CardIndex.save(ctx, index)
            } catch (ex: Exception) {
                // l'indice serve solo allo sfondo dinamico
            }
        }

        val msg = when {
            wanted.isEmpty() -> "Nessun catalogo selezionato: righe rimosse dalla home."
            errors.isEmpty() -> "Aggiornate $ok righe."
            else -> "Aggiornate $ok righe, errori: " + errors.joinToString("; ")
        }
        return RefreshResult(newIds, msg)
    }

    // Riga "Guida TV": una sola card che apre la finestra della guida.
    private fun ensureGuideChannel(ctx: Context, channelIds: MutableMap<String, Long>, newIds: MutableList<Long>) {
        val store = Store(ctx)
        var channelId = channelIds[GUIDE_KEY] ?: -1L
        if (channelId < 0 || !channelExists(ctx, channelId)) {
            channelId = createChannel(ctx, store, "Guida TV")
            if (channelId < 0) throw IllegalStateException("canale non creato")
            channelIds[GUIDE_KEY] = channelId
            newIds.add(channelId)
        }
        val resolver = ctx.contentResolver
        resolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        val intent = Intent(ctx, GuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val program = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle("Guida TV")
            .setDescription("Apri la guida dei canali")
            .setPosterArtUri(Uri.parse("android.resource://" + ctx.packageName + "/drawable/guide_card"))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntent(intent)
            .setInternalProviderId("guide")
            .build()
        resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
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

    private fun replacePrograms(
        ctx: Context,
        store: Store,
        channelId: Long,
        metas: List<Meta>,
        index: MutableList<CardIndex.Entry>
    ) {
        val resolver = ctx.contentResolver
        resolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
        val landscape = store.cardStyle == "landscape"
        for (m in metas) {
            val type = when (m.type) {
                "movie" -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                "series" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                else -> TvContractCompat.PreviewPrograms.TYPE_CLIP
            }
            // Stile orizzontale: uso l'immagine "background" dell'addon; se manca, ripiego sulla locandina.
            val useBackground = landscape && m.background.startsWith("http")
            val image = if (useBackground) m.background else m.poster
            val ratio = if (useBackground) {
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            } else {
                when (m.posterShape) {
                    "landscape" -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    "square" -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
                    else -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                }
            }
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(type)
                .setTitle(m.name)
                .setPosterArtUri(Uri.parse(image))
                .setPosterArtAspectRatio(ratio)
                .setIntent(programIntent(ctx, store, m))
                .setInternalProviderId(m.id)
            index.add(CardIndex.Entry(m.name, m.id, m.type, m.poster, m.background, m.description, image))
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

    // La card apre il trampolino LaunchActivity, che legge le impostazioni al momento del tocco.
    private fun programIntent(ctx: Context, store: Store, m: Meta): Intent {
        val i = Intent(ctx, LaunchActivity::class.java)
        i.action = "com.nuviorows.OPEN"
        i.data = Uri.parse("nuviorows://open/" + Uri.encode(m.type) + "/" + Uri.encode(m.id, ":"))
        i.putExtra("type", m.type)
        i.putExtra("id", m.id)
        i.putExtra("name", m.name)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
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
