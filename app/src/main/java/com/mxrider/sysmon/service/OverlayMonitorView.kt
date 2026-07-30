package com.mxrider.sysmon.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.mxrider.sysmon.R

/**
 * The floating mini-monitor card: four label+value rows (CPU / MEM / NET /
 * BAT) on a translucent dark rounded card with a 3dp accent pill on the left
 * edge — the Win11 summary-view idea at status-widget size. Drawn with Canvas
 * like the notification tiles (no Compose: an overlay window has no lifecycle
 * owner to host a composition, and four text rows do not need one).
 *
 * Threading: [setData] may be called from any thread — it stores immutable
 * strings and posts an invalidate; onDraw reads them on the main thread only.
 * The card is deliberately theme-independent (always dark): it floats over
 * arbitrary apps, most of them games, where a light card would glare.
 */
internal class OverlayMonitorView(context: Context) : View(context) {

    private class Row(val label: String, @Volatile var value: String = DASH)

    private val rows = arrayOf(
        Row(context.getString(R.string.overlay_label_cpu)),
        Row(context.getString(R.string.overlay_label_mem)),
        Row(context.getString(R.string.overlay_label_net)),
        Row(context.getString(R.string.overlay_label_bat)),
    )

    @Volatile
    private var accentArgb: Int = 0xFF0F6CBD.toInt()

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val semibold: Typeface =
        ResourcesCompat.getFont(context, R.font.selawksb) ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface =
        ResourcesCompat.getFont(context, R.font.selawk) ?: Typeface.DEFAULT

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE61A1B1E.toInt() // ~90% opaque near-black
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9BA0A6.toInt()
        typeface = regular
        textSize = dp(11f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = semibold
        textSize = dp(13f)
    }
    private val cardRect = RectF()
    private val pillRect = RectF()

    init {
        contentDescription = context.getString(R.string.settings_overlay)
    }

    /** Values for one tick; null slots keep the dash. Any thread. */
    fun setData(cpu: String?, mem: String?, net: String?, bat: String?, accent: Int) {
        rows[0].value = cpu ?: DASH
        rows[1].value = mem ?: DASH
        rows[2].value = net ?: DASH
        rows[3].value = bat ?: DASH
        accentArgb = accent
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            dp(CARD_WIDTH_DP).toInt(),
            dp(PAD_V_DP * 2 + ROW_H_DP * rows.size).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = dp(8f)
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, corner, corner, cardPaint)

        pillPaint.color = accentArgb
        val pillTop = h / 2f - dp(10f)
        // Reused like cardRect above: this view redraws every 2 s for as long
        // as the overlay is on, and allocating in onDraw is needless garbage
        // in a window that can stay up for hours.
        pillRect.set(dp(3f), pillTop, dp(6f), pillTop + dp(20f))
        canvas.drawRoundRect(pillRect, dp(1.5f), dp(1.5f), pillPaint)

        val labelX = dp(14f)
        val valueRight = w - dp(12f)
        rows.forEachIndexed { index, row ->
            val centerY = dp(PAD_V_DP) + dp(ROW_H_DP) * index + dp(ROW_H_DP) / 2f
            val labelBase = centerY - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(row.label, labelX, labelBase, labelPaint)
            val value = row.value
            val valueBase = centerY - (valuePaint.descent() + valuePaint.ascent()) / 2f
            canvas.drawText(value, valueRight - valuePaint.measureText(value), valueBase, valuePaint)
        }
    }

    // Movement is a drag on the whole card (see the service's touch listener);
    // there is no click action, but accessibility services still expect the
    // contract to exist.
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        const val CARD_WIDTH_DP = 148f
        const val ROW_H_DP = 22f
        const val PAD_V_DP = 8f

        /**
         * Full-height card (all four readout rows). The service needs this to
         * clamp a restored position before the view has been measured.
         */
        const val CARD_MAX_HEIGHT_DP = PAD_V_DP * 2 + ROW_H_DP * 4

        /** Same never-fabricate placeholder as everywhere else. */
        const val DASH = "—"
    }
}
