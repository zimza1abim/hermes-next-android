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
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.assistant.voice.CallBarHost
import com.hermeswebui.android.assistant.voice.VoiceActivity
import com.hermeswebui.android.assistant.voice.VoiceSettingsActivity
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
        val restored = savedInstanceState?.getString(STATE_PANE)?.let { name -> Pane.entries.firstOrNull { it.name == name } } ?: Pane.LIST
        // After the process was killed the chat itself is gone, so its screen would open blank: the list shows instead.
        val chatGone = restored == Pane.CHAT && controller.state.value.let { it.storedId == null && it.turns.isEmpty() && !it.opening }
        pane.value = if (chatGone) Pane.LIST else restored
        load(pane.value)
        nativeHome.value = NativeHome.enabled(this)
        if (savedInstanceState != null) tab.value = savedInstanceState.getString(STATE_TAB)
        controller.loadAvatars()
        if (savedInstanceState == null) openFrom(intent)
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
            val archived by admin.archived.collectAsState()
            Box {
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
                menu = MenuView(fontList, cron, skills, toolsets, mcp, plugins, mcpCatalog, installing, archived, notice, nativeHome.value,
                    capabilitiesOf = "@" + (state.profiles.firstOrNull { if (tabProfile == null) it.own else it.name == tabProfile }?.title ?: tabProfile ?: "메인 봇")),
                menuActions = menuActions,
                )
                // A running call is visible from the conversation it was started in, and one tap returns.
                CallBarHost(modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter))
            }
        }
    }

    private val chatsActions by lazy {
        ChatsActions(
            onOpen = ::open,
            onNew = { if (controller.selectProfile(tabProfile, leave = true)) pane.value = Pane.CHAT else listNotice.value = NOT_SENT_YET },
            onTab = { tab.value = it },
            onDelete = { row ->
                // Gone from the list at once; a refusal puts it back by reading the list again.
                val open = controller.state.value.openedId == row.id
                if (open) controller.selectProfile(controller.state.value.profile, leave = true)
                edit(row) { null }
                admin.deleteChat(row) { gone -> settled(if (gone) "대화를 지웠어요" else "지우지 못했어요. 실행 중인 대화일 수 있어요") }
            },
            onRename = { row, title ->
                edit(row) { it.copy(title = title) }
                admin.changeChat(row, ChatChange(title = title)) { ok -> settled(if (ok) "이름을 바꿨어요" else "이름을 바꾸지 못했어요") }
            },
            onPin = { row, pinned ->
                edit(row) { it.copy(pinned = pinned) }
                admin.changeChat(row, ChatChange(pinned = pinned)) { ok ->
                    settled(if (!ok) "바꾸지 못했어요" else if (pinned) "맨 위에 고정했어요" else "고정을 풀었어요")
                }
            },
            onArchive = { row ->
                edit(row) { null }
                admin.changeChat(row, ChatChange(archived = true)) { ok -> settled(if (ok) "보관했어요. 메뉴의 보관한 대화에 있어요" else "보관하지 못했어요") }
            },
            onPoll = { if (!controller.chatsLoading.value) controller.refreshChats() },
            onNoticeShown = { listNotice.value = null },
            onBackToList = {
                pane.value = Pane.LIST
                val read = shownRow
                shownRow = null
                // An answer that came while the chat was open was read here; the list is read after that is stored.
                if (read != null && !controller.state.value.busy) markRead(read) { controller.refreshChats() } else controller.refreshChats()
            },
            onRefresh = {
                controller.refreshChats()
                controller.refreshProfiles()
            },
            onVoice = { VoiceActivity.start(this, controller.state.value.profile) },
            onMenu = { pane.value = Pane.MENU },
            onExit = ::finish,
        )
    }

    /**
     * Opens [row]. An answer being written in another chat goes on at the server and is there when that
     * chat is opened again; only a question still on its way keeps its chat on screen.
     */
    private fun open(row: ChatRow) {
        if (!controller.openChat(row, leave = true)) {
            listNotice.value = NOT_SENT_YET
            return
        }
        pane.value = Pane.CHAT
        shownRow = row
        markRead(row)
    }

    /** The chat on screen, so what arrives while it is read is not left marked unread. */
    private var shownRow: ChatRow? = null

    /** Tells the server the chat was read, so it stops blinking here and on other devices. */
    private fun markRead(row: ChatRow, done: () -> Unit = {}) {
        if (row.kind == ChatKind.BOT) return done()
        edit(row) { it.copy(unread = false) }
        admin.changeChat(row, ChatChange(unread = false)) { done() }
    }

    /** A notification or the quick panel names the chat to show, by its stored id. */
    private fun openFrom(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_CHAT)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return
        fun known() = controller.chats.value.firstOrNull { it.id == id } ?: controller.state.value.profiles.mapNotNull { it.chat }.firstOrNull { it.id == id }
        val row = known()
        if (row != null) open(row)
        // Its bot is in the list; a chat the list does not have is tried as one of the main bot's.
        else controller.refreshChats { open(known() ?: ChatRow(id, null, null, null)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    /** Shows a change in the list before the server answers; null takes the row out. */
    private fun edit(row: ChatRow, change: (ChatRow) -> ChatRow?) {
        controller.chats.value = controller.chats.value.mapNotNull { if (it.id == row.id && it.profile == row.profile) change(it) else it }
    }

    /** The server answered: say so, and read the list as it now is. */
    private fun settled(text: String) {
        listNotice.value = text
        controller.refreshChats()
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
            onUnarchive = { row ->
                admin.archived.value = admin.archived.value.let { list -> list.copy(rows = list.rows.filterNot { it.id == row.id && it.profile == row.profile }) }
                admin.changeChat(row, ChatChange(archived = false)) { ok ->
                    admin.notice.value = if (ok) "대화 목록으로 꺼냈어요" else "꺼내지 못했어요"
                    admin.refreshArchived()
                    controller.refreshChats()
                }
            },
            onRefresh = ::load,
            onAppSettings = {
                startActivity(com.hermeswebui.android.launcher.AppShortcuts.settingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onWebapp = ::openApp,
            onVoiceSettings = { startActivity(android.content.Intent(this, VoiceSettingsActivity::class.java)) },
            onRefreshChats = {
                controller.refreshChats()
                controller.refreshProfiles()
            },
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
            Pane.ARCHIVE -> admin.refreshArchived()
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
            onNewChat = {
                if (!controller.selectProfile(controller.state.value.profile, leave = true)) listNotice.value = NOT_SENT_YET
            },
            onRetry = controller::retry,
            onApprove = controller::approve,
            onOpenApp = ::openApp,
            onReplace = {},
            onPickPhotos = ::pickPhotos,
            onTakePhoto = ::takePhoto,
            onRemovePhoto = controller::removeImage,
            thumbnail = controller::thumbnail,
            loadImage = controller::loadImage,
            saveImage = controller::saveImage,
            onSlash = controller::loadSlash,
            onVoice = { VoiceActivity.start(this, controller.state.value.profile) },
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
        // Photos taken an hour ago were sent or dropped; they are not kept in the cache.
        val stale = System.currentTimeMillis() - 60 * 60 * 1_000L
        dir.listFiles { file -> file.name.startsWith("chat-") && file.lastModified() < stale }?.forEach { it.delete() }
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
                .putExtra(HermesKeepAliveService.EXTRA_WEBAPP, true)
                // Signing in happens on the Webapp page; once it connects, the native chats come back.
                .putExtra(HermesKeepAliveService.EXTRA_SIGN_IN, controller.state.value.link == AssistantLink.SignedOut)
                .apply { controller.state.value.storedId?.let { putExtra(HermesKeepAliveService.EXTRA_OPEN_STORED_ID, it) } }
        )
    }

    companion object {
        /** The stored id of the chat to open. */
        const val EXTRA_CHAT = "chat"
        private const val NOT_SENT_YET = "보내는 중인 질문이 있어요. 잠시 뒤에 다시 눌러 주세요"
        private const val STATE_CAPTURE = "capture"
        private const val STATE_PANE = "pane"
        private const val STATE_TAB = "tab"
    }
}
