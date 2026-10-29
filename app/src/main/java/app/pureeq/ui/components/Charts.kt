package app.pureeq.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pureeq.dsp.FreqGrid
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

class ChartCurve(val db: DoubleArray, val color: Color, val fill: Boolean = false, val dashed: Boolean = false)

private val GRID_FREQS = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
private fun freqText(f: Double) = if (f >= 1000) "${(f / 1000).toInt()}k" else f.toInt().toString()

/** Log-frequency EQ chart (20 Hz to 20 kHz) with a dB grid that adapts to the curves. */
@Composable
fun EqChart(curves: List<ChartCurve>, modifier: Modifier = Modifier, height: Dp = 200.dp, compact: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    Canvas(modifier.fillMaxWidth().height(height)) {
        var lo = curves.minOfOrNull { it.db.min() } ?: -6.0
        var hi = curves.maxOfOrNull { it.db.max() } ?: 6.0
        val span = max(12.0, hi - lo + 4.0)
        val mid = (hi + lo) / 2
        lo = floor((mid - span / 2) / 3) * 3
        hi = ceil((mid + span / 2) / 3) * 3
        val left = if (compact) 0f else 30.dp.toPx()
        val bottom = if (compact) 0f else 16.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        fun x(f: Double) = left + (log2(f / FreqGrid.MIN_HZ) / log2(FreqGrid.MAX_HZ / FreqGrid.MIN_HZ)).toFloat() * w
        fun y(db: Double) = ((hi - db) / (hi - lo)).toFloat() * h

        for (f in GRID_FREQS) {
            drawLine(grid, Offset(x(f), 0f), Offset(x(f), h), 1f)
            if (!compact) {
                val t = measurer.measure(freqText(f), labelStyle)
                drawText(t, topLeft = Offset(x(f) - t.size.width / 2f, h + 2.dp.toPx()))
            }
        }
        val step = if (hi - lo > 30) 6.0 else 3.0
        var d = lo
        while (d <= hi + 1e-9) {
            val isZero = kotlin.math.abs(d) < 1e-9
            drawLine(if (isZero) zero else grid, Offset(left, y(d)), Offset(size.width, y(d)), if (isZero) 1.5f else 1f)
            if (!compact) {
                val t = measurer.measure((if (d > 0) "+" else "") + d.toInt().toString(), labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(d) - t.size.height / 2f))
            }
            d += step
        }
        for (c in curves) drawCurve(c, ::x, ::y, y(0.0), if (compact) 2.dp.toPx() else 2.5.dp.toPx())
    }
}

private fun DrawScope.drawCurve(c: ChartCurve, x: (Double) -> Float, y: (Double) -> Float, zeroY: Float, width: Float) {
    val path = Path()
    for (i in c.db.indices) {
        val px = x(FreqGrid.hz[i])
        val py = y(c.db[i])
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    if (c.fill) {
        val area = Path().apply {
            addPath(path)
            lineTo(x(FreqGrid.MAX_HZ), zeroY)
            lineTo(x(FreqGrid.MIN_HZ), zeroY)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(c.color.copy(alpha = 0.28f), c.color.copy(alpha = 0.02f))))
    }
    drawPath(
        path, c.color,
        style = Stroke(
            width = width, cap = StrokeCap.Round, join = StrokeJoin.Round,
            pathEffect = if (c.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
        ),
    )
}

class HearingSeries(val points: List<Pair<Double, Double>>, val color: Color, val cross: Boolean)

/**
 * Audiogram-style view of the relative hearing levels: better hearing higher up. Left ear uses
 * crosses and right ear circles, as on a clinical audiogram.
 */
@Composable
fun HearingChart(series: List<HearingSeries>, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = label)
    val freqs = series.flatMap { s -> s.points.map { it.first } }.distinct().sorted()
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (freqs.isEmpty()) return@Canvas
        val all = series.flatMap { s -> s.points.map { it.second } }
        val lo = min(-5.0, floor(all.min() / 5) * 5 - 5)
        val hi = max(25.0, ceil(all.max() / 5) * 5 + 5)
        val left = 30.dp.toPx()
        val bottom = 16.dp.toPx()
        val pad = 14.dp.toPx()
        val w = size.width - left - 2 * pad
        val h = size.height - bottom
        val fLo = log2(freqs.first())
        val fHi = log2(freqs.last())
        fun x(f: Double) = left + pad + if (fHi == fLo) w / 2 else ((log2(f) - fLo) / (fHi - fLo)).toFloat() * w
        // Worse hearing (higher relative level) goes down, like an audiogram.
        fun y(v: Double) = ((v - lo) / (hi - lo)).toFloat() * h
        var v = ceil(lo / 10) * 10
        while (v <= hi) {
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            val t = measurer.measure(v.toInt().toString(), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(v) - t.size.height / 2f))
            v += 10
        }
        for (f in freqs) {
            drawLine(grid, Offset(x(f), 0f), Offset(x(f), h), 1f)
            val t = measurer.measure(freqText(f), labelStyle)
            drawText(t, topLeft = Offset(x(f) - t.size.width / 2f, h + 2.dp.toPx()))
        }
        val r = 5.dp.toPx()
        for (s in series) {
            val pts = s.points.sortedBy { it.first }.map { Offset(x(it.first), y(it.second)) }
            for (i in 1 until pts.size) drawLine(s.color.copy(alpha = 0.7f), pts[i - 1], pts[i], 2.dp.toPx(), StrokeCap.Round)
            for (p in pts) {
                if (s.cross) {
                    drawLine(s.color, p + Offset(-r, -r), p + Offset(r, r), 2.5.dp.toPx(), StrokeCap.Round)
                    drawLine(s.color, p + Offset(-r, r), p + Offset(r, -r), 2.5.dp.toPx(), StrokeCap.Round)
                } else {
                    drawCircle(s.color, r, p, style = Stroke(2.5.dp.toPx()))
                }
            }
        }
    }
}
