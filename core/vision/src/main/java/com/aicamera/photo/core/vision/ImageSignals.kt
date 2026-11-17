package com.aicamera.photo.core.vision

import com.aicamera.photo.domain.ChromaGrid
import com.aicamera.photo.domain.DetectedSubject
import com.aicamera.photo.domain.EdgeDistraction
import com.aicamera.photo.domain.FrameEdge
import com.aicamera.photo.domain.LineSegment
import com.aicamera.photo.domain.LumaImage
import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.NormalizedRect
import com.aicamera.photo.domain.SeparationIssue
import com.aicamera.photo.domain.SubjectKind
import com.aicamera.photo.domain.VisualSignals
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A detected straight line with its pixel support. */
data class HoughLine(val segment: LineSegment, val votes: Int, val directionDegrees: Float)

data class HorizonLine(val angleDegrees: Float, val positionY: Float, val confidence: Float)

/** Result of the custom CV pass on one luma frame. */
data class CvResult(
    val signals: VisualSignals,
    val lines: List<HoughLine>,
    val horizonLine: HorizonLine?,
    val edgeDensity: Float,
)

/**
 * Lightweight, allocation-conscious computer vision on a small grayscale image.
 * All outputs are normalized and world-upright.
 */
class ImageSignals(
    /** Normalizer for Laplacian variance; depends on the analysis resolution. */
    private val sharpnessK: Float = 120f,
) {
    private var previous: LumaImage? = null

    fun reset() {
        previous = null
    }

    fun compute(
        luma: LumaImage,
        chroma: ChromaGrid?,
        subjects: List<DetectedSubject>,
        primary: DetectedSubject?,
        deviceStability: Float,
        trackMotion: Boolean = true,
    ): CvResult {
        val w = luma.width
        val h = luma.height
        val n = w * h
        val px = luma.pixels

        // --- Exposure statistics ---
        var sum = 0L
        var sumSq = 0L
        var high = 0
        var low = 0
        for (i in 0 until n) {
            val v = px[i].toInt() and 0xFF
            sum += v
            sumSq += v.toLong() * v
            if (v >= 250) high++
            if (v <= 6) low++
        }
        val mean = sum.toFloat() / n
        val variance = (sumSq.toFloat() / n - mean * mean).coerceAtLeast(0f)
        val contrast = sqrt(variance) / 128f

        // --- Gradients (Sobel) and Laplacian ---
        val mag = FloatArray(n)
        val gxArr = FloatArray(n)
        val gyArr = FloatArray(n)
        var lapSum = 0.0
        var lapSq = 0.0
        var lapCount = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val tl = px[i - w - 1].toInt() and 0xFF
                val tc = px[i - w].toInt() and 0xFF
                val tr = px[i - w + 1].toInt() and 0xFF
                val ml = px[i - 1].toInt() and 0xFF
                val mc = px[i].toInt() and 0xFF
                val mr = px[i + 1].toInt() and 0xFF
                val bl = px[i + w - 1].toInt() and 0xFF
                val bc = px[i + w].toInt() and 0xFF
                val br = px[i + w + 1].toInt() and 0xFF
                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                gxArr[i] = gx.toFloat()
                gyArr[i] = gy.toFloat()
                mag[i] = sqrt((gx * gx + gy * gy).toFloat())
                val lap = (tc + ml + mr + bc - 4 * mc).toDouble()
                lapSum += lap
                lapSq += lap * lap
                lapCount++
            }
        }
        val lapMean = lapSum / max(1, lapCount)
        val lapVar = (lapSq / max(1, lapCount) - lapMean * lapMean).toFloat()
        val sharpness = (lapVar / (lapVar + sharpnessK)).coerceIn(0f, 1f)

        // Edge threshold: adaptive, with a floor so flat scenes don't produce noise edges.
        val edgeThreshold = max(80f, percentile(mag, 0.85f))
        val edges = BooleanArray(n)
        var edgeCount = 0
        for (i in 0 until n) if (mag[i] >= edgeThreshold) { edges[i] = true; edgeCount++ }
        val edgeDensity = edgeCount.toFloat() / n

        // --- Motion (frame difference) ---
        var motion = 0f
        val prev = previous
        if (trackMotion && prev != null && prev.width == w && prev.height == h) {
            var diff = 0L
            var count = 0
            var i = 0
            while (i < n) {
                diff += abs((px[i].toInt() and 0xFF) - (prev.pixels[i].toInt() and 0xFF))
                count++
                i += 3
            }
            motion = ((diff.toFloat() / count) / 255f * 8f).coerceIn(0f, 1f)
        }
        if (trackMotion) previous = LumaImage(w, h, px.copyOf())

        // --- Lines ---
        val lines = hough(edges, mag, w, h)
        val horizon = horizonFrom(lines, luma)
        val diag = hypot(w.toFloat(), h.toFloat())
        val oblique = lines.filter { isOblique(it.directionDegrees) && it.segment.length > 0.3f }
        val leading = oblique.sortedByDescending { it.votes }.take(4).map { it.segment }
        val convergence = convergence(oblique)
        val diagonalStrength = oblique.maxOfOrNull { (it.segment.length / 1.2f).coerceAtMost(1f) * it.segment.strength }?.coerceIn(0f, 1f) ?: 0f

        // --- Regional statistics (edge density by thirds) ---
        val topDensity = regionDensity(edges, w, h, 0f, 0f, 1f, 1f / 3f)
        val bottomDensity = regionDensity(edges, w, h, 0f, 2f / 3f, 1f, 1f)
        val foreground = (bottomDensity / 0.18f).coerceIn(0f, 1f)
        val sky = (topDensity / 0.18f).coerceIn(0f, 1f)

        // --- Symmetry ---
        val symmetry = symmetry(luma, edgeDensity, contrast)

        // --- Clutter outside the subject ---
        val primaryBox = primary?.boundingBox
        val clutter = if (primaryBox != null) {
            val expanded = expand(primaryBox, 0.05f)
            densityOutside(edges, w, h, expanded)
        } else edgeDensity
        val clutterNorm = (clutter / 0.2f).coerceIn(0f, 1f)

        // --- Subject vs background brightness ---
        var subjectLuma: Float? = null
        var backgroundLuma: Float? = null
        if (primaryBox != null) {
            val inside = regionMean(luma, primaryBox)
            subjectLuma = inside / 255f
            val total = mean * n
            val area = regionArea(w, h, primaryBox)
            backgroundLuma = if (n - area > 0) ((total - inside * area) / (n - area)) / 255f else null
        }

        // --- Framing ---
        val framing = framing(luma, edges)

        // --- Warmth ---
        val warmth = chroma?.let { ((it.meanV() - it.meanU()) * 4f).coerceIn(-1f, 1f) } ?: 0f

        // --- Edge distractions and separation ---
        val distractions = edgeDistractions(luma, mean, subjects, primary)
        val separation = primary?.let { separation(it, edges, w, h, lines) }

        return CvResult(
            signals = VisualSignals(
                meanLuma = mean / 255f,
                highlightClipping = high.toFloat() / n,
                shadowClipping = low.toFloat() / n,
                contrast = contrast,
                sharpness = sharpness,
                motion = motion,
                deviceStability = deviceStability,
                clutter = clutterNorm,
                symmetry = symmetry,
                leadingLines = leading,
                lineConvergence = convergence,
                diagonalStrength = diagonalStrength,
                framingRegion = framing,
                foregroundDetail = foreground,
                skyDetail = sky,
                warmth = warmth,
                edgeDistractions = distractions,
                separationIssue = separation,
                subjectLuma = subjectLuma,
                backgroundLuma = backgroundLuma,
            ),
            lines = lines,
            horizonLine = horizon,
            edgeDensity = edgeDensity,
        )
    }

    // ------------------------------------------------------------------------------------------

    private fun percentile(values: FloatArray, p: Float): Float {
        // Histogram-based percentile (gradient magnitudes are 0..~1450).
        val bins = IntArray(256)
        for (v in values) bins[(v / 6f).toInt().coerceIn(0, 255)]++
        val target = (values.size * p).toInt()
        var acc = 0
        for (b in 0 until 256) {
            acc += bins[b]
            if (acc >= target) return b * 6f
        }
        return 255 * 6f
    }

    private fun hough(edges: BooleanArray, mag: FloatArray, w: Int, h: Int): List<HoughLine> {
        val thetaSteps = 90 // 2-degree resolution over 0..180
        val diag = hypot(w.toDouble(), h.toDouble()).toInt() + 1
        val rhoBins = diag * 2 + 1
        val acc = IntArray(thetaSteps * rhoBins)
        val cosT = DoubleArray(thetaSteps) { cos(Math.toRadians(it * 2.0)) }
        val sinT = DoubleArray(thetaSteps) { sin(Math.toRadians(it * 2.0)) }
        // Subsample edge pixels to bound cost.
        val stride = if (edges.count { it } > 2500) 2 else 1
        var k = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!edges[i]) continue
            k++
            if (k % stride != 0) continue
            for (t in 0 until thetaSteps) {
                val rho = (x * cosT[t] + y * sinT[t]).toInt() + diag
                acc[t * rhoBins + rho]++
            }
        }
        val minVotes = (min(w, h) * 0.35f / stride).toInt().coerceAtLeast(12)
        val peaks = mutableListOf<Triple<Int, Int, Int>>() // theta, rho, votes
        for (t in 0 until thetaSteps) for (r in 0 until rhoBins) {
            val v = acc[t * rhoBins + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -2..2) for (dr in -3..3) {
                if (dt == 0 && dr == 0) continue
                val tt = (t + dt + thetaSteps) % thetaSteps
                val rr = r + dr
                if (rr !in 0 until rhoBins) continue
                if (acc[tt * rhoBins + rr] > v) { isMax = false; break@loop }
            }
            if (isMax) peaks += Triple(t, r, v)
        }
        return peaks.sortedByDescending { it.third }.take(10).mapNotNull { (t, r, votes) ->
            segmentFor(edges, w, h, cosT[t], sinT[t], (r - diag).toDouble(), votes * stride, t * 2f)
        }
    }

    private fun segmentFor(
        edges: BooleanArray, w: Int, h: Int, c: Double, s: Double, rho: Double, votes: Int, thetaDeg: Float,
    ): HoughLine? {
        // Points on the line: x*c + y*s = rho. Direction vector = (-s, c).
        var minT = Double.MAX_VALUE
        var maxT = -Double.MAX_VALUE
        var support = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!edges[y * w + x]) continue
            val d = x * c + y * s - rho
            if (abs(d) <= 1.5) {
                val t = -x * s + y * c
                if (t < minT) minT = t
                if (t > maxT) maxT = t
                support++
            }
        }
        if (support < 8 || maxT <= minT) return null
        val x0 = c * rho
        val y0 = s * rho
        val p1x = x0 + minT * -s
        val p1y = y0 + minT * c
        val p2x = x0 + maxT * -s
        val p2y = y0 + maxT * c
        val start = NormalizedPoint((p1x / w).toFloat().coerceIn(0f, 1f), (p1y / h).toFloat().coerceIn(0f, 1f))
        val end = NormalizedPoint((p2x / w).toFloat().coerceIn(0f, 1f), (p2y / h).toFloat().coerceIn(0f, 1f))
        val lengthPx = maxT - minT
        val density = (support / lengthPx).toFloat().coerceIn(0f, 1f)
        // Line direction angle in image space (y down): normal angle minus 90.
        var dir = thetaDeg - 90f
        if (dir < -90f) dir += 180f
        if (dir > 90f) dir -= 180f
        val ordered = if (start.x <= end.x) LineSegment(start, end, density) else LineSegment(end, start, density)
        return HoughLine(ordered, votes, dir)
    }

    private fun isOblique(dir: Float): Boolean {
        val a = abs(dir)
        return a in 15f..75f
    }

    private fun horizonFrom(lines: List<HoughLine>, luma: LumaImage): HorizonLine? {
        val candidates = lines.filter { abs(it.directionDegrees) <= 20f && it.segment.length >= 0.45f }
        val best = candidates.maxByOrNull { it.votes * it.segment.length } ?: return null
        val seg = best.segment
        val dx = seg.end.x - seg.start.x
        val yAtCenter = if (abs(dx) < 1e-4f) (seg.start.y + seg.end.y) / 2f
        else seg.start.y + (0.5f - seg.start.x) * (seg.end.y - seg.start.y) / dx
        if (yAtCenter !in 0.08f..0.92f) return null
        // A horizon usually separates a brighter sky from darker ground.
        val above = regionMean(luma, NormalizedRect(0f, (yAtCenter - 0.2f).coerceAtLeast(0f), 1f, (yAtCenter - 0.02f).coerceAtLeast(0.01f)))
        val below = regionMean(luma, NormalizedRect(0f, (yAtCenter + 0.02f).coerceAtMost(0.99f), 1f, (yAtCenter + 0.2f).coerceAtMost(1f)))
        val brightnessCue = ((above - below) / 60f).coerceIn(0f, 1f)
        val confidence = (0.35f + 0.35f * seg.length + 0.3f * brightnessCue).coerceIn(0f, 1f) * seg.strength.coerceAtLeast(0.6f)
        // Image-space angle of the line (positive = slopes down to the right).
        return HorizonLine(seg.angleDegrees, yAtCenter, confidence)
    }

    private fun convergence(lines: List<HoughLine>): NormalizedPoint? {
        val pos = lines.filter { it.directionDegrees > 0 }.sortedByDescending { it.votes }.take(3)
        val neg = lines.filter { it.directionDegrees < 0 }.sortedByDescending { it.votes }.take(3)
        var sx = 0f
        var sy = 0f
        var wsum = 0f
        for (a in pos) for (b in neg) {
            val p = intersect(a.segment, b.segment) ?: continue
            if (p.x !in 0.05f..0.95f || p.y !in 0.05f..0.95f) continue
            val weight = (a.votes + b.votes).toFloat()
            sx += p.x * weight
            sy += p.y * weight
            wsum += weight
        }
        return if (wsum > 0f) NormalizedPoint(sx / wsum, sy / wsum) else null
    }

    private fun intersect(a: LineSegment, b: LineSegment): NormalizedPoint? {
        val x1 = a.start.x; val y1 = a.start.y; val x2 = a.end.x; val y2 = a.end.y
        val x3 = b.start.x; val y3 = b.start.y; val x4 = b.end.x; val y4 = b.end.y
        val den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(den) < 1e-5f) return null
        val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / den
        val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / den
        return NormalizedPoint(px, py)
    }

    private fun symmetry(luma: LumaImage, edgeDensity: Float, contrast: Float): Float {
        // Compare a 24x18 block-averaged image with its mirror.
        val gw = 24
        val gh = 18
        val grid = blockAverage(luma, gw, gh)
        var diff = 0f
        var dev = 0f
        val mean = grid.average().toFloat()
        for (y in 0 until gh) for (x in 0 until gw / 2) {
            val a = grid[y * gw + x]
            val b = grid[y * gw + (gw - 1 - x)]
            diff += abs(a - b)
            dev += abs(a - mean) + abs(b - mean)
        }
        if (dev < 1f) return 0f
        val raw = (1f - diff / dev).coerceIn(0f, 1f)
        // Featureless frames (plain wall, sky) are trivially symmetric; require structure.
        val structure = (edgeDensity / 0.05f).coerceIn(0f, 1f) * (contrast / 0.12f).coerceIn(0f, 1f)
        return raw * structure
    }

    private fun blockAverage(luma: LumaImage, gw: Int, gh: Int): FloatArray {
        val out = FloatArray(gw * gh)
        val bw = luma.width.toFloat() / gw
        val bh = luma.height.toFloat() / gh
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var s = 0
            var c = 0
            val x0 = (gx * bw).toInt()
            val x1 = ((gx + 1) * bw).toInt().coerceAtMost(luma.width)
            val y0 = (gy * bh).toInt()
            val y1 = ((gy + 1) * bh).toInt().coerceAtMost(luma.height)
            for (y in y0 until y1) for (x in x0 until x1) { s += luma.at(x, y); c++ }
            out[gy * gw + gx] = if (c > 0) s.toFloat() / c else 0f
        }
        return out
    }

    private fun framing(luma: LumaImage, edges: BooleanArray): NormalizedRect? {
        val gw = 16
        val gh = 12
        val grid = blockAverage(luma, gw, gh)
        var border = 0f
        var bc = 0
        var inner = 0f
        var ic = 0
        for (y in 0 until gh) for (x in 0 until gw) {
            val v = grid[y * gw + x]
            val isBorder = x < 2 || y < 2 || x >= gw - 2 || y >= gh - 2
            val isInner = x in gw / 4 until gw * 3 / 4 && y in gh / 4 until gh * 3 / 4
            if (isBorder) { border += v; bc++ }
            if (isInner) { inner += v; ic++ }
        }
        border /= bc
        inner /= ic
        if (inner - border < 35f) return null
        val threshold = (inner + border) / 2f
        var minX = gw; var minY = gh; var maxX = -1; var maxY = -1
        for (y in 0 until gh) for (x in 0 until gw) {
            if (grid[y * gw + x] > threshold) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        if (maxX < 0) return null
        // A frame must be enclosed: the bright region should not touch at least three edges.
        val touches = listOf(minX == 0, minY == 0, maxX == gw - 1, maxY == gh - 1).count { it }
        if (touches > 1) return null
        return NormalizedRect(minX.toFloat() / gw, minY.toFloat() / gh, (maxX + 1f) / gw, (maxY + 1f) / gh)
    }

    private fun edgeDistractions(
        luma: LumaImage, meanLuma: Float, subjects: List<DetectedSubject>, primary: DetectedSubject?,
    ): List<EdgeDistraction> {
        val out = mutableListOf<EdgeDistraction>()
        // Partially cut-off secondary objects touching an edge.
        for (s in subjects) {
            if (s == primary || s.kind == SubjectKind.FACE) continue
            val b = s.boundingBox
            if (b.area !in 0.005f..0.15f) continue
            val edge = when {
                b.left <= 0.01f -> FrameEdge.LEFT
                b.right >= 0.99f -> FrameEdge.RIGHT
                b.top <= 0.01f -> FrameEdge.TOP
                b.bottom >= 0.99f -> FrameEdge.BOTTOM
                else -> null
            } ?: continue
            val strength = (0.45f + b.area * 2f).coerceAtMost(0.85f) * (0.5f + 0.5f * s.confidence)
            out += EdgeDistraction(edge, b, if (s.confidence >= 0.75f && s.label != "subject") s.label else "object", strength)
        }
        // Bright spots along the frame edge pull the eye out of the picture.
        val strips = listOf(
            FrameEdge.LEFT to NormalizedRect(0f, 0.1f, 0.06f, 0.9f),
            FrameEdge.RIGHT to NormalizedRect(0.94f, 0.1f, 1f, 0.9f),
            FrameEdge.TOP to NormalizedRect(0.1f, 0f, 0.9f, 0.06f),
            FrameEdge.BOTTOM to NormalizedRect(0.1f, 0.94f, 0.9f, 1f),
        )
        for ((edge, strip) in strips) {
            val m = regionMean(luma, strip)
            val stripContrast = (m - meanLuma) / 255f
            if (stripContrast > 0.28f && meanLuma < 170f) {
                // Skip a bright sky along the top edge in landscapes.
                if (edge == FrameEdge.TOP) continue
                out += EdgeDistraction(edge, strip, "bright area", (0.4f + stripContrast).coerceAtMost(0.8f))
            }
        }
        return out
    }

    private fun separation(primary: DetectedSubject, edges: BooleanArray, w: Int, h: Int, lines: List<HoughLine>): SeparationIssue? {
        val b = primary.boundingBox
        if (b.area > 0.5f || b.area < 0.01f) return null
        val bw = b.width
        val bh = b.height
        val left = NormalizedRect((b.left - bw * 0.35f).coerceAtLeast(0f), b.top, b.left, b.bottom)
        val right = NormalizedRect(b.right, b.top, (b.right + bw * 0.35f).coerceAtMost(1f), b.bottom)
        val top = NormalizedRect(b.left, (b.top - bh * 0.3f).coerceAtLeast(0f), b.right, b.top)
        val dl = if (left.width > 0.01f) regionDensity(edges, w, h, left.left, left.top, left.right, left.bottom) else 0f
        val dr = if (right.width > 0.01f) regionDensity(edges, w, h, right.left, right.top, right.right, right.bottom) else 0f
        val dt = if (top.height > 0.01f) regionDensity(edges, w, h, top.left, top.top, top.right, top.bottom) else 0f

        // A near-vertical line rising behind the top of the subject ("pole out of the head").
        val headTop = b.top
        val headSpan = (b.left + bw * 0.2f)..(b.right - bw * 0.2f)
        val pole = lines.firstOrNull { l ->
            abs(abs(l.directionDegrees) - 90f) <= 12f &&
                l.segment.length > 0.2f &&
                min(l.segment.start.y, l.segment.end.y) < headTop - 0.02f &&
                max(l.segment.start.y, l.segment.end.y) > headTop + bh * 0.1f &&
                ((l.segment.start.x + l.segment.end.x) / 2f) in headSpan
        }
        if (pole != null) {
            val lineX = (pole.segment.start.x + pole.segment.end.x) / 2f
            val side = if (lineX < b.center.x) FrameEdge.LEFT else FrameEdge.RIGHT
            return SeparationIssue(primary.trackingId, side, side, 0.75f, verticalLineBehindSubject = true)
        }

        val maxD = max(dl, max(dr, dt))
        val strength = ((maxD - 0.12f) / 0.2f).coerceIn(0f, 1f)
        if (strength < 0.4f) return null
        val cluttered = when (maxD) {
            dl -> FrameEdge.LEFT
            dr -> FrameEdge.RIGHT
            else -> FrameEdge.TOP
        }
        // Stepping towards the cluttered side shifts the background behind the subject to the cleaner side.
        val step = when {
            cluttered == FrameEdge.TOP -> if (dl < dr) FrameEdge.RIGHT else FrameEdge.LEFT
            abs(dl - dr) < 0.04f -> null
            else -> cluttered
        }
        return SeparationIssue(primary.trackingId, cluttered, step, strength, verticalLineBehindSubject = false)
    }

    companion object {
        fun regionDensity(edges: BooleanArray, w: Int, h: Int, l: Float, t: Float, r: Float, b: Float): Float {
            val x0 = (l * w).toInt().coerceIn(0, w)
            val x1 = (r * w).toInt().coerceIn(0, w)
            val y0 = (t * h).toInt().coerceIn(0, h)
            val y1 = (b * h).toInt().coerceIn(0, h)
            var c = 0
            var total = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                total++
                if (edges[y * w + x]) c++
            }
            return if (total == 0) 0f else c.toFloat() / total
        }

        fun densityOutside(edges: BooleanArray, w: Int, h: Int, box: NormalizedRect): Float {
            val x0 = (box.left * w).toInt(); val x1 = (box.right * w).toInt()
            val y0 = (box.top * h).toInt(); val y1 = (box.bottom * h).toInt()
            var c = 0
            var total = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (x in x0 until x1 && y in y0 until y1) continue
                total++
                if (edges[y * w + x]) c++
            }
            return if (total == 0) 0f else c.toFloat() / total
        }

        fun regionMean(luma: LumaImage, r: NormalizedRect): Float {
            val x0 = (r.left * luma.width).toInt().coerceIn(0, luma.width - 1)
            val x1 = (r.right * luma.width).toInt().coerceIn(x0 + 1, luma.width)
            val y0 = (r.top * luma.height).toInt().coerceIn(0, luma.height - 1)
            val y1 = (r.bottom * luma.height).toInt().coerceIn(y0 + 1, luma.height)
            var s = 0L
            var c = 0
            for (y in y0 until y1) for (x in x0 until x1) { s += luma.at(x, y); c++ }
            return if (c == 0) 0f else s.toFloat() / c
        }

        fun regionArea(w: Int, h: Int, r: NormalizedRect): Int =
            ((r.right - r.left) * w).toInt().coerceAtLeast(0) * ((r.bottom - r.top) * h).toInt().coerceAtLeast(0)

        fun expand(r: NormalizedRect, by: Float) =
            NormalizedRect(r.left - by, r.top - by, r.right + by, r.bottom + by).clamped()
    }
}
