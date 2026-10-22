package dev.omp.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImageAttachmentsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun downsamplingKeepsAspectAndTransparencyDoesNotBecomeBlack() {
        val file = File(context.cacheDir, "image-${UUID.randomUUID()}.png")
        val source = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.TRANSPARENT)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val image = preparePromptImage(context, Uri.fromFile(file))
            val bytes = Base64.getDecoder().decode(image.base64)
            val sent = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals("image/jpeg", image.mimeType)
                assertEquals(1600, sent.width)
                assertEquals(800, sent.height)
                assertTrue(Color.red(sent.getPixel(10, 10)) > 245)
                assertTrue(Color.green(sent.getPixel(10, 10)) > 245)
                assertTrue(Color.blue(sent.getPixel(10, 10)) > 245)
                assertEquals(240, image.thumbnail.width)
                assertEquals(120, image.thumbnail.height)
                assertFalse(image.thumbnail.isRecycled)
                assertFalse(image.toString().contains(image.base64))
            } finally {
                sent.recycle()
                image.thumbnail.recycle()
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun jpegRotationIsAppliedToUploadAndPreview() {
        val file = File(context.cacheDir, "rotated-${UUID.randomUUID()}.jpg")
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val image = preparePromptImage(context, Uri.fromFile(file))
            val bytes = Base64.getDecoder().decode(image.base64)
            val sent = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(40, sent.width)
                assertEquals(80, sent.height)
                assertEquals(40, image.thumbnail.width)
                assertEquals(80, image.thumbnail.height)
                assertTrue(Color.red(sent.getPixel(20, 40)) > 240)
                assertTrue(Color.green(sent.getPixel(20, 40)) < 20)
                assertFalse(image.thumbnail.isRecycled)
            } finally {
                sent.recycle()
                image.thumbnail.recycle()
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun corruptImageIsRejectedInsteadOfSendingPlaceholderContent() {
        val file = File(context.cacheDir, "invalid-${UUID.randomUUID()}.jpg")
        try {
            file.writeText("this is not an image")
            try {
                preparePromptImage(context, Uri.fromFile(file))
                throw AssertionError("Corrupt file must not become an attachment")
            } catch (_: IOException) {
                // Expected: the draft must not contain a fake image.
            }
        } finally {
            file.delete()
        }
    }
}
