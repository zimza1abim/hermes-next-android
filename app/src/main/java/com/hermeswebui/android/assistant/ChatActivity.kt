package com.hermeswebui.android.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.background.HermesKeepAliveService
import java.io.File

/**
 * The native chats: every earlier chat and a full conversation, on the gateway socket alone. No
 * Webapp page is loaded. Started by `hermes://chats` or an explicit intent.
 */
class ChatActivity : ComponentActivity() {
    private val controller by lazy { AssistantController.obtainChats(this) }
    private val pane = mutableStateOf(Pane.LIST)
    private val admin by lazy { HermesAdmin(this) }
    private val fonts = kotlinx.coroutines.flow.MutableStateFlow(Listing<FontChoice>())
    private val nativeHome = mutableStateOf(true)
    private val tab = mutableStateOf<String?>(ChatsView.ALL)
    private val listNotice = mutableStateOf<String?>(null)
    /** The bot the open tab stands for; the full list and the main bot's tab are the server's own profile. */
    private val tabProfile: String? get() = tab.value?.takeUnless { it == ChatsView.ALL }
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
        HermesFonts.preload(applicationContext)
        captureUri = savedInstanceState?.getString(STATE_CAPTURE)?.let(Uri::parse)
        pane.value = savedInstanceState?.getString(STATE_PANE)?.let { name -> Pane.entries.firstOrNull { it.name == name } } ?: Pane.LIST
        nativeHome.value = NativeHome.enabled(this)
        if (savedInstanceState != null) tab.value = savedInstanceState.getString(STATE_TAB)
        controller.loadAvatars()
        setContent {
            val state by controller.state.collectAsState()
            val draft by controller.draft.collectAsState()
            val mode by controller.mode.collectAsState()
            val photos by controller.pendingImages.collectAsState()
            val preparing by controller.preparing.collectAsState()
            val favourites by controller.favourites.collectAsState()
            val setup by controller.setup.collectAsState()
            val catalog by controller.catalog.collectAsState()
            val chats by controller.chats.collectAsState()
            val loading by controller.chatsLoading.collectAsState()
            val avatars by controller.avatars.collectAsState()
            val fontList by fonts.collectAsState()
            val cron by admin.cron.collectAsState()
            val skills by admin.skills.collectAsState()
            val toolsets by admin.toolsets.collectAsState()
            val mcp by admin.mcp.collectAsState()
            val notice by admin.notice.collectAsState()
            val plugins by admin.plugins.collectAsState()
            val mcpCatalog by admin.mcpCatalog.collectAsState()
            val installing by admin.installing.collectAsState()
            ChatScreen(
                state = state,
                draft = draft,
                mode = mode,
                photos = photos,
                preparing = preparing,
                models = ModelChoice(favourites, setup, catalog),
                actions = actions,
                view = ChatsView(chats, loading, avatars, controller::avatarKey, tab.value, pane.value, listNotice.value),
                chats = chatsActions,
                menu = MenuView(fontList, cron, skills, toolsets, mcp, plugins, mcpCatalog, installing, notice, nativeHome.value,
                    capabilitiesOf = "@" + (state.profiles.firstOrNull { if (tabProfile == null) it.own else it.name == tabProfile }?.title ?: tabProfile ?: "메인 봇")),
                menuActions = menuActions,
            )
        }
    }

    private val chatsActions by lazy {
        ChatsActions(
            onOpen = { row ->
                // A running turn keeps its chat; the picked one opens only when nothing runs.
                controller.openChat(row)
                pane.value = Pane.CHAT
            },
            onNew = {
                controller.selectProfile(tabProfile)
                pane.value = Pane.CHAT
            },
            onTab = { tab.value = it },
            onDelete = { row ->
                // Gone from the list at once; a refusal puts it back by reading the list again.
                val open = controller.state.value.openedId == row.id
                if (open) controller.selectProfile(controller.state.value.profile)
                controller.chats.value = controller.chats.value.filterNot { it.id == row.id && it.profile == row.profile }
                admin.deleteChat(row) { gone ->
                    listNotice.value = if (gone) "대화를 지웠어요" else "지우지 못했어요. 열려 있거나 실행 중인 대화일 수 있어요"
                    controller.refreshChats()
                    window.decorView.postDelayed({ listNotice.value = null }, 2_500)
                }
            },
            onBackToList = {
                pane.value = Pane.LIST
                controller.refreshChats()
            },
            onRefresh = {
                controller.refreshChats()
                controller.refreshProfiles()
            },
            onMenu = { pane.value = Pane.MENU },
            onExit = ::finish,
        )
    }

    private val menuActions by lazy {
        MenuActions(
            onPane = { next ->
                pane.value = next
                load(next)
            },
            onPickFont = { choice -> Thread { HermesFonts.pick(applicationContext, choice) }.start() },
            onCronPaused = admin::setCronPaused,
            onCronRun = admin::runCron,
            onInstall = { admin.install(it, tabProfile) },
            onRefresh = ::load,
            onAppSettings = {
                startActivity(com.hermeswebui.android.launcher.AppShortcuts.settingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onWebapp = ::openApp,
            onNativeHome = { on ->
                NativeHome.set(this, on)
                nativeHome.value = on
            },
            onNoticeShown = { admin.notice.value = null },
        )
    }

    /** Reads what [next] shows; each list keeps its last rows while it refreshes. */
    private fun load(next: Pane) {
        when (next) {
            Pane.CRON -> admin.refreshCron()
            Pane.CAPABILITIES -> admin.refreshCapabilities(tabProfile)
            Pane.INSTALL -> admin.refreshCatalogs(tabProfile)
            Pane.FONT -> {
                fonts.value = fonts.value.copy(loading = true)
                val app = applicationContext
                Thread {
                    val found = HermesFonts.installed(app)
                    runOnUiThread { fonts.value = Listing(found) }
                }.start()
            }
            else -> Unit
        }
    }

    private val actions by lazy {
        AssistantActions(
            onDraft = controller::setDraft,
            onMode = { controller.mode.value = it },
            onModel = controller::chooseModel,
            onEffort = controller::chooseEffort,
            onBrowseModels = controller::loadCatalog,
            onSend = ::send,
            onStop = controller::stop,
            onAgain = controller::again,
            onClose = { pane.value = Pane.LIST },
            onNewChat = { controller.selectProfile(controller.state.value.profile) },
            onRetry = controller::retry,
            onApprove = controller::approve,
            onOpenApp = ::openApp,
            onReplace = {},
            onPickPhotos = ::pickPhotos,
            onTakePhoto = ::takePhoto,
            onRemovePhoto = controller::removeImage,
            thumbnail = controller::thumbnail,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureUri?.let { outState.putString(STATE_CAPTURE, it.toString()) }
        outState.putString(STATE_PANE, pane.value.name)
        outState.putString(STATE_TAB, tab.value)
    }

    override fun onStart() {
        super.onStart()
        controller.show(true)
        controller.refreshChats()
        controller.refreshProfiles()
    }

    override fun onStop() {
        controller.show(false)
        super.onStop()
    }

    private fun pickPhotos() {
        runCatching { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "upload-captures").apply { mkdirs() }
        val file = File(dir, "chat-${System.currentTimeMillis()}.jpg")
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

    /** Sign-in and the questions this screen cannot answer yet still go through the Webapp page. */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(HermesKeepAliveService.ACTION_OPEN_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { controller.state.value.storedId?.let { putExtra(HermesKeepAliveService.EXTRA_OPEN_STORED_ID, it) } }
        )
    }

    private companion object {
        const val STATE_CAPTURE = "capture"
        const val STATE_PANE = "pane"
        const val STATE_TAB = "tab"
    }
}
