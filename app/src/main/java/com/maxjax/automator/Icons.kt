package com.maxjax.automator

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class Icon { GRIP, PLAY, PAUSE, STOP, CHAT, SEARCH, CLOSE, CHECK }

/**
 * The toolbar icons, drawn as paths rather than emoji. Emoji would render as colour glyphs on
 * some devices and monochrome on others, so a bar built from them never looks like one set.
 */
class IconDrawable(private val icon: Icon, private var tint: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun setIconColor(color: Int) {
        tint = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()

        paint.color = tint
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        path.reset()

        when (icon) {
            Icon.GRIP -> {
                val r = s * 0.075f
                val gapX = s * 0.26f
                val gapY = s * 0.28f
                for (col in -1..0) {
                    for (row in -1..1) {
                        canvas.drawCircle(cx + gapX * (col + 0.5f), cy + gapY * row, r, paint)
                    }
                }
            }

            Icon.PLAY -> {
                // Slightly inset and optically centred: a triangle looks left-heavy when centred.
                val w = s * 0.56f
                val h = s * 0.62f
                path.moveTo(cx - w * 0.38f, cy - h / 2f)
                path.lineTo(cx + w * 0.62f, cy)
                path.lineTo(cx - w * 0.38f, cy + h / 2f)
                path.close()
                canvas.drawPath(path, paint)
            }

            Icon.PAUSE -> {
                val bw = s * 0.17f
                val bh = s * 0.60f
                val gap = s * 0.14f
                val r = bw * 0.45f
                canvas.drawRoundRect(
                    RectF(cx - gap / 2f - bw, cy - bh / 2f, cx - gap / 2f, cy + bh / 2f), r, r, paint
                )
                canvas.drawRoundRect(
                    RectF(cx + gap / 2f, cy - bh / 2f, cx + gap / 2f + bw, cy + bh / 2f), r, r, paint
                )
            }

            Icon.STOP -> {
                val h = s * 0.52f
                val r = s * 0.10f
                canvas.drawRoundRect(RectF(cx - h / 2f, cy - h / 2f, cx + h / 2f, cy + h / 2f), r, r, paint)
            }

            Icon.CHAT -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.105f
                val w = s * 0.62f
                val h = s * 0.48f
                val r = s * 0.14f
                val left = cx - w / 2f
                val top = cy - h / 2f - s * 0.05f
                path.addRoundRect(RectF(left, top, left + w, top + h), r, r, Path.Direction.CW)
                canvas.drawPath(path, paint)
                // tail
                path.reset()
                path.moveTo(cx - w * 0.22f, top + h)
                path.lineTo(cx - w * 0.30f, top + h + s * 0.17f)
                path.lineTo(cx - w * 0.02f, top + h)
                canvas.drawPath(path, paint)
            }

            Icon.SEARCH -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.11f
                val r = s * 0.21f
                val ccx = cx - s * 0.06f
                val ccy = cy - s * 0.06f
                canvas.drawCircle(ccx, ccy, r, paint)
                val d = r * 0.72f
                canvas.drawLine(ccx + d, ccy + d, cx + s * 0.27f, cy + s * 0.27f, paint)
            }

            Icon.CLOSE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.115f
                val d = s * 0.22f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint)
            }

            Icon.CHECK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.12f
                path.moveTo(cx - s * 0.24f, cy + s * 0.02f)
                path.lineTo(cx - s * 0.06f, cy + s * 0.20f)
                path.lineTo(cx + s * 0.25f, cy - s * 0.20f)
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable, still required by the base class")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
