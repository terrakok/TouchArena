package org.company.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.time.Clock

// --- Data Models ---
data class PointData(val t: Long, val y: Float)

data class LsqResult(
    val c: Float, // Constant term
    val b: Float, // Linear term
    val a: Float, // Quadratic term
    val t0: Long   // Time offset for normalization
)

// --- Math Logic ---
object MathUtils {
    fun solveLSQ2(points: List<PointData>): LsqResult? {
        val n = points.size
        if (n < 3) return null

        val t0 = points[0].t
        // Normalize time to avoid huge numbers in matrix
        val normT = points.map { (it.t - t0).toFloat() }
        val y = points.map { it.y }

        var s1 = 0.0f; var s2 = 0.0f; var s3 = 0.0f; var s4 = 0.0f
        var sy = 0.0f; var sty = 0.0f; var st2y = 0.0f

        for (i in 0 until n) {
            val ti = normT[i]
            val yi = y[i]
            val ti2 = ti * ti

            s1 += ti
            s2 += ti2
            s3 += ti2 * ti
            s4 += ti2 * ti2
            sy += yi
            sty += ti * yi
            st2y += ti2 * yi
        }

        // Matrix 3x3
        // | n  s1 s2 | | c |   | sy   |
        // | s1 s2 s3 | | b | = | sty  |
        // | s2 s3 s4 | | a |   | st2y |

        val m = arrayOf(
            arrayOf(n.toFloat(), s1, s2),
            arrayOf(s1, s2, s3),
            arrayOf(s2, s3, s4)
        )
        val rhs = arrayOf(sy, sty, st2y)

        val det = { mat: Array<Array<Float>> ->
            mat[0][0] * (mat[1][1] * mat[2][2] - mat[1][2] * mat[2][1]) -
                    mat[0][1] * (mat[1][0] * mat[2][2] - mat[1][2] * mat[2][0]) +
                    mat[0][2] * (mat[1][0] * mat[2][1] - mat[1][1] * mat[2][0])
        }

        val D = det(m)
        if (abs(D) < 1e-12) return null

        fun cloneArray(arr: Array<Float>) = Array(arr.size) { arr[it] }

        fun solve(idx: Int): Float {
            // Clone matrix and replace column 'idx' with rhs
            val temp = Array(3) { r -> cloneArray(m[r]) }
            for (i in 0 until 3) temp[i][idx] = rhs[i]
            return det(temp) / D
        }

        return LsqResult(solve(0), solve(1), solve(2), t0)
    }
}

// --- Composable UI ---
@Composable
fun SplitScreenTracker() {
    // State
    val points = remember { mutableStateListOf<PointData>() }

    val lsqResult = remember(points.size, points.lastOrNull()) {
        MathUtils.solveLSQ2(points)
    }

    val systemVelocity = remember(points.size, points.lastOrNull()) {
        val tracker = VelocityTracker()
        points.forEach { tracker.addPosition(it.t, Offset(0f, it.y)) }
        tracker.calculateVelocity()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF0F2F5))) {

        // --- Top Half: Chart ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .padding(10.dp)
        ) {
            if (points.isNotEmpty()) {
                MotionChart(points, lsqResult)
            } else {
                Text("No data", Modifier.align(Alignment.Center))
            }
        }

        // --- Bottom Half: Touch Area ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            TouchPad(
                onDrawStart = { points.clear() },
                onDrawMove = { t, y ->
                    points.add(PointData(t, y))
                    if (points.size > 20) points.removeAt(0)
                }
            )

            // Stats Overlay
            StatsOverlay(lsqResult, systemVelocity, points.lastOrNull())
        }
    }
}

@Composable
fun StatsOverlay(
    result: LsqResult?,
    systemVelocity: Velocity,
    lastPoint: PointData?
) {
    val statsText = remember(result, lastPoint) {
        if (result != null && lastPoint != null) {
            val relT = lastPoint.t - result.t0
            // v = 2at + b
            val velocity = (2 * result.a * relT + result.b) * 1000
            // acc = 2a
            val accel = 2 * result.a

            "Velocity: $velocity px/s\nSystem velocity: ${systemVelocity.y} px/s\nAccel: $accel px/ms²"
        } else {
            "Velocity: 0 px/ms\nAccel: 0 px/ms²"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Text(
            text = statsText,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .background(Color(0xBB000000), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )
    }
}

@Composable
fun TouchPad(onDrawStart: () -> Unit, onDrawMove: (Long, Float) -> Unit) {
    var isDrawing by remember { mutableStateOf(false) }
    var startTime by remember { mutableLongStateOf(0L) }

    val borderColor = if (isDrawing) Color(0xFF2ECC71) else Color(0xFF3498DB)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        // The dotted border area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(3.dp, borderColor, RoundedCornerShape(20.dp))
                // Touch logic
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDrawing = true
                            startTime = Clock.System.now().toEpochMilliseconds()
                            onDrawStart()
                            onDrawMove(0, offset.y)
                        },
                        onDragEnd = { isDrawing = false },
                        onDragCancel = { isDrawing = false },
                        onDrag = { change, _ ->
                            val t = Clock.System.now().toEpochMilliseconds() - startTime
                            // Use raw Y relative to the touch area
                            onDrawMove(t, change.position.y)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "DRAW GESTURE HERE",
                color = Color(0xFF3498DB),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MotionChart(points: List<PointData>, result: LsqResult?) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (points.isEmpty()) return@Canvas

        val padding = 40f
        val width = size.width - padding * 2
        val height = size.height - padding * 2

        // Determine bounds for auto-scaling
        val minT = points.minOf { it.t }
        val maxT = (points.maxOf { it.t }).coerceAtLeast(minT + 100) // Ensure some width

        val minYVal = points.minOf { it.y }
        val maxYVal = points.maxOf { it.y }
        // Add some buffer to Y
        val rangeY = (maxYVal - minYVal).coerceAtLeast(10f)
        val viewMinY = minYVal - rangeY * 0.1f
        val viewMaxY = maxYVal + rangeY * 0.1f

        // Helpers to map data to screen coordinates
        fun mapX(t: Long): Float {
            val ratio = (t - minT).toFloat() / (maxT - minT)
            return padding + ratio * width
        }

        // Note: Chart Y usually goes UP, but screen coords go DOWN.
        // We replicate the JS logic: higher pixel value = lower on screen visually in input,
        // but let's graph it traditionally: top of graph is Min Y (or Max Y depending on preference).
        // Since input is screen coordinates (0 at top), let's keep 0 at top for the chart
        // to match the physical motion 1:1 visually.
        fun mapY(y: Float): Float {
            val ratio = (y - viewMinY) / (viewMaxY - viewMinY)
            return padding + ratio * height
        }

        // 1. Draw Grid/Axes (Simplified)
        drawLine(Color.LightGray, Offset(padding, padding), Offset(padding, size.height - padding)) // Y Axis
        drawLine(Color.LightGray, Offset(padding, size.height - padding), Offset(size.width - padding, size.height - padding)) // X Axis

        // 2. Draw Raw Points
        val pointColor = Color(0xFF2C3E50)
        points.forEach { p ->
            drawCircle(
                color = pointColor,
                radius = 5.dp.toPx(),
                center = Offset(mapX(p.t), mapY(p.y))
            )
        }

        // 3. Draw Fitted Curve & Tangent
        if (result != null) {
            // Draw Curve
            val path = Path()
            val steps = 100
            val dt = (maxT - minT) / steps

            for (i in 0..steps) {
                val tVal = minT + i * dt
                val relT = (tVal - result.t0).toDouble()
                val yVal = (result.a * relT * relT + result.b * relT + result.c).toFloat()

                val sx = mapX(tVal)
                val sy = mapY(yVal)

                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }

            drawPath(
                path = path,
                color = Color(0xFF3498DB),
                style = Stroke(width = 4.dp.toPx())
            )

            // Draw Tangent (Velocity Vector) at last point
            val lastT = points.last().t
            val relLastT = (lastT - result.t0).toDouble()

            // Calculate Y and Velocity at last point
            val lastYCalc = (result.a * relLastT * relLastT + result.b * relLastT + result.c).toFloat()
            val velocity = (2 * result.a * relLastT + result.b).toFloat()

            // Project into future for vector drawing
            val futureT = lastT + 150 // 150ms projection
            val futureY = lastYCalc + velocity * 150

            val startVec = Offset(mapX(lastT), mapY(lastYCalc))
            val endVec = Offset(mapX(futureT), mapY(futureY))

            drawLine(
                color = Color(0xFFE74C3C),
                start = startVec,
                end = endVec,
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        }
    }
}