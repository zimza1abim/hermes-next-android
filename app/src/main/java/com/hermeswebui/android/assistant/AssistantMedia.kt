package com.hermeswebui.android.assistant

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Loads one image a message names and hands it back on the main thread; null when it cannot be read. */
internal typealias ImageLoader = (source: String, done: (ImageBitmap?) -> Unit) -> Unit

/** The screen's image loader; null where nothing can load images (previews). */
internal val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** Saves one image to the gallery and tells whether it worked; null where saving is not offered. */
internal typealias ImageSaver = (source: String, done: (Boolean) -> Unit) -> Unit

internal val LocalImageSaver = staticCompositionLocalOf<ImageSaver?> { null }

/**
 * Images inside a message's text, in the four forms the server and the bot write them: `@image:<path>`
 * lines (photos the user sent), `MEDIA:<path>` tokens (files the bot delivers), Markdown `![alt](src)`,
 * and inline `data:image/...;base64,...` text. Pure; no Android.
 */
internal object AssistantMedia {
    data class Extraction(val text: String, val sources: List<String>)

    private val IMAGE_LINE = Regex("^[ \\t]*@image:[ \\t]*(.+?)[ \\t]*$", RegexOption.MULTILINE)
    private val MARKDOWN_IMAGE = Regex("!\\[[^\\]\\n]*]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
    private val MEDIA_TOKEN = Regex("MEDIA:(\\S+?)(?=[.,;:!?)\\]]*(?:\\s|$))")
    private val DATA_URL = Regex("data:image/[\\w.+-]+;base64,[A-Za-z0-9+/=]{64,}")
    private val PLACEHOLDER = Regex("^[ \\t]*\\[(?:image|screenshot)][ \\t]*$\\n?", RegexOption.MULTILINE)
    private val BLANK_RUN = Regex("\\n{3,}")
    private val IMAGE_SUFFIXES = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

    /** The text with its images taken out, and the images in the order they appeared. */
    fun extract(text: String): Extraction {
        if ("@image:" !in text && "![" !in text && "MEDIA:" !in text && "data:image/" !in text) return Extraction(text, emptyList())
        val found = mutableListOf<Pair<IntRange, String>>()
        fun collect(regex: Regex, group: Int, keep: (String) -> Boolean = { true }) {
            regex.findAll(text).forEach { match ->
                // An image inside one already taken (a data URL inside a Markdown image) is not taken twice.
                if (found.any { (range, _) -> range.first <= match.range.last && match.range.first <= range.last }) return@forEach
                val source = clean(match.groups[group]?.value ?: return@forEach)
                if (source.isNotEmpty() && keep(source)) found += match.range to source
            }
        }
        collect(MARKDOWN_IMAGE, 1)
        collect(IMAGE_LINE, 1)
        collect(MEDIA_TOKEN, 1, ::isImagePath)
        collect(DATA_URL, 0)
        if (found.isEmpty()) return Extraction(text, emptyList())
        found.sortBy { it.first.first }
        val rest = StringBuilder(text)
        found.asReversed().forEach { (range, _) -> rest.delete(range.first, range.last + 1) }
        val cleaned = BLANK_RUN.replace(PLACEHOLDER.replace(rest, ""), "\n\n").trim()
        return Extraction(cleaned, found.map { it.second }.distinct())
    }

    private fun clean(value: String) = value.trim(' ', '\t', '"', '\'', '`', '<', '>')

    fun isImagePath(path: String): Boolean = path.substringBefore('?').substringAfterLast('.', "").lowercase() in IMAGE_SUFFIXES

    /** The bytes of a `data:` URL. */
    fun decodeDataUrl(url: String): ByteArray? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        return runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
    }

    /** A bitmap at most [side] pixels on its longer side, so a large photo does not hold its full size in memory. */
    fun picture(bytes: ByteArray, side: Int = 1600): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > side) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

/** The images of one message, as thumbnails; a tap opens one over the whole screen. */
@Composable
internal fun MediaStrip(sources: List<String>, end: Boolean = false, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    val single = sources.size == 1
    Column(modifier.widthIn(max = if (single) 300.dp else 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        sources.chunked(if (single) 1 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { source -> MediaThumb(source, single, Modifier) { viewing = source to it } }
            }
        }
    }
    viewing?.let { (source, image) -> MediaViewer(source, image) { viewing = null } }
}

@Composable
private fun MediaThumb(source: String, single: Boolean, modifier: Modifier, onOpen: (ImageBitmap) -> Unit) {
    val loader = LocalImageLoader.current
    var attempt by remember(source) { mutableIntStateOf(0) }
    // Null while loading; a loaded image or a failure after that.
    val state by produceState<Result<ImageBitmap>?>(null, source, attempt) {
        value = null
        if (loader == null) { value = Result.failure(IllegalStateException()); return@produceState }
        val image = suspendCancellableCoroutine { next -> loader(source) { if (next.isActive) next.resume(it) } }
        value = image?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
    }
    val shape = RoundedCornerShape(16.dp)
    val image = state?.getOrNull()
    Box(
        modifier
            .then(if (single && image != null) Modifier.widthIn(max = 300.dp).heightIn(max = 360.dp) else Modifier.size(if (single) 220.dp else 150.dp, 140.dp))
            .clip(shape).background(Rose.Soft).border(1.dp, Rose.Border, shape)
            .clickable(role = Role.Image) { if (image != null) onOpen(image) else if (state?.isFailure == true) attempt++ }
            .semantics { contentDescription = "이미지" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> Image(image, null, contentScale = if (single) ContentScale.Fit else ContentScale.Crop,
                modifier = if (single) Modifier.widthIn(max = 300.dp).heightIn(max = 360.dp) else Modifier.fillMaxSize())
            state?.isFailure == true -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Rounded.BrokenImage, null, tint = Rose.Muted, modifier = Modifier.size(22.dp))
                Text("이미지를 불러오지 못했어요", fontSize = 11.sp, color = Rose.Muted, modifier = Modifier.padding(top = 4.dp))
            }
            else -> ThinkingIndicator()
        }
    }
}

/** One image over the whole screen: pinch to zoom, double-tap to zoom in or back, the button or Back closes it. */
@Composable
private fun MediaViewer(source: String, image: ImageBitmap, onClose: () -> Unit) {
    val saver = LocalImageSaver.current
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(note) {
        if (note != null) {
            kotlinx.coroutines.delay(2_000)
            note = null
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val zoom = rememberTransformableState { change, pan, _ ->
            scale = (scale * change).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                image, "이미지", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        })
                    }
                    .transformable(zoom)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saver != null) ViewerButton(if (saved) Icons.Rounded.Check else Icons.Rounded.Download, "갤러리에 저장") {
                    if (saving || saved) return@ViewerButton
                    saving = true
                    saver(source) { ok ->
                        saving = false
                        saved = ok
                        note = when {
                            ok -> "갤러리의 Pictures/Hermes에 저장했어요"
                            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q -> "Android 10 이상에서 저장할 수 있어요"
                            else -> "저장하지 못했어요"
                        }
                    }
                }
                ViewerButton(Icons.Rounded.Close, "닫기", onClose)
            }
            note?.let {
                Text(it, fontSize = 14.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
}
