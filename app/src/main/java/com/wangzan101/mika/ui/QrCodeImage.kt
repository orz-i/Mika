package com.wangzan101.mika.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaTheme

/** 本机生成的二维码矩阵，包含四周静区。 */
internal data class QrMatrix(val size: Int, private val cells: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = cells[y * size + x]
}

internal fun encodeQr(content: String): QrMatrix {
    require(content.isNotBlank()) { "QR content is empty" }
    val matrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, 1, 1,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        ),
    )
    require(matrix.width == matrix.height) { "QR matrix is not square" }
    return QrMatrix(matrix.width, BooleanArray(matrix.width * matrix.height) { i ->
        matrix.get(i % matrix.width, i / matrix.width)
    })
}

private val QrFrameShape = RoundedCornerShape(20.dp)
private val QrInnerShape = RoundedCornerShape(12.dp)

/** 按设备像素对齐模块边缘，避免缩放后在模块之间出现灰线；外框使用磨砂玻璃衬托，内区保持纯白静区与纯黑码块高对比可扫。 */
@Composable
internal fun QrCodeImage(content: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { encodeQr(content) }
    val c = MikaTheme.colors
    Box(
        modifier
            .cardSurface(c, QrFrameShape, elevated = true)
            .clip(QrFrameShape)
            .padding(10.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize().clip(QrInnerShape)) {
            val module = size.minDimension / matrix.size
            drawRect(Color.White)
            for (y in 0 until matrix.size) {
                var x = 0
                while (x < matrix.size) {
                    if (!matrix[x, y]) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < matrix.size && matrix[x, y]) x++
                    drawRect(
                        Color.Black,
                        Offset((start * module).toInt().toFloat(), (y * module).toInt().toFloat()),
                        Size((x * module).toInt() - (start * module).toInt().toFloat(),
                            ((y + 1) * module).toInt() - (y * module).toInt().toFloat()),
                    )
                }
            }
        }
    }
}
