package io.github.macrophage87.bikeparty.ride

import io.github.macrophage87.bikeparty.model.RoutePoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Minimal GPX reader: collects track points (`trkpt`) and route points
 * (`rtept`) plus the first `name` element. Large tracks are downsampled so the
 * shared route stays small enough to broadcast to the group.
 */
object GpxParser {

    data class Result(val name: String?, val points: List<RoutePoint>)

    private const val MAX_SHARED_POINTS = 2000

    fun parse(input: InputStream): Result {
        val parser = XmlPullParserFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newPullParser()
        parser.setInput(input, null)

        var name: String? = null
        val points = mutableListOf<RoutePoint>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "trkpt", "rtept" -> {
                        // toDoubleOrNull accepts "NaN"/"Infinity"; keep finite coords only.
                        val lat = parser.getAttributeValue(null, "lat")
                            ?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        val lon = parser.getAttributeValue(null, "lon")
                            ?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        if (lat != null && lon != null) points.add(RoutePoint(lat, lon))
                    }
                    "name" -> if (name == null) {
                        name = parser.nextText().trim().ifEmpty { null }
                    }
                }
            }
            event = parser.next()
        }
        return Result(name, downsample(points))
    }

    private fun downsample(points: List<RoutePoint>, max: Int = MAX_SHARED_POINTS): List<RoutePoint> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / max
        val out = ArrayList<RoutePoint>(max + 1)
        var i = 0.0
        while (i < points.size) {
            out.add(points[i.toInt()])
            i += step
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }
}
