package app.p2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object InviteImage {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun make(invite: String): Bitmap? = runCatching {
        val code = InviteCode.compact(invite)
        // Ask ZXing for a one-pixel-per-module matrix, including the quiet zone.
        val writer = QRCodeWriter()
        val matrix = writer.encode(code, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4))
        val edge = matrix.width
        val pixels = IntArray(edge * edge) { index ->
            if (matrix[index % edge, index / edge]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        val modules = Bitmap.createBitmap(pixels, edge, edge, Bitmap.Config.ARGB_8888)
        modules.density = Bitmap.DENSITY_NONE
        modules
    }.getOrNull()

    fun read(context: Context, uri: Uri): String {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val ratio = (maxOf(width, height) / 2560.0).coerceAtLeast(1.0)
            decoder.setTargetSize((width / ratio).toInt().coerceAtLeast(1), (height / ratio).toInt().coerceAtLeast(1))
        }
        return try {
            readBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun readBitmap(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return readSource(RGBLuminanceSource(bitmap.width, bitmap.height, pixels), tryGlobal = true)
            ?: throw NotFoundException.getNotFoundInstance()
    }

    fun readSource(source: LuminanceSource, tryGlobal: Boolean): String? {
        val side = (minOf(source.width, source.height) * 0.8).toInt()
        val center = if (source.isCropSupported && side >= 80) {
            source.crop((source.width - side) / 2, (source.height - side) / 2, side, side)
        } else null
        for (candidate in listOfNotNull(center, source)) {
            decode(candidate, tryGlobal)?.let { return it }
        }
        return null
    }

    private fun decode(source: LuminanceSource, tryGlobal: Boolean): String? {
        val reader = QRCodeReader()
        for (candidate in listOf(source, source.invert())) {
            val local = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }.getOrNull()
            if (local != null) return local
            if (tryGlobal) {
                val global = runCatching { reader.decode(BinaryBitmap(GlobalHistogramBinarizer(candidate)), hints).text }.getOrNull()
                if (global != null) return global
            }
        }
        return null
    }
}
