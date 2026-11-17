package com.hermeswebui.android.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.webapp.MonitorEndpoint
import com.hermeswebui.android.webapp.SignInGate
import com.hermeswebui.android.webapp.WebappGatewayCoordinator
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The process's one assistant chat. It outlives the panel, so closing and reopening it keeps the
 * conversation and a running answer. Main thread only; every decision is [AssistantMachine]'s.
 */
/** The full model list as far as it has been read. */
data class Catalog(val providers: List<CatalogProvider> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)

class AssistantController private constructor(private val context: Context, private val name: String) {
    private val handler = Handler(Looper.getMainLooper())
    private val repository = SettingsRepository(context)
    private val socket = AssistantSocket(handler, { repository.getSettings("", "").serverUrl }, ::dispatch)
    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    /** The unsent text, kept while the panel is closed and across an app restart. */
    val draft = MutableStateFlow(prefs.getString(KEY_DRAFT, "").orEmpty())
    val mode = MutableStateFlow(AssistantMode.ASK)
    /** The models starred in Hermes, refreshed on every open; kept so the chip shows at once. */
    val favourites = MutableStateFlow(prefs.getString(KEY_FAVOURITES, "").orEmpty().split('\n').mapNotNull(AssistantModel::parse))
    /** What new chats are created with. */
    val setup = MutableStateFlow(ChatSetup(prefs.getString(KEY_MODEL, null)?.let(AssistantModel::parse), prefs.getString(KEY_EFFORT, null)))
    /** Every signed-in provider's models, read when the user browses beyond the favourites. */
    val catalog = MutableStateFlow(Catalog())
    private var favouritesRead: SignInGate.Handle? = null
    /** Favourites are read once the socket is up, so the read never queues ahead of the ticket. */
    private var favouritesDue = false
    private var catalogRead: SignInGate.Handle? = null

    fun chooseModel(choice: AssistantModel?) {
        setup.value = setup.value.copy(model = choice)
        prefs.edit().putString(KEY_MODEL, choice?.key).apply()
    }

    fun chooseEffort(effort: String?) {
        setup.value = setup.value.copy(effort = effort)
        prefs.edit().putString(KEY_EFFORT, effort).apply()
    }

    private fun refreshFavourites() {
        favouritesRead?.cancel()
        favouritesRead = readApi("/api/ui-preferences") { reply ->
            val list = reply?.let(AssistantProtocol::favourites) ?: return@readApi
            favourites.value = list
            prefs.edit().putString(KEY_FAVOURITES, list.joinToString("\n") { it.key }).apply()
        }
    }

    /** Reads the full model list; a list already read is shown while it refreshes. */
    fun loadCatalog() {
        if (catalog.value.loading) return
        catalog.value = catalog.value.copy(loading = true, failed = false)
        catalogRead?.cancel()
        catalogRead = readApi("/api/model/options") { reply ->
            val providers = reply?.let(AssistantProtocol::catalog)
            catalog.value = if (providers == null) catalog.value.copy(loading = false, failed = catalog.value.providers.isEmpty())
                else Catalog(providers)
        } ?: run { catalog.value = catalog.value.copy(loading = false, failed = true); null }
    }

    /** [done] runs on the main thread with the reply, or null when it failed. Null when no server is set. */
    private fun readApi(path: String, done: (org.json.JSONObject?) -> Unit): SignInGate.Handle? {
        val server = repository.getSettings("", "").serverUrl.takeIf { it.isNotBlank() } ?: return null
        val endpoint = MonitorEndpoint.from(server) ?: return null
        return SignInGate.getJson(endpoint, path, server) { reply -> handler.post { done(reply) } }
    }

    fun setDraft(text: String) {
        if (text == draft.value) return
        draft.value = text
        prefs.edit().putString(KEY_DRAFT, text).apply()
    }
    private val pending = ArrayDeque<AssistantInput>()
    private var dispatching = false
    private var shownIds: Set<String> = emptySet()
    /** Photos picked for the next question, by id, in order. */
    val pendingImages = MutableStateFlow<List<String>>(emptyList())
    /** Photos still being read and shrunk. */
    val preparing = MutableStateFlow(0)
    private val images = linkedMapOf<String, PreparedImage>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "hermes-assistant-images").apply { isDaemon = true } }

    init {
        context.getSystemService(ConnectivityManager::class.java)?.let { connectivity ->
            runCatching {
                connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    private var current: Network? = null

                    override fun onAvailable(network: Network) {
                        val previous = current
                        current = network
                        if (previous != null && previous != network) handler.post { dispatch(AssistantInput.NetworkChanged) }
                    }
                })
            }
        }
    }

    fun show(shown: Boolean) = dispatch(AssistantInput.Shown(shown))

    /**
     * The panel was opened anew (a trigger, a shortcut, the text-selection menu): it starts a new chat.
     * The assistant is for the next quick question; earlier chats are in the Hermes app. An answer still
     * being written when the panel was closed stays, so it can be read.
     */
    fun openFresh() {
        dispatch(AssistantInput.NewChat)
        mode.value = AssistantMode.ASK
        // Photos picked for a chat that was left are not carried into the new one.
        pendingImages.value = emptyList()
        trimImages()
        favouritesDue = true
        refreshFavouritesWhenReady()
    }

    private fun refreshFavouritesWhenReady() {
        if (!favouritesDue || _state.value.link != AssistantLink.Ready) return
        favouritesDue = false
        refreshFavourites()
    }

    /** Sends [question] with the picked photos; photos alone get a default question. */
    fun ask(mode: AssistantMode, question: String) {
        val text = question.trim()
        val photos = pendingImages.value
        if (text.isEmpty() && photos.isEmpty()) return
        val prompt = if (text.isEmpty()) PHOTO_ONLY_PROMPT else promptFor(mode, text)
        pendingImages.value = emptyList()
        dispatch(AssistantInput.Ask(UUID.randomUUID().toString(), mode, text, prompt, photos, setup.value))
    }

    /** Reads, shrinks and re-encodes a photo off the main thread, then adds it to the next question. */
    fun addImage(uri: Uri) {
        if (pendingImages.value.size + preparing.value >= MAX_PHOTOS) return
        preparing.value++
        worker.execute {
            val prepared = runCatching { AssistantImages.prepare(context, uri) }.getOrNull()
            handler.post {
                preparing.value--
                if (prepared == null) return@post
                val id = UUID.randomUUID().toString()
                images[id] = prepared
                pendingImages.value = pendingImages.value + id
                trimImages()
            }
        }
    }

    fun removeImage(id: String) {
        pendingImages.value = pendingImages.value - id
    }

    fun thumbnail(id: String): ImageBitmap? = images[id]?.thumbnail

    /** Keeps only photos still to be sent or shown in this chat; each holds its upload bytes. */
    private fun trimImages() {
        val used = pendingImages.value.toSet() + _state.value.turns.flatMap { it.images }
        images.keys.retainAll(used)
    }

    fun stop() = dispatch(AssistantInput.Stop)

    fun again(turnId: String) = dispatch(AssistantInput.Again(turnId, UUID.randomUUID().toString()))

    fun newChat() {
        dispatch(AssistantInput.NewChat)
        trimImages()
    }

    fun retry() = dispatch(AssistantInput.Retry)

    fun refreshProfiles() = dispatch(AssistantInput.RefreshProfiles)

    /** A new chat with [name]'s bot. With [leave], an answer being written is left to finish at the server. False when a question is still on its way. */
    fun selectProfile(name: String?, leave: Boolean = false): Boolean {
        if (!canLeave(leave)) return false
        dispatch(AssistantInput.SelectProfile(name, leave))
        pendingImages.value = emptyList()
        trimImages()
        return true
    }

    private fun canLeave(leave: Boolean) = _state.value.let { !it.busy || (leave && it.leavable) }

    /** Each bot's picture by profile name. The files are kept, so the pictures show before the socket is up. */
    val avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val avatarDir = java.io.File(context.filesDir, "bot-avatars")

    private fun avatarFile(profile: String) =
        java.io.File(avatarDir, profile.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + ".img")

    /** Reads the kept pictures off the main thread; called once when the chats screen is created. */
    fun loadAvatars() {
        if (avatars.value.isNotEmpty()) return
        worker.execute {
            val read = avatarDir.listFiles().orEmpty().mapNotNull { file ->
                decodeAvatar(runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null)?.let { file.name.removeSuffix(".img") to it }
            }.toMap()
            handler.post { if (read.isNotEmpty()) avatars.value = read + avatars.value }
        }
    }

    private fun keepAvatar(profile: String, dataUrl: String) {
        worker.execute {
            val bytes = runCatching { android.util.Base64.decode(dataUrl.substringAfter("base64,", ""), android.util.Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_AVATAR_BYTES } ?: return@execute
            val image = decodeAvatar(bytes) ?: return@execute
            val file = avatarFile(profile)
            runCatching { avatarDir.mkdirs(); file.writeBytes(bytes) }
            handler.post { avatars.value = avatars.value + (file.name.removeSuffix(".img") to image) }
        }
    }

    /** A bot's kept picture; [avatars] is keyed by the file-safe form of the profile name. */
    fun avatarKey(profile: String): String = avatarFile(profile).name.removeSuffix(".img")

    private fun decodeAvatar(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }.getOrNull()

    /** The recent chats of every profile, kept so the list shows at once on the next open. */
    val chats = MutableStateFlow(emptyList<ChatRow>())
    val chatsLoading = MutableStateFlow(false)
    private var chatsRead: SignInGate.Handle? = null

    private val chatsWaiters = mutableListOf<() -> Unit>()

    /** Reads the list again. [done] runs when a read ends, also when a newer read replaced this one. */
    fun refreshChats(done: (() -> Unit)? = null) {
        done?.let(chatsWaiters::add)
        fun finished() {
            chatsLoading.value = false
            val waiting = chatsWaiters.toList()
            chatsWaiters.clear()
            waiting.forEach { it() }
        }
        chatsRead?.cancel()
        chatsLoading.value = true
        chatsRead = readApi(AssistantProtocol.SIDEBAR_PATH) { reply ->
            reply?.let(AssistantProtocol::sidebar)?.let { chats.value = decorate(it) }
            finished()
        } ?: run { finished(); null }
    }

    /** The chats an answer is being written in: this screen's own, and those the background monitor follows. */
    private fun runningIds(): Set<String> {
        val current = _state.value
        val own = if (current.activeTurn != null) setOfNotNull(current.storedId, current.openedId) else emptySet()
        return own + WebappGatewayCoordinator.obtain(context).runningStoredIds()
    }

    /** The list as the app shows it: each row's newest line, and whether an answer is being written in it. */
    private fun decorate(rows: List<ChatRow>): List<ChatRow> {
        val running = runningIds()
        return rows.map { row ->
            val line = lastLines[row.id]
            val busy = row.id in running
            if ((line == null || line == row.preview) && busy == row.running) row else row.copy(preview = line ?: row.preview, running = busy)
        }
    }

    /** A turn began or ended: the rows' running marks follow at once. */
    private fun syncRunning() {
        val rows = chats.value
        if (rows.isEmpty()) return
        val next = decorate(rows)
        if (next != rows) chats.value = next
    }

    /*
     * The server's preview of a chat is the head of its first question and never changes. The list shows
     * the newest line instead for every chat this phone has shown: the line is kept here by chat id.
     */
    private val lastLines: MutableMap<String, String> = runCatching {
        org.json.JSONObject(prefs.getString(KEY_LAST_LINES, "{}").orEmpty()).let { saved -> saved.keys().asSequence().associateWith { saved.optString(it) }.toMutableMap() }
    }.getOrDefault(mutableMapOf())

    /** Keeps the newest line of the chat on screen once nothing is being written in it. */
    private fun rememberLastLine() {
        val current = _state.value
        if (current.busy) return
        val turn = current.turns.lastOrNull()
        val text = (turn?.answer?.takeIf { it.isNotBlank() } ?: turn?.question?.takeIf { it.isNotBlank() } ?: current.history.lastOrNull()?.text)
            ?.let(AssistantMarkdown::plain)?.take(160)?.takeIf { it.isNotBlank() } ?: return
        val ids = setOfNotNull(current.storedId, current.openedId)
        if (ids.isEmpty() || ids.all { lastLines[it] == text }) return
        ids.forEach { lastLines[it] = text }
        while (lastLines.size > MAX_LAST_LINES) lastLines.remove(lastLines.keys.first())
        prefs.edit().putString(KEY_LAST_LINES, org.json.JSONObject(lastLines as Map<*, *>).toString()).apply()
        chats.value = decorate(chats.value)
    }

    /** Opens [row]; a chat of the server's own profile is reached without naming the profile. */
    fun openChat(row: ChatRow, leave: Boolean = false): Boolean {
        if (!canLeave(leave)) return false
        val own = _state.value.profiles.firstOrNull { it.own }?.name
        val profile = row.profile?.takeUnless { it == own || (own == null && row.defaultProfile) }
        dispatch(AssistantInput.OpenChat(row.id, row.title, profile, leave))
        pendingImages.value = emptyList()
        trimImages()
        return true
    }

    fun approve(request: com.hermeswebui.android.webapp.RpcId, allow: Boolean) = dispatch(AssistantInput.Approve(request, allow))

    private fun dispatch(input: AssistantInput) {
        pending.addLast(input)
        if (dispatching) return
        dispatching = true
        try {
            while (pending.isNotEmpty()) {
                val (next, effects) = AssistantMachine.reduce(_state.value, pending.removeFirst())
                _state.value = next
                effects.forEach(::execute)
            }
            syncShown()
            rememberLastLine()
            syncRunning()
            refreshFavouritesWhenReady()
        } finally {
            dispatching = false
        }
    }

    private fun execute(effect: AssistantEffect) {
        when (effect) {
            is AssistantEffect.Mint -> socket.mint(effect.gen)
            is AssistantEffect.Dial -> socket.dial(effect.gen, effect.ticket)
            is AssistantEffect.Send -> if (!socket.send(effect.gen, effect.frame)) dispatch(AssistantInput.Closed(effect.gen))
            is AssistantEffect.Attach -> {
                val image = images[effect.imageId]
                if (image == null) dispatch(AssistantInput.Received(effect.gen,
                    AssistantFrame.Reply(effect.id, errorCode = -1, errorMessage = "사진을 다시 골라 주세요")))
                else if (!socket.send(effect.gen, AssistantProtocol.attachImage(effect.id, effect.sessionId, image.base64, image.filename, _state.value.profile))) {
                    dispatch(AssistantInput.Closed(effect.gen))
                }
            }
            is AssistantEffect.Close -> socket.close(effect.gen)
            is AssistantEffect.RetryAfter -> socket.retryAfter(effect.gen, effect.delayMs)
            is AssistantEffect.Avatar -> keepAvatar(effect.profile, effect.data)
            is AssistantEffect.LingerAfter -> handler.postDelayed({ dispatch(AssistantInput.LingerDue(effect.token)) }, effect.delayMs)
        }
    }

    /** The background monitor stays quiet about the chat the panel is showing. */
    private fun syncShown() {
        val current = _state.value
        val ids = if (current.shown) setOfNotNull(current.runtimeId, current.storedId) else emptySet()
        if (ids == shownIds) return
        shownIds = ids
        shownByScreen[name] = ids
        WebappGatewayCoordinator.obtain(context).setShownElsewhere(shownByScreen.values.flatten().toSet())
    }

    companion object {
        @Volatile private var shared: AssistantController? = null
        @Volatile private var chats: AssistantController? = null
        /** The chats each screen shows; the background monitor stays quiet about all of them. Main thread only. */
        private val shownByScreen = mutableMapOf<String, Set<String>>()

        /** The quick panel's chat. */
        fun obtain(context: Context): AssistantController = shared ?: synchronized(this) {
            shared ?: AssistantController(context.applicationContext, "hermes_assistant").also { shared = it }
        }

        /** The full chat screen's own chat and socket, so the panel and the screen never share a conversation. */
        fun obtainChats(context: Context): AssistantController = chats ?: synchronized(this) {
            chats ?: AssistantController(context.applicationContext, "hermes_chats").also { chats = it }
        }

        private const val MAX_PHOTOS = 4
        private const val MAX_AVATAR_BYTES = 8 * 1024 * 1024
        private const val KEY_DRAFT = "draft"
        private const val KEY_LAST_LINES = "last_lines"
        private const val MAX_LAST_LINES = 300
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_FAVOURITES = "favourites"
        const val PHOTO_ONLY_PROMPT = "이 사진을 보고 무엇인지 설명해 줘."

        fun promptFor(mode: AssistantMode, text: String): String = when (mode) {
            AssistantMode.ASK -> text
            AssistantMode.SEARCH -> "웹에서 최신 정보를 검색해서 답해 줘. 참고한 출처 링크도 같이 알려 줘.\n\n$text"
            AssistantMode.TRANSLATE -> "다음 글을 번역해 줘. 한국어가 아니면 자연스러운 한국어로, 한국어면 자연스러운 영어로 옮겨 줘. " +
                "설명 없이 번역문만 답해 줘.\n\n$text"
            AssistantMode.SUMMARY -> "다음 내용을 핵심만 세 줄로 요약해 줘.\n\n$text"
        }
    }
}
