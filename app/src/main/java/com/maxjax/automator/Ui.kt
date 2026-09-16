package com.maxjax.automator

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** One palette for the app screens and the floating bar, so they read as the same product. */
object Palette {
    val BG = 0xFF0D0F12.toInt()
    val SURFACE = 0xFF15181D.toInt()
    val SURFACE_HI = 0xFF1B1F26.toInt()
    val STROKE = 0xFF262D36.toInt()
    val TEXT = 0xFFEDF0F4.toInt()
    val TEXT_DIM = 0xFF98A2B0.toInt()
    val TEXT_FAINT = 0xFF646F7D.toInt()
    val ACCENT = 0xFF3B82F6.toInt()
    val ACCENT_SOFT = 0xFF1E3A8A.toInt()
    val OK = 0xFF22C55E.toInt()
    val WARN = 0xFFF59E0B.toInt()
    val DANGER = 0xFFEF4444.toInt()
    val BAR = 0xF21A1E24.toInt()
    val RIPPLE = 0x33FFFFFF
}

// ---------------------------------------------------------------- units

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

// ---------------------------------------------------------------- drawables

fun roundedRect(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.setColor(fill)
    d.cornerRadius = radius
    if (strokeWidth > 0) d.setStroke(strokeWidth, stroke)
    return d
}

fun circle(fill: Int): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.OVAL
    d.setColor(fill)
    return d
}

/**
 * The mask's alpha decides where the ripple is drawn, so it has to be opaque — passing the
 * (often transparent) background as its own mask makes the feedback invisible.
 */
fun withRipple(content: Drawable?, mask: Drawable, highlight: Int = Palette.RIPPLE): Drawable =
    RippleDrawable(ColorStateList.valueOf(highlight), content, mask)

fun rectRipple(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0): Drawable =
    withRipple(roundedRect(fill, radius, stroke, strokeWidth), roundedRect(Color.WHITE, radius))

fun circleRipple(fill: Int = Color.TRANSPARENT): Drawable =
    withRipple(if (fill == Color.TRANSPARENT) null else circle(fill), circle(Color.WHITE))

// ---------------------------------------------------------------- layout params

fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .also { it.topMargin = topMargin }

fun weighted(weight: Float = 1f, leftMargin: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        .also { it.leftMargin = leftMargin }

// ---------------------------------------------------------------- type scale

private fun Context.text(s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
    val tv = TextView(this)
    tv.text = s
    tv.textSize = sizeSp
    tv.setTextColor(color)
    if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD)
    tv.setLineSpacing(dpf(2f), 1f)
    return tv
}

fun Context.uiTitle(s: CharSequence): TextView = text(s, 24f, Palette.TEXT, bold = true)
fun Context.uiSectionTitle(s: CharSequence): TextView {
    val tv = text(s.toString().uppercase(), 11f, Palette.TEXT_FAINT, bold = true)
    tv.letterSpacing = 0.12f
    return tv
}

fun Context.uiBody(s: CharSequence): TextView = text(s, 14f, Palette.TEXT_DIM)
fun Context.uiCaption(s: CharSequence): TextView = text(s, 12f, Palette.TEXT_FAINT)

// ---------------------------------------------------------------- containers

/** A full-screen vertical column on the app background. */
fun Context.uiScreen(): LinearLayout {
    val root = LinearLayout(this)
    root.orientation = LinearLayout.VERTICAL
    root.setBackgroundColor(Palette.BG)
    return root
}

fun Context.uiHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null): View {
    val bar = LinearLayout(this)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setPadding(dp(20), dp(18), dp(20), dp(14))

    if (onBack != null) {
        val back = TextView(this)
        back.text = "‹"
        back.textSize = 28f
        back.setTextColor(Palette.TEXT_DIM)
        back.gravity = Gravity.CENTER
        val size = dp(36)
        back.background = circleRipple()
        back.isClickable = true
        back.setOnClickListener { onBack() }
        bar.addView(back, LinearLayout.LayoutParams(size, size).also { it.rightMargin = dp(8) })
    }

    val column = LinearLayout(this)
    column.orientation = LinearLayout.VERTICAL
    column.addView(uiTitle(title))
    if (subtitle != null) column.addView(uiCaption(subtitle), matchWrap(dp(3)))
    bar.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return bar
}

/** A rounded surface that groups related content. */
fun Context.uiCard(): LinearLayout {
    val card = LinearLayout(this)
    card.orientation = LinearLayout.VERTICAL
    card.background = roundedRect(Palette.SURFACE, dpf(16f), Palette.STROKE, dp(1))
    card.clipToOutline = true
    return card
}

fun Context.uiDivider(): View {
    val v = View(this)
    v.setBackgroundColor(Palette.STROKE)
    return v
}

fun LinearLayout.addDivider(ctx: Context, insetStart: Int = 0) {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1).coerceAtLeast(1))
    lp.leftMargin = insetStart
    addView(ctx.uiDivider(), lp)
}

// ---------------------------------------------------------------- controls

private fun Context.buttonBase(label: String, fill: Int, textColor: Int, stroke: Int, onClick: () -> Unit): TextView {
    val b = TextView(this)
    b.text = label
    b.textSize = 15f
    b.setTextColor(textColor)
    b.setTypeface(Typeface.DEFAULT_BOLD)
    b.gravity = Gravity.CENTER
    b.setPadding(dp(16), dp(13), dp(16), dp(13))
    b.background = rectRipple(fill, dpf(12f), stroke, if (stroke == Color.TRANSPARENT) 0 else dp(1))
    b.isClickable = true
    b.isFocusable = true
    b.setOnClickListener { onClick() }
    return b
}

fun Context.uiPrimaryButton(label: String, onClick: () -> Unit): TextView =
    buttonBase(label, Palette.ACCENT, Color.WHITE, Color.TRANSPARENT, onClick)

fun Context.uiSecondaryButton(label: String, onClick: () -> Unit): TextView =
    buttonBase(label, Palette.SURFACE_HI, Palette.TEXT, Palette.STROKE, onClick)

fun Context.uiQuietButton(label: String, onClick: () -> Unit): TextView =
    buttonBase(label, Color.TRANSPARENT, Palette.TEXT_DIM, Palette.STROKE, onClick)

fun Context.uiDangerButton(label: String, onClick: () -> Unit): TextView =
    buttonBase(label, Color.TRANSPARENT, Palette.DANGER, Color.TRANSPARENT, onClick)

fun Context.uiButtonRow(vararg views: View): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    views.forEachIndexed { i, v -> row.addView(v, weighted(1f, if (i == 0) 0 else dp(10))) }
    return row
}

/** A tappable row inside a card: title, optional subtitle, optional badge, chevron. */
fun Context.uiRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    badgeColor: Int = Palette.ACCENT,
    onClick: () -> Unit
): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(dp(16), dp(14), dp(14), dp(14))
    row.background = rectRipple(Color.TRANSPARENT, 0f)
    row.isClickable = true
    row.setOnClickListener { onClick() }

    val column = LinearLayout(this)
    column.orientation = LinearLayout.VERTICAL
    val t = TextView(this)
    t.text = title
    t.textSize = 16f
    t.setTextColor(Palette.TEXT)
    t.maxLines = 1
    t.ellipsize = android.text.TextUtils.TruncateAt.END
    column.addView(t)
    if (subtitle != null) column.addView(uiCaption(subtitle), matchWrap(dp(2)))
    row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    if (badge != null) row.addView(uiBadge(badge, badgeColor), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.leftMargin = dp(8) })

    val chevron = TextView(this)
    chevron.text = "›"
    chevron.textSize = 20f
    chevron.setTextColor(Palette.TEXT_FAINT)
    chevron.setPadding(dp(10), 0, dp(4), dp(3))
    row.addView(chevron)
    return row
}

fun Context.uiBadge(label: String, color: Int): TextView {
    val b = TextView(this)
    b.text = label.uppercase()
    b.textSize = 10f
    b.letterSpacing = 0.08f
    b.setTypeface(Typeface.DEFAULT_BOLD)
    b.setTextColor(color)
    b.setPadding(dp(8), dp(4), dp(8), dp(4))
    b.background = roundedRect(color and 0x30FFFFFF, dpf(6f))
    return b
}

/** Status chip with a state dot — used for the service state on the main screen. */
fun Context.uiStatusChip(label: String, color: Int): LinearLayout {
    val chip = LinearLayout(this)
    chip.orientation = LinearLayout.HORIZONTAL
    chip.gravity = Gravity.CENTER_VERTICAL
    chip.setPadding(dp(10), dp(6), dp(12), dp(6))
    chip.background = roundedRect(color and 0x26FFFFFF, dpf(20f))

    val dot = View(this)
    dot.background = circle(color)
    chip.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(8) })

    val t = TextView(this)
    t.text = label
    t.textSize = 13f
    t.setTypeface(Typeface.DEFAULT_BOLD)
    t.setTextColor(color)
    chip.addView(t)
    return chip
}

fun Context.uiScroll(child: View): ScrollView {
    val s = ScrollView(this)
    s.setBackgroundColor(Palette.BG)
    s.isFillViewport = true
    s.clipToPadding = false
    s.addView(child)
    return s
}

fun Context.toastShort(s: String) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
