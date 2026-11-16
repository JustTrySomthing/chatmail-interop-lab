package app.p2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

private const val PHOTO_DIMENSION = 192
private const val PHOTO_BYTES = 24_000

internal fun prepareProfilePhoto(context: Context, uri: Uri): String {
    val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val scale = (maxOf(info.size.width, info.size.height).toDouble() / PHOTO_DIMENSION).coerceAtLeast(1.0)
        decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1),
            (info.size.height / scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return try {
        val output = ByteArrayOutputStream()
        check(image.compress(Bitmap.CompressFormat.JPEG, 76, output) && output.size() <= PHOTO_BYTES) {
            "Couldn’t prepare this profile photo. Choose another image."
        }
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    } finally {
        image.recycle()
    }
}

internal fun checkedPhoto(encoded: String): ByteArray? {
    if (encoded.isEmpty() || encoded.length > PHOTO_BYTES * 4 / 3) return null
    val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: return null
    if (bytes.size > PHOTO_BYTES) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return bytes.takeIf {
        options.outMimeType == "image/jpeg" && options.outWidth in 1..PHOTO_DIMENSION &&
            options.outHeight in 1..PHOTO_DIMENSION
    }
}

internal fun validProfilePhoto(encoded: String) = encoded.isEmpty() || checkedPhoto(encoded) != null

internal fun decodeProfilePhoto(encoded: String): Bitmap? =
    checkedPhoto(encoded)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
