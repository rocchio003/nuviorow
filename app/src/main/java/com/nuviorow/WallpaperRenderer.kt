package com.nuviorows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Disegna lo sfondo 1920x1080 che Projectivy mette dietro alla home:
 * immagine in alto a destra che sfuma, titolo e trama a sinistra, parte bassa scura (sotto ci sono le righe).
 */
object WallpaperRenderer {

    const val W = 1920
    const val H = 1080

    private val BASE = 0xFF11101C.toInt()
    private const val TEXT_X = 96f
    private const val TEXT_W = 760

    // Buffer riusati: un 1920x1080 nuovo per ogni card a fuoco costava ~8 MB di allocazione
    // e scatenava il GC proprio mentre Projectivy disegnava lo sfondo.
    private val poolLock = Any()
    private val freeBuffers = ArrayList<Bitmap>(2)

    // ARGB_8888 e non RGB_565: in 565 il blu ha 32 livelli e un gradient viola largo 1920 px
    // mostrava bande larghe decine di pixel, oltre a bordi scalettati sui loghi.
    private fun obtain(): Bitmap = synchronized(poolLock) {
        if (freeBuffers.isNotEmpty()) {
            freeBuffers.removeAt(freeBuffers.size - 1)
        } else {
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        }
    }

    /** Da chiamare dopo save(): rimette il buffer in circolo. */
    fun release(bmp: Bitmap) {
        synchronized(poolLock) {
            if (freeBuffers.size < 2 && !bmp.isRecycled) freeBuffers.add(bmp)
        }
    }

    private fun withAlpha(color: Int, a: Int): Int = (a shl 24) or (color and 0x00FFFFFF)

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    /**
     * Bitmap dentro un riquadro con angoli arrotondati. Con Canvas.clipPath i bordi curvi
     * venivano a scalini, perché il ritaglio di Canvas non è antialiasato: qui invece l'arco
     * lo disegna drawRoundRect, che è antialiasato, e la bitmap la campiona il BitmapShader
     * con la stessa scala che avrebbe avuto il drawBitmap.
     */
    private fun drawRounded(c: Canvas, bmp: Bitmap, dst: RectF, radius: Float, paint: Paint) {
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(dst.width() / bmp.width, dst.height() / bmp.height)
        m.postTranslate(dst.left, dst.top)
        shader.setLocalMatrix(m)
        val p = Paint(paint)
        p.shader = shader
        c.drawRoundRect(dst, radius, radius, p)
    }

    private fun drawCover(c: Canvas, bmp: Bitmap, dst: RectF, paint: Paint) {
        val scale = Math.max(dst.width() / bmp.width, dst.height() / bmp.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sx = (bmp.width - sw) / 2f
        val sy = (bmp.height - sh) / 2f
        val src = Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        c.drawBitmap(bmp, src, dst, paint)
    }

    // sfumature: a sinistra (per il testo) e in basso (per le righe del launcher)
    private fun drawFades(c: Canvas, base: Int) {
        val p = Paint()
        p.shader = LinearGradient(
            W * 0.28f, 0f, W * 0.66f, 0f,
            withAlpha(base, 255), withAlpha(base, 0), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W * 0.66f, H * 0.70f, p)

        val q = Paint()
        q.shader = LinearGradient(
            0f, H * 0.36f, 0f, H * 0.66f,
            withAlpha(base, 0), withAlpha(base, 255), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H * 0.66f, q)

        val solid = Paint()
        solid.color = base
        c.drawRect(0f, H * 0.66f, W.toFloat(), H.toFloat(), solid)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.1f)
            .build()

    private fun textPaint(size: Float, color: Int, bold: Boolean): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size
        p.color = color
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
        return p
    }

    /**
     * PNG per le card delle app (arte piatta: logo su tinta unita e gradient): il JPEG lì lascia
     * aloni sui bordi netti e blocchi sul gradient, e su Android il sottocampionamento del colore
     * resta 4:2:0 a qualunque qualità si passi a Bitmap.compress. JPEG 92 per film e serie: sono
     * foto, dove la differenza non si vede e il file pesa un quarto.
     */
    fun save(bmp: Bitmap, out: File): Boolean {
        return try {
            val tmp = File(out.parentFile, out.name + ".tmp")
            val png = out.name.endsWith(".png", ignoreCase = true)
            val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            FileOutputStream(tmp).use { bmp.compress(fmt, if (png) 100 else 92, it) }
            if (out.exists()) out.delete()
            tmp.renameTo(out)
        } catch (e: Exception) {
            false
        }
    }

    fun neutral(): Bitmap {
        val bmp = obtain()
        val c = Canvas(bmp)
        c.drawColor(BASE)
        val p = Paint()
        p.shader = LinearGradient(
            W * 0.1f, H * 0.7f, W.toFloat(), 0f,
            BASE, mix(BASE, 0xFF4B3F8C.toInt(), 0.45f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H * 0.7f, p)
        drawFades(c, BASE)
        return bmp
    }

    /** Film o serie: immagine a destra (sfondo orizzontale oppure locandina), testo a sinistra. */
    fun composeInfo(
        title: String,
        meta: String,
        plot: String,
        backdrop: Bitmap?,
        poster: Bitmap?
    ): Bitmap {
        val bmp = obtain()
        val c = Canvas(bmp)
        c.drawColor(BASE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (backdrop != null) {
            drawCover(c, backdrop, RectF(W * 0.30f, 0f, W.toFloat(), H * 0.68f), paint)
        } else if (poster != null) {
            // sfondo sfocato ricavato dalla locandina + locandina grande a destra
            val small = Bitmap.createScaledBitmap(poster, 32, 48, true)
            val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            blurPaint.alpha = 110
            drawCover(c, small, RectF(W * 0.30f, 0f, W.toFloat(), H * 0.68f), blurPaint)
            small.recycle()
            val ph = H * 0.60f
            val pw = ph * poster.width / poster.height
            val right = W - 150f
            val dst = RectF(right - pw, 60f, right, 60f + ph)
            drawRounded(c, poster, dst, 22f, paint)
        }
        drawFades(c, BASE)

        var y = 170f
        val tp = textPaint(76f, Color.WHITE, true)
        val tl = layout(title, tp, TEXT_W, 2)
        c.save()
        c.translate(TEXT_X, y)
        tl.draw(c)
        c.restore()
        y += tl.height + 18f

        if (meta.isNotEmpty()) {
            val mp = textPaint(30f, 0xFFC9C7D6.toInt(), false)
            val ml = layout(meta, mp, TEXT_W, 1)
            c.save()
            c.translate(TEXT_X, y)
            ml.draw(c)
            c.restore()
            y += ml.height + 26f
        }

        if (plot.isNotEmpty()) {
            val pp = textPaint(33f, 0xFFE6E4F0.toInt(), false)
            val pl = layout(plot, pp, TEXT_W, 6)
            c.save()
            c.translate(TEXT_X, y)
            pl.draw(c)
            c.restore()
        }
        return bmp
    }

    private fun drawableToBitmap(d: Drawable, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(cv)
        return b
    }

    /** App: logo (o banner) ingrandito in alto a destra, nome a sinistra. */
    fun composeApp(ctx: Context, pkg: String, label: String, dark: Int): Bitmap {
        val bmp = obtain()
        val c = Canvas(bmp)
        c.drawColor(BASE)
        // Gradient a tre fermate invece del vecchio BASE mescolato al 50% con un viola spento
        // (#4B3F8C), che lasciava metà schermo quasi nero: viola vivo -> blu elettrico -> base.
        val vivid = if (dark != 0) mix(BASE, dark or 0xFF000000.toInt(), 0.85f) else 0xFF6B4BFF.toInt()
        val blue = mix(vivid, 0xFF2F6BFF.toInt(), 0.55f)
        val bg = Paint()
        bg.shader = LinearGradient(
            W * 0.06f, H * 0.72f, W.toFloat(), 0f,
            intArrayOf(BASE, vivid, blue, mix(BASE, blue, 0.35f)),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H * 0.7f, bg)

        // Alone dietro l'arte: stacca logo e banner dal fondo senza schiarire il lato del testo
        // (drawFades riporta comunque la sinistra verso la base).
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            W * 0.72f, H * 0.22f, 820f,
            intArrayOf(withAlpha(blue, 104), withAlpha(blue, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H * 0.7f, glow)
        drawFades(c, BASE)

        val pm = ctx.packageManager
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var banner: Drawable? = null
        var icon: Drawable? = null
        try {
            banner = pm.getApplicationBanner(pkg)
        } catch (e: Exception) {
            // nessun banner
        }
        try {
            icon = pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            // nessuna icona
        }

        val cx = W * 0.70f
        val cy = H * 0.30f
        if (banner != null) {
            // Misura grande, quella che l'utente preferisce: 760x428, quasi il doppio dell'icona.
            // Il banner piccolo viene ingrandito con filtro bilineare: resta morbido, ma il
            // colore ora è pieno (ARGB_8888 + PNG) quindi non si vedono più aloni sui bordi.
            val bw = 760
            val bh = 428
            val img = drawableToBitmap(banner, bw, bh)
            val dst = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
            drawRounded(c, img, dst, 32f, paint)
            img.recycle()
        } else if (icon != null) {
            val s = 420
            val img = drawableToBitmap(icon, s, s)
            c.drawBitmap(img, null, RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), paint)
            img.recycle()
        }

        val tp = textPaint(86f, Color.WHITE, true)
        val tl = layout(label, tp, TEXT_W, 2)
        c.save()
        c.translate(TEXT_X, H * 0.30f - tl.height / 2f)
        tl.draw(c)
        c.restore()
        return bmp
    }

    fun formatMeta(info: Tmdb.Info): String {
        val parts = ArrayList<String>()
        if (info.year.isNotEmpty()) parts.add(info.year)
        if (info.genres.isNotEmpty()) parts.add(info.genres.take(3).joinToString(", "))
        if (info.runtime > 0) {
            val h = info.runtime / 60
            val m = info.runtime % 60
            parts.add(if (h > 0) "$h h $m min" else "$m min")
        }
        if (info.rating > 0.0) parts.add("\u2605 " + String.format(Locale.US, "%.1f", info.rating))
        return parts.joinToString("   \u2022   ")
    }
}
