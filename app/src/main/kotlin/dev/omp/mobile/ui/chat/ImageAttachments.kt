package dev.omp.mobile.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

internal const val MAX_PROMPT_IMAGES = 4
internal const val MAX_PROMPT_IMAGE_BASE64 = 8_000_000
private const val MAX_IMAGE_DIMENSION = 1600
private const val MAX_IMAGE_BYTES = 2_000_000

/** In-memory draft only; never log image content or put it in saved instance state. */
data class PendingImage(
    val id: String,
    val name: String,
    val mimeType: String,
    val base64: String,
    val thumbnail: Bitmap,
) {
    override fun toString(): String = "PendingImage(id=$id, mimeType=$mimeType, data=<redacted>)"
}

/** Runs on IO. Reopens the URI for bounds, EXIF and sampled decode; never buffers the original file. */
internal fun preparePromptImage(context: Context, uri: Uri): PendingImage {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: "Image"
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit }
        ?: throw IOException("Image cannot be opened")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")
    var sample = 1
    while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION || bounds.outHeight / sample > MAX_IMAGE_DIMENSION) {
        sample *= 2
    }
    val orientation = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        // Formats without EXIF still decode normally.
        ExifInterface.ORIENTATION_NORMAL
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IOException("Image cannot be decoded")
    var bitmap = decoded
    var retainBitmap = false
    try {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (!matrix.isIdentity) {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            bitmap = oriented
        }
        if (bitmap.hasAlpha()) {
            val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            bitmap.recycle()
            bitmap = opaque
        }
        val output = ByteArrayOutputStream()
        var quality = 82
        do {
            output.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IOException("Image cannot be encoded")
            quality -= 10
        } while (output.size() > MAX_IMAGE_BYTES && quality >= 42)
        if (output.size() > MAX_IMAGE_BYTES) throw IOException("Image exceeds size limit")
        val scale = minOf(1f, 240f / maxOf(bitmap.width, bitmap.height))
        val thumbnail = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val image = PendingImage(
            UUID.randomUUID().toString(), name, "image/jpeg",
            Base64.getEncoder().encodeToString(output.toByteArray()), thumbnail,
        )
        // A small source may itself be the preview; leave that bitmap alive for Compose.
        retainBitmap = thumbnail === bitmap
        return image
    } finally {
        if (!retainBitmap) bitmap.recycle()
    }
}
