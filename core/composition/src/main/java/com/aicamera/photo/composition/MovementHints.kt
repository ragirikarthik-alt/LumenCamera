package com.aicamera.photo.composition

import com.aicamera.photo.domain.FrameEdge
import com.aicamera.photo.domain.NormalizedPoint
import kotlin.math.abs

/**
 * Turns geometry into short, everyday instructions about how to move the phone.
 *
 * Moving the phone left makes the subject move right on the screen, so every hint is phrased as a
 * phone movement ("Move the phone left") rather than a subject movement.
 */
object MovementHints {

    fun aimHint(current: NormalizedPoint, target: NormalizedPoint, subjectName: String = "subject"): String {
        val dx = target.x - current.x
        val dy = target.y - current.y
        if (abs(dx) < 0.025f && abs(dy) < 0.025f) return "Hold it there"
        return if (abs(dx) >= abs(dy)) {
            val amount = if (abs(dx) < 0.1f) " a little" else ""
            if (dx > 0) "Move the phone$amount left ←" else "Move the phone$amount right →"
        } else {
            val amount = if (abs(dy) < 0.1f) " a little" else ""
            if (dy > 0) "Point the phone$amount up ↑" else "Point the phone$amount down ↓"
        }
    }

    /** Positive tilt means the horizon slopes down to the right: the phone's right side is too high. */
    fun levelHint(tiltDegrees: Float): String =
        if (tiltDegrees > 0) "Straighten: lower the right side of the phone a little"
        else "Straighten: lower the left side of the phone a little"

    fun horizonPositionHint(current: Float, target: Float): String =
        if (target > current) "Point the phone up a little to move the horizon down ↑"
        else "Point the phone down a little to move the horizon up ↓"

    fun sizeHint(currentArea: Float, desiredArea: Float): String =
        if (currentArea < desiredArea) "Step closer (or zoom in)" else "Step back (or zoom out)"

    fun stepDirection(edge: FrameEdge): String = when (edge) {
        FrameEdge.LEFT -> "left"
        FrameEdge.RIGHT -> "right"
        FrameEdge.TOP -> "up"
        FrameEdge.BOTTOM -> "down"
    }

    /** To push something at an edge out of the picture, move the phone away from that edge. */
    fun aimAwayFrom(edge: FrameEdge): String = when (edge) {
        FrameEdge.LEFT -> "move the phone a little right"
        FrameEdge.RIGHT -> "move the phone a little left"
        FrameEdge.TOP -> "point the phone a little down"
        FrameEdge.BOTTOM -> "point the phone a little up"
    }
}
