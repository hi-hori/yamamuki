package io.github.shohei0205.yamamuki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
private val BinocularBody = Color(0xFF333333)
private val BinocularHinge = Color(0xFF777777)
private val LensBlue = Color(0xFF5B8DB8)

/** 画面上部の方位目盛りに収める角度の幅。 */
private const val TAPE_SPAN_DEG = 60.0

/** 一度に表示する山の上限。 */
private const val MAX_PEAKS = 40

private val LabelStyle = TextStyle(color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
private val RingLabelStyle = TextStyle(color = RingGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
private val ReadoutStyle = TextStyle(color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)

/**
 * 方位盤。現在地(画面下部の双眼鏡)から向いている方向を上にとり、山を ▲ と「山名 (標高)」で描く。
 * [mountains] は表示の優先順(標高の高い順)に並んでいること。重なる山は優先度の低いほうを省く。
 * 描いた山(▲ か山名)をタップすると [onMountainTap] を呼ぶ。
 */
@Composable
fun DialCanvas(
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    rangeKm: Double,
    modifier: Modifier = Modifier,
    onMountainTap: (NearbyMountain) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val hitTargets = remember { HitTargets() }
    val currentOnTap by rememberUpdatedState(onMountainTap)
    val tapModifier = Modifier.pointerInput(Unit) {
        val slop = 8.dp.toPx()
        detectTapGestures { tap -> hitTargets.find(tap, slop)?.let(currentOnTap) }
    }
    Canvas(modifier.clipToBounds().then(tapModifier)) {
        val tapeHeight = 44.dp.toPx()
        val chartTop = tapeHeight + 32.dp.toPx()
        // 双眼鏡が右下の「© OpenStreetMap contributors」と重ならない高さ。
        val observer = Offset(size.width / 2, size.height - 52.dp.toPx())
        val pxPerKm = ((observer.y - chartTop) / rangeKm).toFloat()
        hitTargets.peaks = if (pxPerKm > 0f) {
            drawRings(observer, pxPerKm, rangeKm, chartTop, textMeasurer)
            drawPeaks(observer, pxPerKm, headingDeg, mountains, chartTop, textMeasurer)
        } else {
            emptyList()
        }
        drawBinoculars(observer)
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

private class PlacedPeak(
    val mountain: NearbyMountain,
    val position: Offset,
    val label: TextLayoutResult,
    /** ▲ と山名を合わせた範囲。重なりの判定とタップの当たり判定に使う。 */
    val box: Box,
)

/** 直近に描いた山。描画のたびに差し替え、タップ位置から山を引く。 */
private class HitTargets {
    var peaks: List<PlacedPeak> = emptyList()

    /** [tap] を含む山のうち、▲ が最も近いもの。枠を [slop] だけ広げて判定する。 */
    fun find(tap: Offset, slop: Float): NearbyMountain? = peaks
        .filter { with(it.box) { tap.x in left - slop..right + slop && tap.y in top - slop..bottom + slop } }
        .minByOrNull { (it.position - tap).getDistanceSquared() }
        ?.mountain
}

private fun DrawScope.drawPeaks(
    observer: Offset,
    pxPerKm: Float,
    headingDeg: Double,
    mountains: List<NearbyMountain>,
    chartTop: Float,
    textMeasurer: TextMeasurer,
): List<PlacedPeak> {
    val halfWidth = 11.dp.toPx()
    val height = 18.dp.toPx()
    val gap = 2.dp.toPx()

    val visible = mountains.asSequence()
        .map { m ->
            val o = DialGeometry.project(m.distanceKm, m.bearingDeg, headingDeg)
            m to Offset(observer.x + (o.x * pxPerKm).toFloat(), observer.y - (o.y * pxPerKm).toFloat())
        }
        .filter { (_, p) -> p.x in 0f..size.width && p.y - height >= chartTop && p.y < observer.y }
        .map { (m, p) ->
            val label = textMeasurer.measure(m.mountain.displayLabel(), LabelStyle)
            val labelHalf = label.size.width / 2f
            val box = Box(
                left = min(p.x - halfWidth, p.x - labelHalf),
                top = p.y - height,
                right = max(p.x + halfWidth, p.x + labelHalf),
                bottom = p.y + gap + label.size.height,
            )
            PlacedPeak(m, p, label, box)
        }
        .toList()

    val placed = declutter(visible, limit = MAX_PEAKS) { it.box }

    for (peak in placed) {
        val p = peak.position
        drawTriangle(p, halfWidth, height, PeakGreen)
        drawTriangle(Offset(p.x, p.y - height * 0.14f), halfWidth * 0.45f, height * 0.45f, PeakYellow)
        drawText(peak.label, topLeft = Offset(p.x - peak.label.size.width / 2f, p.y + gap))
    }
    return placed
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

/**
 * 現在地を表す双眼鏡。対物レンズを上(向いている方位)に向け、前方へ広がる視野を薄く描いて
 * 「前を覗いている」ように見せる。同心円や山と重なっても埋もれないよう、白い縁取りを付ける。
 */
private fun DrawScope.drawBinoculars(center: Offset) {
    val u = 1.dp.toPx()

    /** 中心からのずれ(dp)で矩形を描く。[grow] だけ四方に広げる。 */
    fun part(x: Float, top: Float, width: Float, bottom: Float, corner: Float, color: Color, grow: Float = 0f) {
        drawRoundRect(
            color,
            topLeft = Offset(center.x + (x - width / 2 - grow) * u, center.y + (top - grow) * u),
            size = Size((width + grow * 2) * u, (bottom - top + grow * 2) * u),
            cornerRadius = CornerRadius((corner + grow) * u),
        )
    }

    fun body(color: Color, grow: Float) {
        for (side in listOf(-1f, 1f)) {
            val x = side * 10f
            part(x, top = -13f, width = 15f, bottom = 3f, corner = 5f, color = color, grow = grow) // 対物部
            part(x, top = 1f, width = 9f, bottom = 12f, corner = 3f, color = color, grow = grow) // 接眼部
        }
        part(0f, top = -1f, width = 8f, bottom = 6f, corner = 2f, color = color, grow = grow) // ブリッジ
    }

    // 視野: 対物レンズの先から前方へ扇形に広がり、遠くほど薄くなる。
    val apex = Offset(center.x, center.y - 10f * u)
    val reach = 70f * u
    val halfAngle = 22f
    drawArc(
        brush = Brush.radialGradient(
            colors = listOf(LensBlue.copy(alpha = 0.35f), LensBlue.copy(alpha = 0f)),
            center = apex,
            radius = reach,
        ),
        startAngle = -90f - halfAngle,
        sweepAngle = halfAngle * 2,
        useCenter = true,
        topLeft = Offset(apex.x - reach, apex.y - reach),
        size = Size(reach * 2, reach * 2),
    )

    body(Color.White, grow = 2f)
    body(BinocularBody, grow = 0f)
    drawCircle(BinocularHinge, radius = 3f * u, center = Offset(center.x, center.y + 2.5f * u))
    for (side in listOf(-1f, 1f)) {
        // 前を向いたレンズ面を斜め後ろから見た楕円。
        val lens = Offset(center.x + side * 10f * u, center.y - 10.5f * u)
        drawOval(LensBlue, topLeft = lens - Offset(5.5f * u, 2.5f * u), size = Size(11f * u, 5f * u))
        drawOval(
            Color.White.copy(alpha = 0.8f),
            topLeft = lens + Offset(-3.5f * u, -1.5f * u),
            size = Size(3f * u, 1.4f * u),
        )
    }
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
