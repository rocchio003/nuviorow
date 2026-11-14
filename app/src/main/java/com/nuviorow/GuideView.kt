package com.nuviorows

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LogoCache(private val onLoaded: () -> Unit) {
    private val cache = LruCache<String, Bitmap>(150)
    private val busy = HashSet<String>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)

    fun get(url: String): Bitmap? {
        if (!url.startsWith("http")) return null
        val hit = cache.get(url)
        if (hit != null) return hit
        synchronized(this) {
            if (failed.contains(url) || busy.contains(url)) return null
            busy.add(url)
        }
        pool.execute {
            var ok = false
            try {
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
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) {
                    cache.put(url, bmp)
                    ok = true
                }
            } catch (e: Exception) {
                // logo non disponibile
            } finally {
                synchronized(this@LogoCache) {
                    busy.remove(url)
                    if (!ok) failed.add(url)
                }
            }
            if (ok) onLoaded()
        }
        return null
    }
}

/**
 * Griglia della guida TV: canali a sinistra, programmi nel tempo a destra.
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

    private val headerH = dp(40f)
    private val rowH = dp(54f)
    private val chanW = dp(220f)
    private val detailH = dp(130f)

    private val halfHour = 30L * 60_000L
    private val spanMs = 3L * 3600_000L

    private val cBg = 0xFF1C1B22.toInt()
    private val cHeader = 0xFF2A2833.toInt()
    private val cChan = 0xFF2F2D3A.toInt()
    private val cChanSel = 0xFF4B3F8C.toInt()
    private val cCell = 0xFF3A3842.toInt()
    private val cNow = 0xFF4A4756.toInt()
    private val cSel = 0xFFEDEDED.toInt()
    private val cText = 0xFFFFFFFF.toInt()
    private val cTextDim = 0xFFAAAAB5.toInt()
    private val cTextDark = 0xFF111111.toInt()
    private val cGrid = 0x22FFFFFF
    private val cNowLine = 0xFFE53935.toInt()
    private val cBar = 0xFF7C4DFF.toInt()

    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint()
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val minTime: Long
    private val maxTime: Long
    private var viewStart: Long
    private var selTime: Long
    private var selRow = 0
    private var topRow = 0

    private val logos = LogoCache { postInvalidate() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        val now = System.currentTimeMillis()
        minTime = floorHalf(now - 3L * 3600_000L)
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

    private fun programAt(row: Int, t: Long): GProgram? {
        for (p in channels[row].programs) {
            if (t >= p.start && t < p.stop) return p
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

    // ---------- disegno ----------

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

        canvas.drawColor(cBg)

        // intestazione con gli orari
        pFill.color = cHeader
        canvas.drawRect(0f, 0f, w, headerH, pFill)
        pText.typeface = Typeface.DEFAULT
        pText.textSize = dp(14f)
        pText.textAlign = Paint.Align.LEFT
        pText.color = cTextDim
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = cText
        canvas.save()
        canvas.clipRect(0f, 0f, chanW - dp(6f), headerH)
        val groupTitle = if (groupMode) "Scegli un gruppo" else "\u2039 " + groups[groupIndex]
        canvas.drawText(groupTitle, dp(12f), baseline(headerH / 2f, pText), pText)
        canvas.restore()
        pText.typeface = Typeface.DEFAULT
        pText.color = cTextDim
        var t = ceilHalf(viewStart)
        pLine.color = cGrid
        while (t < viewEnd) {
            val x = tlLeft + (t - viewStart) * pxPerMs
            canvas.drawText(timeFmt.format(Date(t)), x + dp(6f), baseline(headerH / 2f, pText), pText)
            canvas.drawLine(x, 0f, x, rowsBottom, pLine)
            t += halfHour
        }

        // righe dei canali
        for (r in 0 until vr) {
            val idx = topRow + r
            if (idx >= channels.size) break
            val ch = channels[idx]
            val top = headerH + r * rowH
            val bottom = top + rowH
            val rowSelected = idx == selRow

            // cella del canale
            pFill.color = if (rowSelected) cChanSel else cChan
            canvas.drawRect(0f, top, chanW - dp(2f), bottom - dp(2f), pFill)

            val box = dp(38f)
            var textX = dp(10f)
            val bmp = logos.get(ch.logo)
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                val scale = Math.min(box / bmp.width, box / bmp.height)
                val bw = bmp.width * scale
                val bh = bmp.height * scale
                val bx = dp(8f) + (box - bw) / 2f
                val by = top + (rowH - bh) / 2f
                rect.set(bx, by, bx + bw, by + bh)
                canvas.drawBitmap(bmp, null, rect, pBmp)
            }
            textX = dp(8f) + box + dp(8f)
            pText.textSize = dp(14f)
            pText.typeface = if (rowSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            pText.color = cText
            canvas.save()
            canvas.clipRect(textX, top, chanW - dp(6f), bottom)
            canvas.drawText(ch.name, textX, baseline(top + rowH / 2f, pText), pText)
            canvas.restore()

            // programmi
            val sel = if (rowSelected) programAt(idx, selTime) else null
            for (p in ch.programs) {
                if (p.stop <= viewStart || p.start >= viewEnd) continue
                val x1 = tlLeft + (Math.max(p.start, viewStart) - viewStart) * pxPerMs + dp(2f)
                val x2 = tlLeft + (Math.min(p.stop, viewEnd) - viewStart) * pxPerMs - dp(2f)
                if (x2 - x1 < dp(4f)) continue
                val isSel = sel === p
                pFill.color = when {
                    isSel -> cSel
                    now >= p.start && now < p.stop -> cNow
                    else -> cCell
                }
                rect.set(x1, top + dp(2f), x2, bottom - dp(4f))
                canvas.drawRoundRect(rect, dp(6f), dp(6f), pFill)
                pText.textSize = dp(14f)
                pText.typeface = Typeface.DEFAULT
                pText.color = if (isSel) cTextDark else cText
                canvas.save()
                canvas.clipRect(x1 + dp(4f), top, x2 - dp(4f), bottom)
                canvas.drawText(p.title, x1 + dp(8f), baseline(top + rowH / 2f - dp(1f), pText), pText)
                canvas.restore()
            }
        }

        // linea dell'ora attuale
        if (now >= viewStart && now <= viewEnd) {
            val x = tlLeft + (now - viewStart) * pxPerMs
            pLine.color = cNowLine
            pLine.strokeWidth = dp(2f)
            canvas.drawLine(x, headerH, x, rowsBottom, pLine)
            pLine.strokeWidth = 0f
        }

        if (groupMode) drawGroups(canvas, rowsBottom)

        drawDetail(canvas, w, h, now)
    }

    private fun drawGroups(canvas: Canvas, rowsBottom: Float) {
        val panelW = dp(320f)
        pFill.color = 0xFA2A2833.toInt()
        canvas.drawRect(0f, headerH, panelW, rowsBottom, pFill)
        val vr = visibleRows()
        for (r in 0 until vr) {
            val gi = groupTop + r
            if (gi >= groups.size) break
            val top = headerH + r * rowH
            if (gi == groupCursor) {
                pFill.color = cChanSel
                canvas.drawRect(0f, top, panelW - dp(2f), top + rowH - dp(2f), pFill)
            }
            pText.textSize = dp(15f)
            pText.typeface = if (gi == groupCursor) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            pText.color = cText
            pText.textAlign = Paint.Align.LEFT
            val mark = if (gi == groupIndex) "\u25CF " else ""
            canvas.save()
            canvas.clipRect(dp(12f), top, panelW - dp(60f), top + rowH)
            canvas.drawText(mark + groups[gi], dp(12f), baseline(top + rowH / 2f, pText), pText)
            canvas.restore()
            pText.textAlign = Paint.Align.RIGHT
            pText.color = cTextDim
            canvas.drawText(groupCounts[gi].toString(), panelW - dp(12f), baseline(top + rowH / 2f, pText), pText)
            pText.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawDetail(canvas: Canvas, w: Float, h: Float, now: Long) {
        val top = h - detailH
        pFill.color = cHeader
        canvas.drawRect(0f, top, w, h, pFill)
        if (channels.isEmpty()) return

        val ch = channels[selRow]
        val p = programAt(selRow, selTime)
        val pad = dp(16f)

        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textSize = dp(20f)
        pText.color = cText
        val title = p?.title ?: "Nessuna informazione"
        canvas.save()
        canvas.clipRect(pad, top, w - pad, top + dp(40f))
        canvas.drawText(title, pad, top + dp(30f), pText)
        canvas.restore()

        pText.typeface = Typeface.DEFAULT
        pText.textSize = dp(14f)
        pText.color = cTextDim
        val sub = if (p != null) {
            ch.name + "  ·  " + dayFmt.format(Date(p.start)) + ", " +
                timeFmt.format(Date(p.start)) + " - " + timeFmt.format(Date(p.stop))
        } else {
            ch.name + (if (ch.group.isNotEmpty()) "  ·  " + ch.group else "")
        }
        canvas.drawText(sub, pad, top + dp(54f), pText)

        if (p != null && now >= p.start && now < p.stop) {
            val barW = dp(240f)
            val frac = (now - p.start).toFloat() / (p.stop - p.start).toFloat()
            pFill.color = 0x33FFFFFF
            rect.set(pad, top + dp(62f), pad + barW, top + dp(66f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
            pFill.color = cBar
            rect.set(pad, top + dp(62f), pad + barW * frac.coerceIn(0f, 1f), top + dp(66f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
        }

        if (p != null && p.desc.isNotEmpty()) {
            pText.textSize = dp(13f)
            pText.color = 0xFFD0D0D8.toInt()
            val lines = wrap(p.desc, pText, w * 0.62f - pad, 2)
            var y = top + dp(90f)
            for (l in lines) {
                canvas.drawText(l, pad, y, pText)
                y += dp(19f)
            }
        }

        pText.textSize = dp(12f)
        pText.color = cTextDim
        pText.textAlign = Paint.Align.RIGHT
        val hint = if (groupMode) "OK o \u25B6: scegli   \u00B7   Indietro: annulla"
        else "OK: apri Sparkle TV   \u00B7   \u25C0: gruppi   \u00B7   Menu: aggiorna"
        canvas.drawText(hint, w - pad, top + dp(120f), pText)
        pText.textAlign = Paint.Align.LEFT
    }
}
