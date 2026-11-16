package com.hermeswebui.android.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
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
class AssistantController private constructor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val repository = SettingsRepository(context)
    private val socket = AssistantSocket(handler, { repository.getSettings("", "").serverUrl }, ::dispatch)
    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state
    private val prefs = context.getSharedPreferences("hermes_assistant", Context.MODE_PRIVATE)
    /** The unsent text, kept while the panel is closed and across an app restart. */
    val draft = MutableStateFlow(prefs.getString(KEY_DRAFT, "").orEmpty())
    val mode = MutableStateFlow(AssistantMode.ASK)
    /** The models starred in Hermes, refreshed on every open; kept so the chip shows at once. */
    val favourites = MutableStateFlow(prefs.getString(KEY_FAVOURITES, "").orEmpty().split('\n').mapNotNull(AssistantModel::parse))
    /** The model new chats run on; null is Hermes's own choice. */
    val model = MutableStateFlow(prefs.getString(KEY_MODEL, null)?.let(AssistantModel::parse))
    private var favouritesRead: SignInGate.Handle? = null

    fun chooseModel(choice: AssistantModel?) {
        model.value = choice
        prefs.edit().putString(KEY_MODEL, choice?.key).apply()
    }

    private fun refreshFavourites() {
        val server = repository.getSettings("", "").serverUrl.takeIf { it.isNotBlank() } ?: return
        val endpoint = MonitorEndpoint.from(server) ?: return
        favouritesRead?.cancel()
        favouritesRead = SignInGate.preferences(endpoint, server) { reply ->
            val list = reply?.let(AssistantProtocol::favourites) ?: return@preferences
            handler.post {
                favourites.value = list
                prefs.edit().putString(KEY_FAVOURITES, list.joinToString("\n") { it.key }).apply()
                if (model.value != null && model.value !in list) chooseModel(null)
            }
        }
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
        refreshFavourites()
    }

    /** Sends [question] with the picked photos; photos alone get a default question. */
    fun ask(mode: AssistantMode, question: String) {
        val text = question.trim()
        val photos = pendingImages.value
        if (text.isEmpty() && photos.isEmpty()) return
        val prompt = if (text.isEmpty()) PHOTO_ONLY_PROMPT else promptFor(mode, text)
        pendingImages.value = emptyList()
        dispatch(AssistantInput.Ask(UUID.randomUUID().toString(), mode, text, prompt, photos, model.value))
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
                while (images.size > MAX_KEPT) images.remove(images.keys.first { it !in pendingImages.value })
                pendingImages.value = pendingImages.value + id
            }
        }
    }

    fun removeImage(id: String) {
        pendingImages.value = pendingImages.value - id
    }

    fun thumbnail(id: String): ImageBitmap? = images[id]?.thumbnail

    fun stop() = dispatch(AssistantInput.Stop)

    fun again(turnId: String) = dispatch(AssistantInput.Again(turnId, UUID.randomUUID().toString()))

    fun newChat() = dispatch(AssistantInput.NewChat)

    fun retry() = dispatch(AssistantInput.Retry)

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
                else if (!socket.send(effect.gen, AssistantProtocol.attachImage(effect.id, effect.sessionId, image.base64, image.filename))) {
                    dispatch(AssistantInput.Closed(effect.gen))
                }
            }
            is AssistantEffect.Close -> socket.close(effect.gen)
            is AssistantEffect.RetryAfter -> socket.retryAfter(effect.gen, effect.delayMs)
            is AssistantEffect.LingerAfter -> handler.postDelayed({ dispatch(AssistantInput.LingerDue(effect.token)) }, effect.delayMs)
        }
    }

    /** The background monitor stays quiet about the chat the panel is showing. */
    private fun syncShown() {
        val current = _state.value
        val ids = if (current.shown) setOfNotNull(current.runtimeId, current.storedId) else emptySet()
        if (ids == shownIds) return
        shownIds = ids
        WebappGatewayCoordinator.obtain(context).setShownElsewhere(ids)
    }

    companion object {
        @Volatile private var shared: AssistantController? = null

        fun obtain(context: Context): AssistantController = shared ?: synchronized(this) {
            shared ?: AssistantController(context.applicationContext).also { shared = it }
        }

        private const val MAX_PHOTOS = 4
        private const val KEY_DRAFT = "draft"
        private const val KEY_MODEL = "model"
        private const val KEY_FAVOURITES = "favourites"
        private const val MAX_KEPT = 24
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
