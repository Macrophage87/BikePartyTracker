package io.github.macrophage87.bikeparty.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.github.macrophage87.bikeparty.model.IncidentType
import io.github.macrophage87.bikeparty.model.RiderRole

/**
 * Draws map marker icons at runtime: a colored circle per role with the
 * role's badge letter, and red badges with a pictogram for incidents.
 */
object MarkerFactory {

    fun riderMarker(context: Context, role: RiderRole, isSelf: Boolean, stale: Boolean): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (40 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = center - 2 * density

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(role.colorHex)
            if (stale) alpha = 110
        }
        canvas.drawCircle(center, center, radius, fill)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (isSelf) 3.5f else 2f) * density
            color = if (isSelf) Color.parseColor("#FFD600") else Color.WHITE
            if (stale) alpha = 110
        }
        canvas.drawCircle(center, center, radius, ring)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            if (stale) alpha = 150
        }
        val baseline = center - (text.descent() + text.ascent()) / 2f
        canvas.drawText(role.badge, center, baseline, text)

        return BitmapDrawable(context.resources, bmp)
    }

    fun incidentMarker(context: Context, type: IncidentType): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (44 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = center - 2 * density

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F") }
        canvas.drawCircle(center, center, radius, fill)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        canvas.drawCircle(center, center, radius, ring)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val baseline = center - (text.descent() + text.ascent()) / 2f
        canvas.drawText(type.emoji, center, baseline, text)

        return BitmapDrawable(context.resources, bmp)
    }
}
