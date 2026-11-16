package com.nuviorows

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Sfondo del pannello Guida TV: vetro blu-notte con aloni viola/blu,
 * riflesso sul bordo alto e filo chiaro sul perimetro.
 * Espone anche l'outline arrotondato, così la finestra può tagliare i figli.
 */
class GlassPanelDrawable(private val density: Float) : Drawable() {

    private val radius = 20f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)

        fill.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xFF0B0C1E.toInt(), 0xFF0D0F26.toInt(), 0xFF090B1C.toInt()),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fill)

        glow(canvas, b, 0.05f, 0.98f, 0.62f, 0x7A2EE8, 0xA0)
        glow(canvas, b, 0.88f, 0.06f, 0.52f, 0x1C5EDE, 0x86)
        glow(canvas, b, 0.99f, 0.94f, 0.50f, 0x1842CC, 0x70)

        // velo chiaro sul bordo superiore
        fill.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.top + 120f * density,
            intArrayOf(0x1FFFFFFF, 0x0AFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, fill)

        canvas.restore()

        rim.color = 0x26FFFFFF
        canvas.drawRoundRect(rect, radius, radius, rim)
    }

    private fun glow(canvas: Canvas, b: Rect, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        val centerX = b.left + b.width() * cx
        val centerY = b.top + b.height() * cy
        val rad = maxOf(b.width(), b.height()) * r
        bloom.shader = RadialGradient(
            centerX, centerY, rad,
            intArrayOf(color, color and 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        bloom.alpha = alpha
        canvas.drawRect(b, bloom)
        bloom.alpha = 255
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
