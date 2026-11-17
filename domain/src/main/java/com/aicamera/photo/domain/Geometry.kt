package com.aicamera.photo.domain

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in normalized frame coordinates: (0,0) is top-left, (1,1) is bottom-right. */
@Serializable
data class NormalizedPoint(val x: Float, val y: Float) {
    fun distanceTo(other: NormalizedPoint): Float = hypot(x - other.x, y - other.y)
    operator fun minus(other: NormalizedPoint) = NormalizedPoint(x - other.x, y - other.y)
    operator fun plus(other: NormalizedPoint) = NormalizedPoint(x + other.x, y + other.y)
    fun clamped() = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    companion object {
        val CENTER = NormalizedPoint(0.5f, 0.5f)
    }
}

/** A rectangle in normalized frame coordinates (0.0 to 1.0). */
@Serializable
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val center: NormalizedPoint get() = NormalizedPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(p: NormalizedPoint) = p.x in left..right && p.y in top..bottom

    /** True when the rectangle is within [margin] of any frame edge. */
    fun touchesEdge(margin: Float = 0.02f): Boolean =
        left <= margin || top <= margin || right >= 1f - margin || bottom >= 1f - margin

    fun intersectionOverUnion(other: NormalizedRect): Float {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        val inter = (r - l).coerceAtLeast(0f) * (b - t).coerceAtLeast(0f)
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clamped() = NormalizedRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
        fun fromCorners(a: NormalizedPoint, b: NormalizedPoint) =
            NormalizedRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }
}

/** A line segment in normalized coordinates, with a detection strength in 0..1. */
@Serializable
data class LineSegment(val start: NormalizedPoint, val end: NormalizedPoint, val strength: Float = 1f) {
    val length: Float get() = start.distanceTo(end)

    /** Angle in degrees in image space (y grows downwards). Positive slopes down to the right. */
    val angleDegrees: Float
        get() = Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
}

/**
 * Quarter-turn rotations inside the unit square.
 *
 * The analysis pipeline works in a "world-upright" frame (rotated so gravity points down even when
 * the phone is held sideways). The UI is locked to portrait, so overlay geometry is mapped back
 * with these helpers.
 */
object FrameRotation {
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Rotates [p] clockwise by [degrees] (a multiple of 90) within the unit square. */
    fun rotatePointCw(p: NormalizedPoint, degrees: Int): NormalizedPoint = when (normalize(degrees)) {
        0 -> p
        90 -> NormalizedPoint(1f - p.y, p.x)
        180 -> NormalizedPoint(1f - p.x, 1f - p.y)
        270 -> NormalizedPoint(p.y, 1f - p.x)
        else -> throw IllegalArgumentException("Only quarter turns are supported: $degrees")
    }

    fun rotateRectCw(r: NormalizedRect, degrees: Int): NormalizedRect =
        NormalizedRect.fromCorners(
            rotatePointCw(NormalizedPoint(r.left, r.top), degrees),
            rotatePointCw(NormalizedPoint(r.right, r.bottom), degrees),
        )

    fun rotateLineCw(l: LineSegment, degrees: Int) =
        LineSegment(rotatePointCw(l.start, degrees), rotatePointCw(l.end, degrees), l.strength)

    fun mirrorX(p: NormalizedPoint) = NormalizedPoint(1f - p.x, p.y)
    fun mirrorX(r: NormalizedRect) = NormalizedRect(1f - r.right, r.top, 1f - r.left, r.bottom)
}
