package com.nuviorows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LogoCache(private val ctx: Context, private val onLoaded: () -> Unit) {
    private val cache = LruCache<String, Bitmap>(150)
    private val busy = HashSet<String>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val dir = File(ctx.cacheDir, "logos").apply { if (!exists()) mkdirs() }
    private val diskMax = 300

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".png")
    }

    fun get(url: String): Bitmap? {
        if (!url.startsWith("http")) return null
        synchronized(this) {
            cache.get(url)?.let { return it }
            if (failed.contains(url) || busy.contains(url)) return null
            busy.add(url)
        }
        pool.execute {
            val disk = try {
                fileFor(url)
            } catch (e: Exception) {
                null
            }
            // 1) cache su disco: al secondo avvio i loghi sono già pronti
            val cached = if (disk != null && disk.exists()) {
                try {
                    BitmapFactory.decodeFile(disk.path)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val bmp = cached ?: download(url)
            if (bmp != null && cached == null && disk != null) {
                try {
                    FileOutputStream(disk).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    prune()
                } catch (e: Exception) {
                    // la cache su disco è un extra: se fallisce pazienza
                }
            }
            synchronized(this@LogoCache) {
                busy.remove(url)
                if (bmp == null) failed.add(url)
            }
            if (bmp != null) {
                cache.put(url, bmp)
                onLoaded()
            }
        }
        return null
    }

    private fun download(url: String): Bitmap? {
        return try {
            val out = ByteArrayOutputStream()
            Net.stream(url).use { input ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 600_000) throw IOException("immagine troppo grande")
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= diskMax) return
        files.sortedBy { it.lastModified() }.take(files.size - diskMax).forEach { it.delete() }
    }
}

/**
 * Griglia della guida TV in stile glass: canali a sinistra, programmi nel tempo a destra.
 * Frecce su/giù = canale, sinistra/destra = programma precedente/successivo, OK = apri.
 */
class GuideView(
    context: Context,
    private val allChannels: List<GChannel>,
    initialGroup: String,
    private val onOpen: (GChannel) -> Unit,
    private val onGroupChanged: (String) -> Unit
) : View(context) {

    private val allLabel = "Tutti i canali"
    private val groups: List<String> =
        listOf(allLabel) + allChannels.map { it.group }.filter { it.isNotEmpty() }.distinct()
    private val groupCounts: IntArray = IntArray(groups.size) { gi ->
        if (gi == 0) allChannels.size else allChannels.count { it.group == groups[gi] }
    }
    private var channels: List<GChannel> = allChannels
    private var groupIndex = 0
    private var groupCursor = 0
    private var groupTop = 0
    private var groupMode = false

    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    private val headerH = dp(48f)
    private val rowH = dp(56f)
    private val chanW = dp(210f)
    private val detailH = dp(134f)

    private val halfHour = 30L * 60_000L
    private val spanMs = 3L * 3600_000L

    // vetro blu-notte
    private val cText = 0xFFE9E8F5.toInt()
    private val cTextDim = 0xFFB8B8CC.toInt()
    private val cTextFaint = 0xFF8B8AA3.toInt()
    private val cTime = 0xFFD2D5E4.toInt()
    private val cDesc = 0xFFC9C7DE.toInt()
    private val cRim = 0x4CE0EEFF
    private val cAccent = 0xFFA63CFF.toInt()
    private val cNowLine = 0xBCFF5F7A.toInt()

    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val box = RectF()
    private val shade = Path()

    private val tfBold = Typeface.DEFAULT_BOLD
    private val tfNormal = Typeface.DEFAULT
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val minTime: Long
    private val maxTime: Long
    private var viewStart: Long
    private var selTime: Long
    private var selRow = 0
    private var topRow = 0

    private val appContext = context.applicationContext
    private var invalidatePending = false
    private val logos = LogoCache(appContext) {
        // i loghi arrivano uno alla volta: raggruppo i ridisegni invece di farne uno per logo
        if (!invalidatePending) {
            invalidatePending = true
            postDelayed({
                invalidatePending = false
                invalidate()
            }, 80)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        val now = System.currentTimeMillis()
        minTime = floorHalf(now - 1L * 3600_000L)
        maxTime = now + 30L * 3600_000L
        viewStart = floorHalf(now - halfHour)
        selTime = now
        val gi = groups.indexOf(initialGroup)
        if (gi > 0) {
            groupIndex = gi
            groupCursor = gi
            channels = allChannels.filter { it.group == groups[gi] }
        }
    }

    private fun applyGroup(i: Int) {
        groupIndex = i
        channels = if (i == 0) allChannels else allChannels.filter { it.group == groups[i] }
        selRow = 0
        topRow = 0
        onGroupChanged(if (i == 0) "" else groups[i])
    }

    private fun openGroups() {
        if (groups.size <= 1) return
        groupMode = true
        groupCursor = groupIndex
        val vr = visibleRows()
        groupTop = (groupCursor - vr / 2).coerceAtLeast(0)
    }

    // Da chiamare quando si preme Indietro: se l'elenco dei gruppi è aperto, lo chiude.
    fun handleBack(): Boolean {
        if (groupMode) {
            groupMode = false
            invalidate()
            return true
        }
        return false
    }

    private fun groupKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> groupCursor = (groupCursor + 1).coerceAtMost(groups.size - 1)
            KeyEvent.KEYCODE_DPAD_UP -> groupCursor = (groupCursor - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                groupCursor = (groupCursor + visibleRows()).coerceAtMost(groups.size - 1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP ->
                groupCursor = (groupCursor - visibleRows()).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                applyGroup(groupCursor)
                groupMode = false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // niente: si resta nell'elenco
            }
            else -> return false
        }
        val vr = visibleRows()
        if (groupCursor < groupTop) groupTop = groupCursor
        if (groupCursor >= groupTop + vr) groupTop = groupCursor - vr + 1
        invalidate()
        return true
    }

    private fun floorHalf(t: Long): Long = (t / halfHour) * halfHour
    private fun ceilHalf(t: Long): Long = ((t + halfHour - 1) / halfHour) * halfHour

    private fun visibleRows(): Int =
        ((height - headerH - detailH) / rowH).toInt().coerceAtLeast(1)

    // I programmi sono ordinati per inizio: ricerca binaria invece di scandire la lista.
    private fun programAt(row: Int, t: Long): GProgram? {
        val list = channels[row].programs
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val p = list[mid]
            when {
                t < p.start -> hi = mid - 1
                t >= p.stop -> lo = mid + 1
                else -> return p
            }
        }
        return null
    }

    // ---------- tasti ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (groupMode) {
            return if (groupKey(keyCode)) true else super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveRow(visibleRows())
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveRow(-visibleRows())
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (channels.isNotEmpty()) onOpen(channels[selRow])
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    private fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        selRow = (selRow + delta).coerceIn(0, channels.size - 1)
        val vr = visibleRows()
        if (selRow < topRow) topRow = selRow
        if (selRow >= topRow + vr) topRow = selRow - vr + 1
    }

    private fun moveTime(dir: Int) {
        if (channels.isEmpty()) {
            if (dir < 0) openGroups()
            return
        }
        val now = System.currentTimeMillis()
        val progs = channels[selRow].programs
        val cur = programAt(selRow, selTime)
        if (dir > 0) {
            if (cur != null) {
                val nxt = progs.firstOrNull { it.start >= cur.stop }
                selTime = nxt?.start ?: cur.stop
            } else {
                val nxt = progs.firstOrNull { it.start > selTime }
                selTime = nxt?.start ?: (selTime + halfHour)
            }
        } else {
            // programmi precedenti ancora in corso o futuri (il passato non serve)
            val ref = cur?.start ?: selTime
            val prv = progs.lastOrNull { it.stop > now && it.start < ref }
            if (prv == null) {
                openGroups()
                return
            }
            selTime = prv.start
        }
        selTime = selTime.coerceIn(minTime, maxTime)
        while (selTime >= viewStart + spanMs - 600_000L && viewStart + spanMs < maxTime) viewStart += halfHour
        while (selTime < viewStart && viewStart > minTime) viewStart -= halfHour
    }

    // ---------- primitives ----------

    private fun baseline(centerY: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    private fun wrap(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.replace("\n", " ").trim()
        while (rest.isNotEmpty() && out.size < maxLines) {
            var n = p.breakText(rest, true, maxW, null)
            if (n <= 0) break
            if (n < rest.length) {
                val sp = rest.lastIndexOf(' ', n)
                if (sp > 0) n = sp
            }
            var line = rest.substring(0, n).trim()
            val isLast = out.size == maxLines - 1
            if (isLast && n < rest.length && line.length > 1) line = line.dropLast(1).trimEnd() + "…"
            out.add(line)
            rest = rest.substring(n).trim()
        }
        return out
    }

    /** Capsula di vetro. kind: "glass", "now", "sel", "soft" */
    private fun capsule(
        canvas: Canvas,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        kind: String,
        withShadow: Boolean = true
    ) {
        val radius = (b - t) / 2f
        box.set(l, t, r, b)

        if (withShadow) {
            pFill.shader = null
            pFill.color = 0x22000000
            rect.set(l, t + dp(3f), r, b + dp(3f))
            canvas.drawRoundRect(rect, radius, radius, pFill)
        }

        if (kind == "sel") {
            for (i in 7 downTo 1) {
                val g = i * dp(2.4f)
                pFill.shader = null
                pFill.color = ((20 - i * 2).coerceAtLeast(3) shl 24) or 0xB240FF
                rect.set(l - g, t - g, r + g, b + g)
                canvas.drawRoundRect(rect, radius + g, radius + g, pFill)
            }
        }

        pFill.shader = when (kind) {
            "sel" -> LinearGradient(
                l, t, r, b,
                intArrayOf(0xFFCD94FF.toInt(), 0xFFB45CFF.toInt(), 0xFFA63CFF.toInt(), 0xFF8D1FD6.toInt()),
                floatArrayOf(0f, 0.34f, 0.64f, 1f),
                Shader.TileMode.CLAMP
            )
            "now" -> LinearGradient(
                l, t, l, b,
                intArrayOf(0x4DA88CFF, 0x298050E6),
                null,
                Shader.TileMode.CLAMP
            )
            "soft" -> LinearGradient(
                l, t, r, b,
                intArrayOf(0x80A63CFF.toInt(), 0x248050E6),
                null,
                Shader.TileMode.CLAMP
            )
            else -> LinearGradient(
                l, t, l, b,
                intArrayOf(0x33CAD8F6, 0x22929EBE),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(box, radius, radius, pFill)

        // riflesso diagonale del vetro
        shade.reset()
        shade.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(shade)
        pFill.shader = LinearGradient(
            l, t, l + (r - l) * 0.62f, b,
            intArrayOf(
                0x00FFFFFF,
                if (kind == "sel") 0x47FFFFFF else 0x2EFFFFFF,
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.32f, 0.58f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(box, pFill)
        canvas.restore()

        pStroke.shader = null
        pStroke.strokeWidth = dp(1f)
        pStroke.color = when (kind) {
            "sel" -> 0xD9F4E0FF.toInt()
            "now" -> 0x70BEA8FF
            "soft" -> 0x66D2AAFF
            else -> cRim
        }
        canvas.drawRoundRect(box, radius, radius, pStroke)
    }

    private fun glassTile(canvas: Canvas, l: Float, t: Float, size: Float, lit: Boolean) {
        val r = l + size
        val b = t + size
        val radius = dp(10f)
        if (lit) {
            for (i in 4 downTo 1) {
                val g = i * dp(1.8f)
                pFill.shader = null
                pFill.color = ((16 - i * 3).coerceAtLeast(3) shl 24) or 0xA63CFF
                rect.set(l - g, t - g, r + g, b + g)
                canvas.drawRoundRect(rect, radius + g, radius + g, pFill)
            }
        }
        box.set(l, t, r, b)
        pFill.shader = LinearGradient(
            l, t, r, b,
            intArrayOf(0x38DCE8FF, 0x1496A5C8),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(box, radius, radius, pFill)
        pStroke.shader = null
        pStroke.strokeWidth = dp(1f)
        pStroke.color = if (lit) 0x9AD8B4FF.toInt() else 0x42DCEBFF
        canvas.drawRoundRect(box, radius, radius, pStroke)
    }

    private fun initials(name: String): String {
        val clean = name.filter { it.isLetterOrDigit() }
        return if (clean.isEmpty()) "" else clean.take(2).uppercase()
    }

    // ---------- disegno ----------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tlLeft = chanW
        val tlW = w - chanW
        val pxPerMs = tlW / spanMs
        val viewEnd = viewStart + spanMs
        val rowsBottom = h - detailH
        val vr = visibleRows()
        val now = System.currentTimeMillis()

        drawNowLine(canvas, tlLeft, rowsBottom, viewStart, pxPerMs, now)
        drawHeader(canvas, pxPerMs, viewStart, viewEnd)
        drawRows(canvas, tlLeft, pxPerMs, viewStart, viewEnd, vr, now)
        if (groupMode) drawGroups(canvas, rowsBottom)
        drawDetail(canvas, w, h, now)
    }

    private fun drawNowLine(
        canvas: Canvas,
        left: Float,
        rowsBottom: Float,
        start: Long,
        pxPerMs: Float,
        now: Long
    ) {
        val end = start + spanMs
        if (now < start || now > end) return
        val x = left + (now - start) * pxPerMs
        pLine.shader = null
        pLine.color = 0x33FF5F7A
        pLine.strokeWidth = dp(6f)
        canvas.drawLine(x, headerH, x, rowsBottom, pLine)
        pLine.color = cNowLine
        pLine.strokeWidth = dp(1.6f)
        canvas.drawLine(x, headerH, x, rowsBottom, pLine)
    }

    private fun drawHeader(canvas: Canvas, pxPerMs: Float, start: Long, end: Long) {
        pText.typeface = tfMedium
        pText.textSize = dp(12.5f)
        pText.textAlign = Paint.Align.LEFT
        pText.color = cTextDim
        val title = if (groupMode) "Scegli un gruppo" else "\u2039 " + groups[groupIndex]
        canvas.save()
        canvas.clipRect(0f, 0f, chanW - dp(6f), headerH)
        canvas.drawText(title, dp(16f), baseline(headerH / 2f, pText), pText)
        canvas.restore()

        pText.textSize = dp(15f)
        pText.color = cTime
        var t = ceilHalf(start)
        while (t < end) {
            val x = chanW + (t - start) * pxPerMs + dp(10f)
            canvas.drawText(timeFmt.format(Date(t)), x, baseline(headerH / 2f, pText), pText)
            t += halfHour
        }
    }

    private fun drawRows(
        canvas: Canvas,
        tlLeft: Float,
        pxPerMs: Float,
        start: Long,
        end: Long,
        vr: Int,
        now: Long
    ) {
        for (r in 0 until vr) {
            val idx = topRow + r
            if (idx >= channels.size) break
            val ch = channels[idx]
            val top = headerH + r * rowH
            val bottom = top + rowH
            val rowSelected = idx == selRow

            // barretta del canale selezionato
            if (rowSelected) {
                for (i in 4 downTo 1) {
                    val g = i * dp(1.6f)
                    pFill.shader = null
                    pFill.color = ((14 - i * 3).coerceAtLeast(2) shl 24) or 0xA63CFF
                    rect.set(dp(6f) - g, top + dp(10f) - g, dp(9f) + g, bottom - dp(10f) + g)
                    canvas.drawRoundRect(rect, dp(2f) + g, dp(2f) + g, pFill)
                }
                pFill.shader = LinearGradient(
                    dp(6f), top, dp(9f), bottom,
                    intArrayOf(0xFFD9B8FF.toInt(), cAccent),
                    null,
                    Shader.TileMode.CLAMP
                )
                rect.set(dp(6f), top + dp(10f), dp(9f), bottom - dp(10f))
                canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
            }

            // logo in una piastrella di vetro
            val size = dp(34f)
            val logoLeft = dp(16f)
            val logoTop = top + (rowH - size) / 2f
            glassTile(canvas, logoLeft, logoTop, size, rowSelected)

            val bmp = logos.get(ch.logo)
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val inset = dp(5f)
                val avail = size - inset * 2
                val scale = Math.min(avail / bmp.width, avail / bmp.height)
                val bw = bmp.width * scale
                val bh = bmp.height * scale
                val bx = logoLeft + (size - bw) / 2f
                val by = logoTop + (size - bh) / 2f
                rect.set(bx, by, bx + bw, by + bh)
                canvas.drawBitmap(bmp, null, rect, pBmp)
            } else {
                val text = initials(ch.name)
                if (text.isNotEmpty()) {
                    pText.typeface = tfBold
                    pText.textSize = dp(11f)
                    pText.textAlign = Paint.Align.CENTER
                    pText.color = cText
                    canvas.drawText(
                        text,
                        logoLeft + size / 2f,
                        baseline(logoTop + size / 2f, pText),
                        pText
                    )
                    pText.textAlign = Paint.Align.LEFT
                }
            }

            pText.typeface = if (rowSelected) tfBold else tfNormal
            pText.textSize = dp(14f)
            pText.color = if (rowSelected) 0xFFFFFFFF.toInt() else cText
            val textX = logoLeft + size + dp(10f)
            canvas.save()
            canvas.clipRect(textX, top, chanW - dp(8f), bottom)
            canvas.drawText(ch.name, textX, baseline(top + rowH / 2f, pText), pText)
            canvas.restore()

            // programmi
            val sel = if (rowSelected) programAt(idx, selTime) else null
            for (p in ch.programs) {
                if (p.stop <= start || p.start >= end) continue
                val l = tlLeft + (Math.max(p.start, start) - start) * pxPerMs
                val rr = tlLeft + (Math.min(p.stop, end) - start) * pxPerMs
                if (rr - l < dp(8f)) continue

                val isSel = sel === p
                val kind = when {
                    isSel -> "sel"
                    now >= p.start && now < p.stop -> "now"
                    else -> "glass"
                }
                capsule(canvas, l + dp(2f), top + dp(4f), rr - dp(2f), bottom - dp(4f), kind)

                pText.typeface = if (isSel) tfBold else tfNormal
                pText.textSize = dp(13.5f)
                pText.color = if (isSel) 0xFFFFFFFF.toInt() else 0xFFEEEDF8.toInt()
                canvas.save()
                canvas.clipRect(l + dp(6f), top, rr - dp(8f), bottom)
                canvas.drawText(
                    p.title,
                    l + dp(15f),
                    baseline(top + rowH / 2f, pText),
                    pText
                )
                canvas.restore()
            }
        }
    }

    private fun drawGroups(canvas: Canvas, rowsBottom: Float) {
        val panelW = dp(300f)
        pFill.shader = LinearGradient(
            0f, headerH, 0f, rowsBottom,
            intArrayOf(0xF2101128.toInt(), 0xF7090A1A.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, headerH, panelW, rowsBottom, pFill)

        pLine.shader = null
        pLine.color = 0x24FFFFFF
        pLine.strokeWidth = dp(1f)
        canvas.drawLine(panelW, headerH, panelW, rowsBottom, pLine)

        val vr = visibleRows()
        for (r in 0 until vr) {
            val gi = groupTop + r
            if (gi >= groups.size) break
            val top = headerH + r * rowH
            val bottom = top + rowH
            if (gi == groupCursor) {
                capsule(canvas, dp(8f), top + dp(6f), panelW - dp(12f), bottom - dp(6f), "soft", false)
            }

            pText.typeface = if (gi == groupCursor) tfBold else tfNormal
            pText.textSize = dp(13.5f)
            pText.color = cText
            pText.textAlign = Paint.Align.LEFT
            val mark = if (gi == groupIndex) "\u25CF " else ""
            canvas.save()
            canvas.clipRect(dp(22f), top, panelW - dp(64f), bottom)
            canvas.drawText(mark + groups[gi], dp(22f), baseline(top + rowH / 2f, pText), pText)
            canvas.restore()

            pText.textAlign = Paint.Align.RIGHT
            pText.typeface = tfNormal
            pText.textSize = dp(11.5f)
            pText.color = cTextFaint
            canvas.drawText(
                groupCounts[gi].toString(),
                panelW - dp(24f),
                baseline(top + rowH / 2f, pText),
                pText
            )
            pText.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawDetail(canvas: Canvas, w: Float, h: Float, now: Long) {
        val top = h - detailH

        pLine.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        pLine.strokeWidth = dp(1f)
        canvas.drawLine(0f, top, w, top, pLine)
        pLine.shader = null

        // titolo grande
        pText.typeface = tfBold
        pText.textSize = dp(36f)
        pText.textAlign = Paint.Align.LEFT
        pText.color = 0xFFFFFFFF.toInt()
        canvas.drawText("Guida TV", dp(26f), top + dp(58f), pText)
        val titleW = pText.measureText("Guida TV")

        pText.typeface = tfMedium
        pText.textSize = dp(9f)
        pText.letterSpacing = 0.22f
        pText.color = 0x73FFFFFF
        canvas.drawText("NUVIO ROWS", dp(27f), top + dp(80f), pText)
        pText.letterSpacing = 0f

        if (channels.isEmpty()) {
            pText.typeface = tfNormal
            pText.textSize = dp(13f)
            pText.color = cTextDim
            canvas.drawText("Nessun canale", dp(26f) + titleW + dp(30f), top + dp(58f), pText)
            return
        }

        val ch = channels[selRow]
        val p = programAt(selRow, selTime)
        val x = dp(26f) + maxOf(titleW, dp(190f)) + dp(30f)

        pText.typeface = tfBold
        pText.textSize = dp(19f)
        pText.color = 0xFFFFFFFF.toInt()
        canvas.save()
        canvas.clipRect(x, top, w - dp(26f), top + dp(40f))
        canvas.drawText(p?.title ?: "Nessuna informazione", x, top + dp(28f), pText)
        canvas.restore()

        pText.typeface = tfNormal
        pText.textSize = dp(12.5f)
        pText.color = cTextDim
        val meta = if (p != null) {
            ch.name + "  \u00B7  " + dayFmt.format(Date(p.start)) + ", " +
                timeFmt.format(Date(p.start)) + " \u2013 " + timeFmt.format(Date(p.stop))
        } else {
            ch.name + (if (ch.group.isNotEmpty()) "  \u00B7  " + ch.group else "")
        }
        canvas.drawText(meta, x, top + dp(50f), pText)
        val metaW = pText.measureText(meta)

        val onAir = p != null && now >= p.start && now < p.stop
        if (onAir) {
            val bx = x + metaW + dp(12f)
            val bw = dp(58f)
            val bh = dp(16f)
            val by = top + dp(39f)
            capsule(canvas, bx, by, bx + bw, by + bh, "soft", false)
            pText.typeface = tfMedium
            pText.textSize = dp(8.5f)
            pText.letterSpacing = 0.10f
            pText.color = 0xFFE6D4FF.toInt()
            pText.textAlign = Paint.Align.CENTER
            canvas.drawText("IN ONDA", bx + bw / 2f, baseline(by + bh / 2f, pText), pText)
            pText.textAlign = Paint.Align.LEFT
            pText.letterSpacing = 0f
        }

        if (onAir && p != null) {
            val barW = dp(170f)
            val barY = top + dp(62f)
            pFill.shader = null
            pFill.color = 0x1FFFFFFF
            rect.set(x, barY, x + barW, barY + dp(4f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)

            val frac = ((now - p.start).toFloat() / (p.stop - p.start).toFloat()).coerceIn(0f, 1f)
            val fillW = barW * frac
            pFill.shader = LinearGradient(
                x, barY, x + barW, barY,
                intArrayOf(0xFFC07BFF.toInt(), 0xFF8D1FD6.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            rect.set(x, barY, x + fillW, barY + dp(4f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)

            pFill.shader = null
            pFill.color = 0x33A63CFF
            rect.set(x - dp(2f), barY - dp(2f), x + fillW + dp(2f), barY + dp(6f))
            canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
        }

        if (p != null && p.desc.isNotEmpty()) {
            pText.typeface = tfNormal
            pText.textSize = dp(12f)
            pText.color = cDesc
            val lines = wrap(p.desc, pText, w - x - dp(190f), 2)
            var y = top + dp(84f)
            for (l in lines) {
                canvas.drawText(l, x, y, pText)
                y += dp(16f)
            }
        }

        pText.typeface = tfMedium
        pText.textSize = dp(10.5f)
        pText.color = cTextFaint
        pText.textAlign = Paint.Align.RIGHT
        val hint = if (groupMode) "OK o \u25B6: scegli   \u00B7   Indietro: annulla"
        else "OK: apri Sparkle TV   \u00B7   \u25C0: gruppi   \u00B7   Menu: aggiorna"
        canvas.drawText(hint, w - dp(26f), top + dp(112f), pText)
        pText.textAlign = Paint.Align.LEFT
    }
}
