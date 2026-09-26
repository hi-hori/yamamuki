package io.github.shohei0205.yamamuki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shohei0205.yamamuki.core.Box
import io.github.shohei0205.yamamuki.core.DialGeometry
import io.github.shohei0205.yamamuki.core.Heading
import io.github.shohei0205.yamamuki.core.Mountain
import io.github.shohei0205.yamamuki.core.NearbyMountain
import io.github.shohei0205.yamamuki.core.declutter
import io.github.shohei0205.yamamuki.core.displayLabel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val DialBeige = Color(0xFFEFE4B0)
private val RingGray = Color(0xFFC3C3C3)
private val PeakGreen = Color(0xFF22B14C)
private val PeakYellow = Color(0xFFB5E61D)
private val NorthRed = Color(0xFFED1C24)

/** 画面上部の方位目盛りに収める角度の幅。 */
private const val TAPE_SPAN_DEG = 60.0

/** 一度に表示する山の上限。 */
private const val MAX_PEAKS = 40

private val LabelStyle = TextStyle(color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
private val RingLabelStyle = TextStyle(color = RingGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
private val ReadoutStyle = TextStyle(color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)

/**
 * 方位盤。現在地(画面下部の黒丸)から向いている方向を上にとり、山を ▲ と「山名 (標高)」で描く。
 * [mountains] は表示の優先順(標高の高い順)に並んでいること。重なる山は優先度の低いほうを省く。
 */
@Composable
fun DialCanvas(
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    rangeKm: Double,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    Canvas(modifier.clipToBounds()) {
        val tapeHeight = 44.dp.toPx()
        val chartTop = tapeHeight + 32.dp.toPx()
        val observer = Offset(size.width / 2, size.height - 40.dp.toPx())
        val pxPerKm = ((observer.y - chartTop) / rangeKm).toFloat()
        if (pxPerKm > 0f) {
            drawRings(observer, pxPerKm, rangeKm, chartTop, textMeasurer)
            drawPeaks(observer, pxPerKm, headingDeg, mountains, chartTop, textMeasurer)
        }
        drawCircle(Color.Black, radius = 14.dp.toPx(), center = observer)
        drawTape(headingDeg, tapeHeight, textMeasurer)
        drawReadout(headingDeg, tapeHeight, textMeasurer)
    }
}

private fun DrawScope.drawRings(
    observer: Offset,
    pxPerKm: Float,
    rangeKm: Double,
    chartTop: Float,
    textMeasurer: TextMeasurer,
) {
    val step = DialGeometry.ringStepKm(rangeKm)
    val farthestPx = hypot(size.width / 2, observer.y)
    var i = 1
    while (step * i * pxPerKm <= farthestPx) {
        val km = step * i
        val radius = (km * pxPerKm).toFloat()
        drawCircle(RingGray, radius = radius, center = observer, style = Stroke(width = 3.dp.toPx()))
        val label = textMeasurer.measure(DialGeometry.ringLabel(km), RingLabelStyle)
        val y = observer.y - radius - label.size.height - 2.dp.toPx()
        if (y >= chartTop) drawText(label, topLeft = Offset(observer.x - label.size.width / 2f, y))
        i++
    }
}

private class PlacedPeak(val position: Offset, val label: TextLayoutResult)

private fun DrawScope.drawPeaks(
    observer: Offset,
    pxPerKm: Float,
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    chartTop: Float,
    textMeasurer: TextMeasurer,
) {
    val halfWidth = 11.dp.toPx()
    val height = 18.dp.toPx()
    val gap = 2.dp.toPx()

    val visible = mountains.asSequence()
        .map { m ->
            val o = DialGeometry.project(m.distanceKm, m.bearingDeg, headingDeg)
            m to Offset(observer.x + (o.x * pxPerKm).toFloat(), observer.y - (o.y * pxPerKm).toFloat())
        }
        .filter { (_, p) -> p.x in 0f..size.width && p.y - height >= chartTop && p.y < observer.y }
        .map { (m, p) -> PlacedPeak(p, textMeasurer.measure(m.mountain.displayLabel(), LabelStyle)) }
        .toList()

    val placed = declutter(visible, limit = MAX_PEAKS) { peak ->
        val p = peak.position
        val labelHalf = peak.label.size.width / 2f
        Box(
            left = min(p.x - halfWidth, p.x - labelHalf),
            top = p.y - height,
            right = max(p.x + halfWidth, p.x + labelHalf),
            bottom = p.y + gap + peak.label.size.height,
        )
    }

    for (peak in placed) {
        val p = peak.position
        drawTriangle(p, halfWidth, height, PeakGreen)
        drawTriangle(Offset(p.x, p.y - height * 0.14f), halfWidth * 0.45f, height * 0.45f, PeakYellow)
        drawText(peak.label, topLeft = Offset(p.x - peak.label.size.width / 2f, p.y + gap))
    }
}

/** 底辺の中点を [bottomCenter] とする二等辺三角形。 */
private fun DrawScope.drawTriangle(bottomCenter: Offset, halfWidth: Float, height: Float, color: Color) {
    val path = Path().apply {
        moveTo(bottomCenter.x, bottomCenter.y - height)
        lineTo(bottomCenter.x + halfWidth, bottomCenter.y)
        lineTo(bottomCenter.x - halfWidth, bottomCenter.y)
        close()
    }
    drawPath(path, color)
}

/** 画面上部の方位目盛り。向いている方位が中央に来る。 */
private fun DrawScope.drawTape(headingDeg: Double, tapeHeight: Float, textMeasurer: TextMeasurer) {
    val center = size.width / 2
    val stroke = 3.dp.toPx()
    for (tick in DialGeometry.tapeTicks(headingDeg, TAPE_SPAN_DEG)) {
        val x = center + (tick.offsetDeg / TAPE_SPAN_DEG * size.width).toFloat()
        val cardinal = DialGeometry.cardinalLabel(tick.angleDeg)
        if (cardinal != null) {
            val style = TextStyle(
                color = if (cardinal == "N") NorthRed else Color.Black,
                fontSize = if (cardinal.length == 1) 22.sp else 15.sp,
                fontWeight = FontWeight.Bold,
            )
            val label = textMeasurer.measure(cardinal, style)
            drawText(label, topLeft = Offset(x - label.size.width / 2f, (tapeHeight - label.size.height) / 2))
        } else {
            val length = if (tick.angleDeg % 10 == 0) tapeHeight * 0.75f else tapeHeight * 0.45f
            drawLine(Color.Black, Offset(x, 0f), Offset(x, length), strokeWidth = stroke)
        }
    }
}

/** 目盛りの下に、中央を指す赤い印と「北東 45°」の表示。 */
private fun DrawScope.drawReadout(headingDeg: Double, tapeHeight: Float, textMeasurer: TextMeasurer) {
    val center = size.width / 2
    val caret = 6.dp.toPx()
    val path = Path().apply {
        moveTo(center, tapeHeight)
        lineTo(center + caret, tapeHeight + caret)
        lineTo(center - caret, tapeHeight + caret)
        close()
    }
    drawPath(path, NorthRed)
    val deg = headingDeg.roundToInt() % 360
    val label = textMeasurer.measure("${Heading.directionName(headingDeg)} $deg°", ReadoutStyle)
    drawText(label, topLeft = Offset(center - label.size.width / 2f, tapeHeight + caret + 2.dp.toPx()))
}

@Preview(widthDp = 320, heightDp = 560)
@Composable
private fun DialCanvasPreview() {
    fun peak(name: String, ele: Double, km: Double, bearing: Double) =
        NearbyMountain(Mountain(name.hashCode().toLong(), name, 0.0, 0.0, ele), km, bearing)
    DialCanvas(
        headingDeg = 0.0,
        mountains = listOf(
            peak("□□山", 1212.0, 8.0, 20.0),
            peak("○○山", 560.0, 12.5, -18.0),
            peak("○×山", 122.0, 3.5, -20.0),
        ),
        rangeKm = DialGeometry.DEFAULT_RANGE_KM,
        modifier = Modifier.fillMaxSize().background(DialBeige),
    )
}
