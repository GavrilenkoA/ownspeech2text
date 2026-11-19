package app.murmur.ui.design

import androidx.compose.ui.graphics.vector.PathNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of a flattened outline. */
data class Pt(val x: Double, val y: Double)

/** Axis-aligned bounds of a flattened outline. */
data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun near(o: Bounds, tol: Double) =
        abs(left - o.left) <= tol && abs(top - o.top) <= tol && abs(right - o.right) <= tol && abs(bottom - o.bottom) <= tol

    override fun toString() = "[%.3f, %.3f – %.3f, %.3f]".format(left, top, right, bottom)
}

fun List<Pt>.bounds(): Bounds = Bounds(minOf { it.x }, minOf { it.y }, maxOf { it.x }, maxOf { it.y })

/**
 * Flattens Compose [PathNode]s (the nodes an ImageVector draws) into points along the outline: lines by their end
 * points, Béziers at 32 steps, elliptical arcs (SVG endpoint parameterisation, radii scaled up as SVG F.6.6 requires)
 * at 64 steps. Used to compare generated icon geometry with the prototype's SVG elements.
 */
object PathGeometry {
    private const val CURVE_STEPS = 32
    private const val ARC_STEPS = 64

    fun flatten(nodes: List<PathNode>): List<Pt> {
        val out = mutableListOf<Pt>()
        var x = 0.0; var y = 0.0
        var startX = 0.0; var startY = 0.0
        // Last control point for the reflective (S / T) forms, and which kind of segment set it.
        var cx = 0.0; var cy = 0.0
        var lastCubic = false; var lastQuad = false

        fun lineTo(nx: Double, ny: Double) { x = nx; y = ny; out += Pt(x, y) }
        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
            val x0 = x; val y0 = y
            for (i in 1..CURVE_STEPS) {
                val t = i.toDouble() / CURVE_STEPS; val u = 1 - t
                out += Pt(
                    u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                    u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
                )
            }
            x = x3; y = y3; cx = x2; cy = y2
        }
        fun quad(x1: Double, y1: Double, x2: Double, y2: Double) {
            val x0 = x; val y0 = y
            for (i in 1..CURVE_STEPS) {
                val t = i.toDouble() / CURVE_STEPS; val u = 1 - t
                out += Pt(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2)
            }
            x = x2; y = y2; cx = x1; cy = y1
        }
        fun arc(rxIn: Double, ryIn: Double, phiDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
            val x1 = x; val y1 = y
            if (x1 == x2 && y1 == y2) return
            var rx = abs(rxIn); var ry = abs(ryIn)
            if (rx == 0.0 || ry == 0.0) { lineTo(x2, y2); return }
            val phi = phiDeg * PI / 180
            val cosP = cos(phi); val sinP = sin(phi)
            val dx = (x1 - x2) / 2; val dy = (y1 - y2) / 2
            val x1p = cosP * dx + sinP * dy
            val y1p = -sinP * dx + cosP * dy
            val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
            if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            var coef = sqrt(max(0.0, num / den))
            if (large == sweep) coef = -coef
            val cxp = coef * rx * y1p / ry
            val cyp = -coef * ry * x1p / rx
            val ccx = cosP * cxp - sinP * cyp + (x1 + x2) / 2
            val ccy = sinP * cxp + cosP * cyp + (y1 + y2) / 2
            fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
                val dot = ux * vx + uy * vy
                val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
                val a = acos((dot / len).coerceIn(-1.0, 1.0))
                return if (ux * vy - uy * vx < 0) -a else a
            }
            val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
            var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
            if (!sweep && delta > 0) delta -= 2 * PI
            if (sweep && delta < 0) delta += 2 * PI
            for (i in 1..ARC_STEPS) {
                val th = theta1 + delta * i / ARC_STEPS
                val ex = rx * cos(th); val ey = ry * sin(th)
                out += Pt(cosP * ex - sinP * ey + ccx, sinP * ex + cosP * ey + ccy)
            }
            x = x2; y = y2
        }

        for (n in nodes) {
            var cubicNow = false; var quadNow = false
            when (n) {
                is PathNode.MoveTo -> { x = n.x.toDouble(); y = n.y.toDouble(); startX = x; startY = y; out += Pt(x, y) }
                is PathNode.RelativeMoveTo -> { x += n.dx; y += n.dy; startX = x; startY = y; out += Pt(x, y) }
                is PathNode.LineTo -> lineTo(n.x.toDouble(), n.y.toDouble())
                is PathNode.RelativeLineTo -> lineTo(x + n.dx, y + n.dy)
                is PathNode.HorizontalTo -> lineTo(n.x.toDouble(), y)
                is PathNode.RelativeHorizontalTo -> lineTo(x + n.dx, y)
                is PathNode.VerticalTo -> lineTo(x, n.y.toDouble())
                is PathNode.RelativeVerticalTo -> lineTo(x, y + n.dy)
                is PathNode.CurveTo -> { cubic(n.x1.d, n.y1.d, n.x2.d, n.y2.d, n.x3.d, n.y3.d); cubicNow = true }
                is PathNode.RelativeCurveTo -> {
                    cubic(x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2, x + n.dx3, y + n.dy3); cubicNow = true
                }
                is PathNode.ReflectiveCurveTo -> {
                    val (rx, ry) = if (lastCubic) (2 * x - cx) to (2 * y - cy) else x to y
                    cubic(rx, ry, n.x1.d, n.y1.d, n.x2.d, n.y2.d); cubicNow = true
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    val (rx, ry) = if (lastCubic) (2 * x - cx) to (2 * y - cy) else x to y
                    cubic(rx, ry, x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2); cubicNow = true
                }
                is PathNode.QuadTo -> { quad(n.x1.d, n.y1.d, n.x2.d, n.y2.d); quadNow = true }
                is PathNode.RelativeQuadTo -> { quad(x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2); quadNow = true }
                is PathNode.ReflectiveQuadTo -> {
                    val (rx, ry) = if (lastQuad) (2 * x - cx) to (2 * y - cy) else x to y
                    quad(rx, ry, n.x.d, n.y.d); quadNow = true
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val (rx, ry) = if (lastQuad) (2 * x - cx) to (2 * y - cy) else x to y
                    quad(rx, ry, x + n.dx, y + n.dy); quadNow = true
                }
                is PathNode.ArcTo -> arc(
                    n.horizontalEllipseRadius.d, n.verticalEllipseRadius.d, n.theta.d, n.isMoreThanHalf, n.isPositiveArc,
                    n.arcStartX.d, n.arcStartY.d,
                )
                is PathNode.RelativeArcTo -> arc(
                    n.horizontalEllipseRadius.d, n.verticalEllipseRadius.d, n.theta.d, n.isMoreThanHalf, n.isPositiveArc,
                    x + n.arcStartDx, y + n.arcStartDy,
                )
                PathNode.Close -> lineTo(startX, startY)
            }
            lastCubic = cubicNow; lastQuad = quadNow
        }
        return out
    }

    private val Float.d: Double get() = toDouble()

    /** Distance from [p] to the outline of the rounded rectangle (x, y, w, h, corner radius r). */
    fun distanceToRoundRect(p: Pt, x: Double, y: Double, w: Double, h: Double, r: Double): Double {
        // Signed distance of a rounded box, measured from its centre.
        val qx = abs(p.x - (x + w / 2)) - (w / 2 - r)
        val qy = abs(p.y - (y + h / 2)) - (h / 2 - r)
        val outside = sqrt(max(qx, 0.0) * max(qx, 0.0) + max(qy, 0.0) * max(qy, 0.0))
        return abs(outside + min(max(qx, qy), 0.0) - r)
    }

    /** How far [p] is from the ellipse (cx, cy, rx, ry), in units of the smaller radius (0 = on it). */
    fun ellipseError(p: Pt, cx: Double, cy: Double, rx: Double, ry: Double): Double {
        val k = sqrt(((p.x - cx) / rx).let { it * it } + ((p.y - cy) / ry).let { it * it })
        return abs(k - 1) * min(rx, ry)
    }
}
