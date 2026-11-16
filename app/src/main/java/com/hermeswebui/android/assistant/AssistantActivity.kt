package com.hermeswebui.android.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.background.HermesKeepAliveService
import com.hermeswebui.android.launcher.AppShortcuts
import com.hermeswebui.android.launcher.LauncherIconManager

/**
 * The assistant panel over whatever app was open. It runs in its own task, so closing it returns to
 * that app. Started by `hermes://assistant`, an explicit intent (Tasker, Modes and Routines) or the
 * text-selection menu.
 */
class AssistantActivity : ComponentActivity() {
    private val controller by lazy { AssistantController.obtain(this) }
    /** The text-selection caller can take a translation back in place of its selection. */
    private val replaceable = mutableStateOf(false)
    private var captureUri: Uri? = null
    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        uris.forEach(controller::addImage)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = captureUri
        if (taken && uri != null) controller.addImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        captureUri = savedInstanceState?.getString(STATE_CAPTURE)?.let(Uri::parse)
        // The photo shortcut lives on the assistant's launcher entry; publish it even if the main app never opened.
        if (savedInstanceState == null) AppShortcuts.publish(applicationContext, LauncherIconManager(applicationContext).selectedIcon())
        if (savedInstanceState == null) handle(intent)
        setContent {
            val state by controller.state.collectAsState()
            val draft by controller.draft.collectAsState()
            val mode by controller.mode.collectAsState()
            val photos by controller.pendingImages.collectAsState()
            val preparing by controller.preparing.collectAsState()
            val favourites by controller.favourites.collectAsState()
            val model by controller.model.collectAsState()
            AssistantScreen(
                state = state,
                draft = draft,
                mode = mode,
                photos = photos,
                preparing = preparing,
                replaceable = replaceable.value,
                models = ModelChoice(favourites, model),
                actions = actions,
            )
        }
    }

    /** One instance for the activity, so a new state does not hand every part of the panel new callbacks. */
    private val actions by lazy {
        AssistantActions(
                    onDraft = controller::setDraft,
                    onMode = { controller.mode.value = it },
                    onModel = controller::chooseModel,
                    onSend = ::send,
                    onStop = controller::stop,
                    onAgain = controller::again,
                    onClose = ::close,
                    onNewChat = controller::newChat,
                    onRetry = controller::retry,
                    onApprove = controller::approve,
                    onOpenApp = ::openApp,
                    onReplace = ::replace,
                    onPickPhotos = ::pickPhotos,
                    onTakePhoto = ::takePhoto,
                    onRemovePhoto = controller::removeImage,
                    thumbnail = controller::thumbnail,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureUri?.let { outState.putString(STATE_CAPTURE, it.toString()) }
    }

    override fun onStart() {
        super.onStart()
        controller.show(true)
    }

    override fun onStop() {
        controller.show(false)
        super.onStop()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        controller.openFresh()
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()?.take(MAX_TEXT)
            replaceable.value = !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            if (!text.isNullOrEmpty()) {
                controller.mode.value = AssistantMode.TRANSLATE
                // The user picked this text and this action themselves, so it is sent at once.
                controller.ask(AssistantMode.TRANSLATE, text)
            }
            return
        }
        replaceable.value = false
        val uri: Uri? = intent.data?.takeIf { it.scheme == "hermes" && it.host == "assistant" }
        val mode = (uri?.getQueryParameter("mode") ?: intent.getStringExtra(EXTRA_MODE))?.let(::parseMode)
        val text = (uri?.getQueryParameter("text") ?: intent.getStringExtra(EXTRA_TEXT))?.trim()?.take(MAX_TEXT)
        if (mode != null) controller.mode.value = mode
        // Another app or a link can only fill the field: sending stays the user's tap.
        if (!text.isNullOrEmpty()) controller.setDraft(text)
        // Opening the camera is not sending: the photo still waits for the user's tap.
        if ((uri?.getQueryParameter("attach") ?: intent.getStringExtra(EXTRA_ATTACH)) == "camera") {
            window.decorView.post { takePhoto() }
        }
    }

    private fun pickPhotos() {
        runCatching { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    /** The camera app writes into the app cache through the file provider; no camera permission is needed. */
    private fun takePhoto() {
        val dir = File(cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val stale = System.currentTimeMillis() - CAPTURE_TTL_MS
        dir.listFiles { file -> file.name.startsWith(CAPTURE_PREFIX) && file.lastModified() < stale }?.forEach { it.delete() }
        val file = File(dir, "$CAPTURE_PREFIX${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        captureUri = uri
        runCatching { takePhoto.launch(uri) }
    }

    private fun send() {
        val text = controller.draft.value
        if (text.isBlank() && controller.pendingImages.value.isEmpty()) return
        controller.ask(controller.mode.value, text)
        controller.setDraft("")
    }

    private fun close() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun replace(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        close()
    }

    /** Opens this chat in the full app; the Webapp page is asked to show it. */
    private fun openApp() {
        val state = controller.state.value
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(HermesKeepAliveService.ACTION_OPEN_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // The page opens chats by stored id; this socket's runtime id means nothing to it.
                .apply { state.storedId?.let { putExtra(HermesKeepAliveService.EXTRA_OPEN_STORED_ID, it) } }
        )
        close()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_MODE = "mode"
        /** `camera` opens the camera as the panel appears. */
        const val EXTRA_ATTACH = "attach"
        private const val STATE_CAPTURE = "capture"
        private const val CAPTURE_DIR = "upload-captures"
        private const val CAPTURE_PREFIX = "assistant-"
        private const val CAPTURE_TTL_MS = 60 * 60 * 1_000L
        private const val MAX_TEXT = 20_000

        fun parseMode(value: String): AssistantMode? = when (value.trim().lowercase()) {
            "ask" -> AssistantMode.ASK
            "search" -> AssistantMode.SEARCH
            "translate" -> AssistantMode.TRANSLATE
            "summary", "summarize" -> AssistantMode.SUMMARY
            else -> null
        }
    }
}
