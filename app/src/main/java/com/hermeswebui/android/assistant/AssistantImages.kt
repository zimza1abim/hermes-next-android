package com.hermeswebui.android.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

/** A photo ready to upload: a re-encoded JPEG (no EXIF, so no location) and a small preview. */
internal class PreparedImage(val base64: String, val filename: String, val thumbnail: ImageBitmap)

internal object AssistantImages {
    /** The long side the model gets; enough to read text in a photo, small enough for one socket frame. */
    private const val MAX_SIDE = 1600
    private const val THUMB_SIDE = 256
    private const val QUALITY = 85

    fun prepare(context: Context, uri: Uri): PreparedImage {
        val bitmap = decode(context, uri, MAX_SIDE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val thumb = scale(bitmap, THUMB_SIDE)
        if (thumb !== bitmap) bitmap.recycle()
        return PreparedImage(
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            "photo-${System.currentTimeMillis()}.jpg",
            thumb.asImageBitmap(),
        )
    }

    /** Decodes already shrunk; ImageDecoder also applies the EXIF rotation. */
    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val factor = maxOf(w, h).toFloat() / maxSide
                if (factor > 1f) decoder.setTargetSize((w / factor).toInt().coerceAtLeast(1), (h / factor).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Unreadable image")
        return scale(decoded, maxSide)
    }

    private fun scale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val factor = maxOf(bitmap.width, bitmap.height).toFloat() / maxSide
        if (factor <= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width / factor).toInt().coerceAtLeast(1), (bitmap.height / factor).toInt().coerceAtLeast(1), true)
    }
}
