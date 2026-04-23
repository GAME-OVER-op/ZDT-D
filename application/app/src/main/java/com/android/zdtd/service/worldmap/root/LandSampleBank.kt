package com.android.zdtd.service.worldmap.root

import com.android.zdtd.service.worldmap.ui.dashboard.WorldLandData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object LandSampleBank {

    private data class PolygonInfo(
        val polygon: IntArray,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val bboxArea: Float,
    )

    private val visiblePolygons: List<PolygonInfo> by lazy {
        WorldLandData.polygons.mapNotNull { polygon ->
            if (polygon.size < 6) return@mapNotNull null

            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            var index = 0
            while (index + 1 < polygon.size) {
                val x = polygon[index] / 10000f
                val y = polygon[index + 1] / 10000f
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                index += 2
            }

            val area = (maxX - minX) * (maxY - minY)
            if (area < MIN_VISIBLE_BBOX_AREA) return@mapNotNull null

            PolygonInfo(
                polygon = polygon,
                minX = minX,
                minY = minY,
                maxX = maxX,
                maxY = maxY,
                bboxArea = area,
            )
        }
    }

    val canvasSamples: List<Pair<Float, Float>> by lazy {
        val samples = mutableListOf<Pair<Float, Float>>()

        visiblePolygons.forEach { info ->
            val spanX = info.maxX - info.minX
            val spanY = info.maxY - info.minY
            val area = info.bboxArea
            val stepsX = when {
                area < 0.0008f -> 7
                area < 0.0020f -> 10
                area < 0.0060f -> 14
                else -> 18
            }
            val stepsY = when {
                area < 0.0008f -> 7
                area < 0.0020f -> 10
                area < 0.0060f -> 14
                else -> 18
            }
            val stepX = max(0.0022f, spanX / stepsX)
            val stepY = max(0.0022f, spanY / stepsY)

            // Try polygon center first so small countries keep a stable sample.
            val centerX = (info.minX + info.maxX) * 0.5f
            val centerY = (info.minY + info.maxY) * 0.5f
            if (pointInPolygon(centerX, centerY, info.polygon)) {
                samples += toCanvasFractions(centerX, centerY)
            }

            var yy = info.minY + stepY * 0.5f
            while (yy < info.maxY) {
                var xx = info.minX + stepX * 0.5f
                while (xx < info.maxX) {
                    if (isInteriorPoint(xx, yy, info.polygon, area)) {
                        samples += toCanvasFractions(xx, yy)
                    }
                    xx += stepX
                }
                yy += stepY
            }
        }

        val distinct = samples
            .map { (it.first * 1200).toInt() to (it.second * 1200).toInt() }
            .distinct()
            .map { it.first / 1200f to it.second / 1200f }
            .filter { it.first in MIN_CANVAS_X..MAX_CANVAS_X && it.second in MIN_CANVAS_Y..MAX_CANVAS_Y }

        if (distinct.isNotEmpty()) distinct else listOf(0.50f to 0.30f)
    }

    fun projectLonLatToCanvas(longitude: Float, latitude: Float): Pair<Float, Float> {
        val normalizedX = ((longitude + 180f) / 360f).coerceIn(0f, 1f)
        val normalizedY = ((90f - latitude) / 180f).coerceIn(0f, 1f)
        val x = MAP_LEFT + normalizedX * MAP_WIDTH
        val y = MAP_TOP + normalizedY * MAP_HEIGHT
        return x.coerceIn(MIN_CANVAS_X, MAX_CANVAS_X) to y.coerceIn(MIN_CANVAS_Y, MAX_CANVAS_Y)
    }

    fun refineCanvasPoint(x: Float, y: Float): Pair<Float, Float> {
        val clampedX = x.coerceIn(MIN_CANVAS_X, MAX_CANVAS_X)
        val clampedY = y.coerceIn(MIN_CANVAS_Y, MAX_CANVAS_Y)
        if (isCanvasPointOnVisibleLand(clampedX, clampedY)) {
            return clampedX to clampedY
        }

        val nearby = nearestCanvasSample(clampedX, clampedY)
        val dx = nearby.first - clampedX
        val dy = nearby.second - clampedY
        val distance = dx * dx + dy * dy
        return if (distance <= 0.020f * 0.020f) nearby else nearby
    }

    fun nearestCanvasSample(x: Float, y: Float): Pair<Float, Float> {
        var best = canvasSamples.first()
        var bestDist = Float.MAX_VALUE
        for (sample in canvasSamples) {
            val dx = sample.first - x
            val dy = sample.second - y
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = sample
            }
        }
        return best
    }

    fun isCanvasPointOnVisibleLand(x: Float, y: Float): Boolean {
        val worldX = ((x - MAP_LEFT) / MAP_WIDTH).coerceIn(0f, 1f)
        val worldY = ((y - MAP_TOP) / MAP_HEIGHT).coerceIn(0f, 1f)
        return visiblePolygons.any { info ->
            worldX in info.minX..info.maxX &&
                worldY in info.minY..info.maxY &&
                pointInPolygon(worldX, worldY, info.polygon)
        }
    }

    private fun isInteriorPoint(x: Float, y: Float, polygon: IntArray, area: Float): Boolean {
        if (!pointInPolygon(x, y, polygon)) return false
        val ring = when {
            area < 0.0008f -> 0.0015f
            area < 0.0020f -> 0.0022f
            area < 0.0060f -> 0.0032f
            else -> 0.0048f
        }
        return insideCross(x, y, ring, polygon)
    }

    private fun insideCross(x: Float, y: Float, margin: Float, polygon: IntArray): Boolean {
        return pointInPolygon(x - margin, y, polygon) &&
            pointInPolygon(x + margin, y, polygon) &&
            pointInPolygon(x, y - margin, polygon) &&
            pointInPolygon(x, y + margin, polygon)
    }

    private fun toCanvasFractions(worldX: Float, worldY: Float): Pair<Float, Float> {
        val x = MAP_LEFT + worldX * MAP_WIDTH
        val y = MAP_TOP + worldY * MAP_HEIGHT
        return x.coerceIn(MIN_CANVAS_X, MAX_CANVAS_X) to y.coerceIn(MIN_CANVAS_Y, MAX_CANVAS_Y)
    }

    private fun pointInPolygon(x: Float, y: Float, polygon: IntArray): Boolean {
        var inside = false
        var j = polygon.size - 2
        var i = 0
        while (i < polygon.size) {
            val xi = polygon[i] / 10000f
            val yi = polygon[i + 1] / 10000f
            val xj = polygon[j] / 10000f
            val yj = polygon[j + 1] / 10000f
            val denom = if (abs(yj - yi) < 0.000001f) 0.000001f else (yj - yi)
            val intersects = ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / denom + xi)
            if (intersects) inside = !inside
            j = i
            i += 2
        }
        return inside
    }

    private const val MAP_LEFT = 0.035f
    private const val MAP_TOP = 0.09f
    private const val MAP_WIDTH = 0.93f
    private const val MAP_HEIGHT = 0.68f
    private const val MIN_CANVAS_X = 0.06f
    private const val MAX_CANVAS_X = 0.94f
    private const val MIN_CANVAS_Y = 0.13f
    private const val MAX_CANVAS_Y = 0.74f
    private const val MIN_VISIBLE_BBOX_AREA = 0.00012f
}
