package com.hermeswebui.android.assistant

import com.hermeswebui.android.webapp.GatewayEvent
import com.hermeswebui.android.webapp.GatewayMonitorMachine
import com.hermeswebui.android.webapp.RpcId
import com.hermeswebui.android.webapp.TicketResult
import com.hermeswebui.android.webapp.TurnOutcome

/** What the user asked for; the panel turns it into the prompt text it sends. */
enum class AssistantMode { ASK, SEARCH, TRANSLATE, SUMMARY }

/** What a new chat is created with; null fields are Hermes's own settings. */
data class ChatSetup(val model: AssistantModel? = null, val effort: String? = null)

/** A model the chat is created with, as Hermes names it: provider slug and model id. */
data class AssistantModel(val provider: String, val model: String) {
    /** The `provider::model` form Hermes stores favourites in. */
    val key: String get() = "$provider::$model"

    companion object {
        fun parse(key: String): AssistantModel? {
            val provider = key.substringBefore("::", "").trim()
            val model = key.substringAfter("::", "").trim()
            return if (provider.isEmpty() || model.isEmpty()) null else AssistantModel(provider, model)
        }
    }
}

/** A chat in the list. [id] is the durable id `session.resume` opens. */
data class ChatRow(
    val id: String,
    val title: String?,
    val preview: String?,
    /** Unix seconds. */
    val lastActiveAt: Double?,
    val running: Boolean = false,
    val pinned: Boolean = false,
    /** The profile that owns it, and whether that is the default profile. */
    val profile: String? = null,
    val defaultProfile: Boolean = true,
    val kind: ChatKind = ChatKind.CHAT,
    /** A messaging thread's platform (`telegram`, `discord`, ...). */
    val source: String? = null,
)

/** What a list row is: the bot's standing chat, a chat started in an app, a messaging thread, or a cron job's run. */
enum class ChatKind { BOT, CHAT, MESSAGING, CRON }

/** A bot: a Hermes profile the server serves. [own] is the server's launch profile, which calls reach without naming it. */
data class ProfileRow(
    val name: String,
    val title: String,
    val own: Boolean = false,
    val hasAvatar: Boolean = true,
    /** The bot's one standing chat ("Bot Chat"), which the server keeps out of the recent chats. */
    val chat: ChatRow? = null,
)

enum class HistoryRole { USER, ASSISTANT }

/** One row of a chat's earlier transcript. */
data class HistoryRow(val role: HistoryRole, val text: String)

/** The assistant socket. Each attempt runs under its own generation; older callbacks are dropped. */
sealed interface AssistantLink {
    data object Idle : AssistantLink
    data class Minting(val attempt: Int) : AssistantLink
    data class Connecting(val attempt: Int) : AssistantLink
    data class Attaching(val attempt: Int) : AssistantLink
    data object Ready : AssistantLink
    data class Backoff(val attempt: Int) : AssistantLink
    /** The gateway refused the sign-in; the main app must sign in again. */
    data object SignedOut : AssistantLink
    /** The server has no gateway: not an official Webapp server. */
    data object Unsupported : AssistantLink
}

enum class TurnStatus {
    /** Waiting for a session or a socket. */
    QUEUED,
    /** `prompt.submit` sent, no receipt yet. */
    SENDING,
    /** The socket dropped before a receipt: the server may or may not have it. Never resent blindly. */
    UNKNOWN,
    ACCEPTED,
    STREAMING,
    DONE,
    FAILED,
    STOPPED;

    val active: Boolean get() = this == SENDING || this == UNKNOWN || this == ACCEPTED || this == STREAMING
}

data class AssistantTurn(
    /** Also the `submission_id`, so a resend after a lost receipt is the same admission. */
    val id: String,
    val mode: AssistantMode,
    /** What the user typed, shown in the bubble. */
    val question: String,
    /** What goes to the server. */
    val prompt: String,
    val status: TurnStatus = TurnStatus.QUEUED,
    val segments: Map<Int, String> = emptyMap(),
    /** The server's final text; it replaces the streamed segments. */
    val final: String? = null,
    val error: String? = null,
    /** Photo ids, in order; their bytes stay in the controller, outside the state. */
    val images: List<String> = emptyList(),
    /** What the server returned for each uploaded photo of [images]. */
    val attached: Map<String, ImageAttachment> = emptyMap(),
    /** What a chat this turn creates runs with. */
    val setup: ChatSetup = ChatSetup(),
) {
    val answer: String get() = final ?: segments.toSortedMap().values.joinToString("\n\n")
}

/** An uploaded photo: the server path `prompt.submit` names, and its type when a canonical server gave one. */
data class ImageAttachment(val path: String?, val mime: String?)

/** A call the panel is waiting on, by request id. */
sealed interface PendingCall {
    data object Capabilities : PendingCall
    data object Create : PendingCall
    data object Resume : PendingCall
    data class Submit(val turnId: String, val identity: Boolean) : PendingCall
    data class Attach(val turnId: String, val imageId: String) : PendingCall
    data object Interrupt : PendingCall
    data object Profiles : PendingCall
    data class Avatar(val profile: String) : PendingCall
}

data class AssistantState(
    val gen: Int = 0,
    val link: AssistantLink = AssistantLink.Idle,
    val authority: Boolean = false,
    val nextId: Int = 1,
    /** The live id events carry; null until created, and again after a reconnect until resumed. */
    val runtimeId: String? = null,
    /** The durable chat id; survives reconnects. */
    val storedId: String? = null,
    val calls: Map<Int, PendingCall> = emptyMap(),
    val turns: List<AssistantTurn> = emptyList(),
    /** Approvals and questions the server is waiting on in this chat. */
    val requests: List<GatewayEvent.PromptRequest> = emptyList(),
    /** The panel is on screen. */
    val shown: Boolean = false,
    /** Bumped each time the panel goes idle off screen; only the latest linger timer may close the socket. */
    val lingerToken: Int = 0,
    /** The socket stays open a little after the panel closes, so reopening it is instant. */
    val lingering: Boolean = false,
    /** The opened chat's transcript from before this screen's own turns. */
    val history: List<HistoryRow> = emptyList(),
    /** An earlier chat was picked and its transcript has not arrived. */
    val opening: Boolean = false,
    val title: String? = null,
    val profiles: List<ProfileRow> = emptyList(),
    /** The bot whose chats are shown and whose chat is open; null is the server's own profile. */
    val profile: String? = null,
    val profilesDue: Boolean = false,
    /** The list id the open chat was picked by; the server may answer with another durable id. */
    val openedId: String? = null,
) {
    /** The selected bot's row, or the server's own. */
    val bot: ProfileRow? get() = profiles.firstOrNull { it.name == profile } ?: profiles.firstOrNull { it.own }


    val activeTurn: AssistantTurn? get() = turns.lastOrNull { it.status.active }

    /** The socket is wanted while the panel is shown or a turn still runs. */
    val wanted: Boolean get() = shown || profilesDue || opening || turns.any { it.status.active || it.status == TurnStatus.QUEUED }

    val busy: Boolean get() = turns.any { it.status.active || it.status == TurnStatus.QUEUED }
}

sealed interface AssistantInput {
    data class Shown(val shown: Boolean) : AssistantInput
    data class Ask(
        val id: String,
        val mode: AssistantMode,
        val question: String,
        val prompt: String,
        val images: List<String> = emptyList(),
        val setup: ChatSetup = ChatSetup(),
    ) : AssistantInput
    data object Stop : AssistantInput
    /** Asks a failed or stopped question again, as a new turn [newId] in its place. */
    data class Again(val turnId: String, val newId: String) : AssistantInput
    /** Starts a new chat; ignored while a turn runs. */
    data object NewChat : AssistantInput
    /** Opens an earlier chat of [profile] (null: the server's own) with its transcript; ignored while a turn runs. */
    data class OpenChat(val storedId: String, val title: String?, val profile: String? = null) : AssistantInput
    data object RefreshProfiles : AssistantInput
    /** Starts a new chat with [name] (null: the server's own profile); ignored while a turn runs. */
    data class SelectProfile(val name: String?) : AssistantInput
    data class Approve(val rpcId: RpcId, val allow: Boolean) : AssistantInput
    /** The user asks to reconnect now. */
    data object Retry : AssistantInput
    data object NetworkChanged : AssistantInput
    /** The linger after the panel closed is over. */
    data class LingerDue(val token: Int) : AssistantInput

    sealed interface LinkInput : AssistantInput { val gen: Int }
    data class Ticket(override val gen: Int, val result: TicketResult) : LinkInput
    data class Opened(override val gen: Int) : LinkInput
    data class Received(override val gen: Int, val frame: AssistantFrame) : LinkInput
    data class Closed(override val gen: Int) : LinkInput
    data class RetryDue(override val gen: Int) : LinkInput
}

sealed interface AssistantEffect {
    data class Mint(val gen: Int) : AssistantEffect
    data class Dial(val gen: Int, val ticket: String) : AssistantEffect {
        override fun toString() = "Dial(gen=$gen)"
    }
    data class Send(val gen: Int, val frame: String) : AssistantEffect
    /** Uploads photo [imageId] to [sessionId] as call [id]; the shell holds the bytes. */
    data class Attach(val gen: Int, val id: Int, val imageId: String, val sessionId: String) : AssistantEffect
    data class Close(val gen: Int) : AssistantEffect
    data class RetryAfter(val gen: Int, val delayMs: Long) : AssistantEffect
    data class LingerAfter(val token: Int, val delayMs: Long) : AssistantEffect
    /** A bot's picture arrived, as a data URL; the shell decodes and keeps it. */
    data class Avatar(val profile: String, val data: String) : AssistantEffect
}

/**
 * Every assistant decision, pure. Invariants:
 *  - One turn runs at a time; later questions wait as [TurnStatus.QUEUED].
 *  - A question is resent only with its own `submission_id` on a canonical server, which deduplicates
 *    it. On a legacy server a lost receipt leaves the turn [TurnStatus.UNKNOWN] until the transcript
 *    shows what happened.
 *  - Only events of this panel's chat change it.
 */
object AssistantMachine {
    private const val MAX_TURNS = 50
    /** How long a ready socket outlives the closed panel. Pings every 15 s keep it alive meanwhile. */
    const val LINGER_MS = 3 * 60 * 1_000L

    fun reduce(state: AssistantState, input: AssistantInput): Pair<AssistantState, List<AssistantEffect>> {
        val (applied, effects) = apply(state, input)
        val (connected, linkEffects) = reconcile(applied)
        val (flushed, sends) = flush(connected)
        return flushed to (effects + linkEffects + sends)
    }

    private fun apply(state: AssistantState, input: AssistantInput): Pair<AssistantState, List<AssistantEffect>> = when (input) {
        is AssistantInput.Shown -> {
            val next = state.copy(shown = input.shown)
            val stuck = state.link == AssistantLink.SignedOut || state.link == AssistantLink.Unsupported || state.link is AssistantLink.Backoff
            // Opening the panel tries again at once: the user may have signed in or the network returned.
            if (input.shown && !state.shown && stuck) mint(next, 0) else next to emptyList()
        }
        is AssistantInput.Ask -> {
            val text = input.question.trim()
            if ((text.isEmpty() && input.images.isEmpty()) || state.turns.any { it.id == input.id }) state to emptyList()
            else state.copy(turns = (state.turns + AssistantTurn(input.id, input.mode, text, input.prompt, images = input.images, setup = input.setup))
                .takeLast(MAX_TURNS)) to emptyList()
        }
        AssistantInput.Stop -> stop(state)
        is AssistantInput.Again -> {
            val old = state.turns.firstOrNull { it.id == input.turnId && (it.status == TurnStatus.FAILED || it.status == TurnStatus.STOPPED) }
            if (old == null || state.turns.any { it.id == input.newId }) state to emptyList()
            else state.copy(turns = state.turns - old + AssistantTurn(input.newId, old.mode, old.question, old.prompt, images = old.images, setup = old.setup)) to emptyList()
        }
        AssistantInput.NewChat -> if (state.busy) state to emptyList()
            else state.copy(runtimeId = null, storedId = null, turns = emptyList(), requests = emptyList(),
                history = emptyList(), opening = false, title = null, openedId = null) to emptyList()
        AssistantInput.RefreshProfiles -> state.copy(profilesDue = true) to emptyList()
        is AssistantInput.SelectProfile -> if (state.busy) state to emptyList()
            else state.copy(profile = input.name?.takeIf { it.isNotBlank() }, runtimeId = null, storedId = null, turns = emptyList(),
                requests = emptyList(), history = emptyList(), opening = false, title = null, openedId = null,
                calls = state.calls.filterValues { it != PendingCall.Resume && it != PendingCall.Create }) to emptyList()
        is AssistantInput.OpenChat -> when {
            state.busy || input.storedId.isBlank() -> state to emptyList()
            // The chat already on screen: its transcript and this screen's turns stay as they are.
            input.storedId == state.openedId && (state.runtimeId != null || state.opening) -> state to emptyList()
            else -> state.copy(runtimeId = null, storedId = input.storedId, openedId = input.storedId, turns = emptyList(), requests = emptyList(),
                profile = input.profile?.takeIf { it.isNotBlank() },
                history = emptyList(), opening = true, title = input.title,
                calls = state.calls.filterValues { it != PendingCall.Resume && it != PendingCall.Create }) to emptyList()
        }
        is AssistantInput.Approve -> approve(state, input)
        AssistantInput.Retry -> when (state.link) {
            is AssistantLink.Backoff, AssistantLink.SignedOut, AssistantLink.Unsupported -> mint(state, 0)
            else -> state to emptyList()
        }
        AssistantInput.NetworkChanged -> when (val link = state.link) {
            AssistantLink.Idle, AssistantLink.SignedOut, AssistantLink.Unsupported -> state to emptyList()
            is AssistantLink.Backoff -> mint(state, link.attempt)
            else -> dropped(state).let { (next, _) -> mint(next, 0).let { (minted, effects) -> minted to listOf(AssistantEffect.Close(state.gen)) + effects } }
        }
        is AssistantInput.LingerDue ->
            if (input.token == state.lingerToken && state.lingering && !state.wanted) close(state) else state to emptyList()
        is AssistantInput.LinkInput -> if (input.gen != state.gen) state to emptyList() else link(state, input)
    }

    /**
     * Connects while wanted. Once the panel is gone and nothing runs, a ready socket lingers for
     * [LINGER_MS] so reopening is instant; an attempt still connecting finishes and then lingers; a
     * backoff stops at once.
     */
    private fun reconcile(state: AssistantState): Pair<AssistantState, List<AssistantEffect>> {
        if (state.wanted) {
            val awake = if (state.lingering) state.copy(lingering = false, lingerToken = state.lingerToken + 1) else state
            return if (awake.link == AssistantLink.Idle) mint(awake, 0) else awake to emptyList()
        }
        return when (state.link) {
            AssistantLink.Ready -> if (state.lingering) state to emptyList() else {
                val token = state.lingerToken + 1
                state.copy(lingering = true, lingerToken = token) to listOf(AssistantEffect.LingerAfter(token, LINGER_MS))
            }
            is AssistantLink.Backoff -> close(state)
            else -> state to emptyList()
        }
    }

    private fun close(state: AssistantState): Pair<AssistantState, List<AssistantEffect>> =
        dropped(state).first.copy(gen = state.gen + 1, link = AssistantLink.Idle, lingering = false) to listOf(AssistantEffect.Close(state.gen))

    /** Creates the chat, then submits the oldest queued question when nothing else runs. */
    private fun flush(state: AssistantState): Pair<AssistantState, List<AssistantEffect>> {
        if (state.link != AssistantLink.Ready) return state to emptyList()
        if (state.profilesDue) {
            val id = state.nextId
            return flush(state.copy(profilesDue = false, nextId = id + 1, calls = state.calls + (id to PendingCall.Profiles))).let { (flushed, sends) ->
                flushed to (listOf(AssistantEffect.Send(state.gen, AssistantProtocol.listProfiles(id))) + sends)
            }
        }
        if (state.calls.values.any { it == PendingCall.Create || it == PendingCall.Resume }) return state to emptyList()
        // A chat picked from the list on a socket that is already up: attach to it now.
        if (state.opening && state.runtimeId == null) {
            val stored = state.storedId ?: return state.copy(opening = false) to emptyList()
            val id = state.nextId
            return state.copy(nextId = id + 1, calls = state.calls + (id to PendingCall.Resume)) to
                listOf(AssistantEffect.Send(state.gen, AssistantProtocol.resume(id, state.authority, stored, state.profile)))
        }
        val queued = state.turns.firstOrNull { it.status == TurnStatus.QUEUED } ?: return state to emptyList()
        if (state.runtimeId == null) {
            if (state.storedId != null) return state to emptyList()
            val id = state.nextId
            return state.copy(nextId = id + 1, calls = state.calls + (id to PendingCall.Create)) to
                listOf(AssistantEffect.Send(state.gen, AssistantProtocol.create(id, state.authority, "assistant-${queued.id}", queued.setup, state.profile)))
        }
        if (state.activeTurn != null) return state to emptyList()
        val uploading = state.calls.values.filterIsInstance<PendingCall.Attach>().filter { it.turnId == queued.id }.map { it.imageId }
        val missing = queued.images.filter { it !in queued.attached && it !in uploading }
        if (missing.isNotEmpty()) {
            val ids = missing.indices.map { state.nextId + it }
            return state.copy(
                nextId = state.nextId + missing.size,
                calls = state.calls + ids.zip(missing) { id, image -> id to PendingCall.Attach(queued.id, image) },
            ) to ids.zip(missing) { id, image -> AssistantEffect.Attach(state.gen, id, image, state.runtimeId) }
        }
        if (uploading.isNotEmpty()) return state to emptyList()
        return submit(state, queued, identity = true)
    }

    private fun submit(state: AssistantState, turn: AssistantTurn, identity: Boolean): Pair<AssistantState, List<AssistantEffect>> {
        val sessionId = state.runtimeId ?: return state to emptyList()
        val id = state.nextId
        return state.copy(
            nextId = id + 1,
            calls = state.calls + (id to PendingCall.Submit(turn.id, identity)),
            turns = state.turns.update(turn.id) { it.copy(status = TurnStatus.SENDING, error = null) },
        ) to listOf(AssistantEffect.Send(state.gen, AssistantProtocol.submit(id, sessionId, turn.prompt, turn.id.takeIf { identity },
            turn.images.mapNotNull { turn.attached[it] }, state.profile)))
    }

    private fun stop(state: AssistantState): Pair<AssistantState, List<AssistantEffect>> {
        val queued = state.turns.map { if (it.status == TurnStatus.QUEUED) it.copy(status = TurnStatus.STOPPED) else it }
        val sessionId = state.runtimeId
        if (state.activeTurn == null || sessionId == null || state.link != AssistantLink.Ready) return state.copy(turns = queued) to emptyList()
        val id = state.nextId
        return state.copy(turns = queued, nextId = id + 1, calls = state.calls + (id to PendingCall.Interrupt)) to
            listOf(AssistantEffect.Send(state.gen, AssistantProtocol.interrupt(id, sessionId, state.profile)))
    }

    private fun approve(state: AssistantState, input: AssistantInput.Approve): Pair<AssistantState, List<AssistantEffect>> {
        val request = state.requests.firstOrNull { it.rpcId == input.rpcId } as? GatewayEvent.ApprovalRequested ?: return state to emptyList()
        if (state.link != AssistantLink.Ready) return state to emptyList()
        val frame = AssistantProtocol.approval(request, input.allow) ?: return state to emptyList()
        return state.copy(requests = state.requests - request) to listOf(AssistantEffect.Send(state.gen, frame))
    }

    private fun link(state: AssistantState, input: AssistantInput.LinkInput): Pair<AssistantState, List<AssistantEffect>> {
        val link = state.link
        return when (input) {
            is AssistantInput.Ticket -> if (link !is AssistantLink.Minting) state to emptyList() else when {
                input.result.signedOut -> state.copy(link = AssistantLink.SignedOut) to emptyList()
                input.result.unsupported -> state.copy(link = AssistantLink.Unsupported) to emptyList()
                input.result.ticket != null -> state.copy(link = AssistantLink.Connecting(link.attempt)) to
                    listOf(AssistantEffect.Dial(state.gen, input.result.ticket))
                else -> backoff(state, link.attempt)
            }
            is AssistantInput.Opened -> if (link !is AssistantLink.Connecting) state to emptyList()
                else state.copy(link = AssistantLink.Attaching(link.attempt)) to emptyList()
            is AssistantInput.Closed -> when (link) {
                is AssistantLink.Connecting -> backoff(state, link.attempt)
                is AssistantLink.Attaching -> backoff(state, link.attempt)
                AssistantLink.Ready -> backoff(dropped(state).first, 0)
                else -> state to emptyList()
            }
            is AssistantInput.RetryDue -> if (link is AssistantLink.Backoff) mint(state, link.attempt + 1) else state to emptyList()
            is AssistantInput.Received -> received(state, input.frame)
        }
    }

    private fun received(state: AssistantState, frame: AssistantFrame): Pair<AssistantState, List<AssistantEffect>> {
        if (frame is AssistantFrame.Ready) {
            if (state.link !is AssistantLink.Attaching) return state to emptyList()
            val capsId = state.nextId
            var next = state.copy(link = AssistantLink.Ready, authority = frame.authority, nextId = capsId + 1,
                calls = mapOf(capsId to PendingCall.Capabilities))
            val sends = mutableListOf<AssistantEffect>(AssistantEffect.Send(state.gen, AssistantProtocol.capabilities(capsId)))
            val stored = state.storedId
            if (stored != null) {
                val id = next.nextId
                next = next.copy(nextId = id + 1, calls = next.calls + (id to PendingCall.Resume))
                sends += AssistantEffect.Send(state.gen, AssistantProtocol.resume(id, frame.authority, stored, state.profile))
            }
            return next to sends
        }
        if (state.link != AssistantLink.Ready) return state to emptyList()
        return when (frame) {
            is AssistantFrame.Reply -> reply(state, frame)
            is AssistantFrame.WindowRequest -> state to listOf(AssistantEffect.Send(state.gen, AssistantProtocol.notShown(frame.rpcId)))
            is AssistantFrame.Started -> ours(watching(state, frame.sessionId), frame.sessionId) { turn -> turn.copy(status = TurnStatus.STREAMING) }
            is AssistantFrame.Delta -> ours(state, frame.sessionId) { turn ->
                turn.copy(status = TurnStatus.STREAMING, segments = appendChunk(turn.segments, frame))
            }
            is AssistantFrame.Completed -> ours(state, frame.sessionId) { turn ->
                val status = when (frame.outcome) {
                    TurnOutcome.COMPLETE -> TurnStatus.DONE
                    TurnOutcome.ERROR -> TurnStatus.FAILED
                    TurnOutcome.INTERRUPTED -> TurnStatus.STOPPED
                }
                turn.copy(status = status, final = frame.text?.takeIf { it.isNotBlank() } ?: turn.final,
                    error = frame.error.takeIf { status == TurnStatus.FAILED })
            }.let { (next, effects) -> next.copy(requests = emptyList()) to effects }
            is AssistantFrame.Request -> if (frame.request.sessionId != state.runtimeId || state.requests.any { it.rpcId == frame.request.rpcId }) state to emptyList()
                else state.copy(requests = state.requests + frame.request) to emptyList()
            is AssistantFrame.Withdrawn -> if (frame.sessionId != state.runtimeId) state to emptyList()
                else state.copy(requests = state.requests.filterNot { request ->
                    request.rpcId == frame.rpcId || (frame.requestIds != null && request is GatewayEvent.ApprovalRequested &&
                        (frame.requestIds.isEmpty() || request.requestId in frame.requestIds))
                }) to emptyList()
            is AssistantFrame.Ready -> state to emptyList()
        }
    }

    private fun reply(state: AssistantState, frame: AssistantFrame.Reply): Pair<AssistantState, List<AssistantEffect>> {
        val call = state.calls[frame.id] ?: return state to emptyList()
        val base = state.copy(calls = state.calls - frame.id)
        return when (call) {
            PendingCall.Capabilities, PendingCall.Interrupt -> base to emptyList()
            PendingCall.Profiles -> {
                val rows = frame.profiles?.takeIf { frame.ok } ?: return base to emptyList()
                // A selected bot the server no longer serves falls back to the server's own profile.
                val kept = base.copy(profiles = rows, profile = base.profile?.takeIf { name -> rows.any { it.name == name } })
                val wanted = rows.filter { it.hasAvatar }
                val ids = wanted.indices.map { kept.nextId + it }
                kept.copy(nextId = kept.nextId + wanted.size, calls = kept.calls + ids.zip(wanted) { id, row -> id to PendingCall.Avatar(row.name) }) to
                    ids.zip(wanted) { id, row -> AssistantEffect.Send(state.gen, AssistantProtocol.avatar(id, row.name)) }
            }
            is PendingCall.Avatar -> base to listOfNotNull(frame.asset?.let { AssistantEffect.Avatar(call.profile, it) })
            PendingCall.Create -> if (frame.ok && frame.sessionId != null) base.copy(runtimeId = frame.sessionId, storedId = frame.storedId) to emptyList()
                else base.copy(turns = base.turns.map {
                    if (it.status == TurnStatus.QUEUED) it.copy(status = TurnStatus.FAILED, error = frame.errorMessage ?: "새 대화를 만들지 못했어요") else it
                }) to emptyList()
            PendingCall.Resume -> resumed(base, frame)
            is PendingCall.Attach -> base.copy(turns = base.turns.update(call.turnId) { turn ->
                if (turn.status != TurnStatus.QUEUED) turn
                else if (frame.ok && frame.attached != false) turn.copy(attached = turn.attached + (call.imageId to ImageAttachment(frame.path, frame.mime)))
                else turn.copy(status = TurnStatus.FAILED, error = frame.errorMessage ?: "사진을 올리지 못했어요")
            }) to emptyList()
            is PendingCall.Submit -> {
                val turn = base.turns.firstOrNull { it.id == call.turnId } ?: return base to emptyList()
                when {
                    frame.ok -> base.copy(turns = base.turns.update(turn.id) {
                        if (it.status == TurnStatus.SENDING) it.copy(status = TurnStatus.ACCEPTED) else it
                    }) to emptyList()
                    call.identity && frame.errorCode in AssistantProtocol.PRE_ADMISSION_CODES -> submit(base, turn, identity = false)
                    else -> base.copy(turns = base.turns.update(turn.id) {
                        it.copy(status = TurnStatus.FAILED, error = frame.errorMessage ?: "보내지 못했어요")
                    }) to emptyList()
                }
            }
        }
    }

    /** After a reconnect: the chat's new runtime id, and how the turn that ran while away ended. */
    private fun resumed(state: AssistantState, frame: AssistantFrame.Reply): Pair<AssistantState, List<AssistantEffect>> {
        if (!frame.ok || frame.sessionId == null) {
            return state.copy(runtimeId = null, storedId = null, opening = false, turns = state.turns.map {
                if (it.status.active) it.copy(status = TurnStatus.FAILED, error = "대화를 다시 열지 못했어요") else it
            }) to emptyList()
        }
        val attached = state.copy(runtimeId = frame.sessionId, storedId = frame.storedId ?: state.storedId, opening = false)
        // The transcript already holds this screen's own turns, so it is taken only for a chat just opened.
        val next = if (state.turns.isNotEmpty() || frame.history == null) attached
            else attached.copy(history = frame.history).let { if (frame.running == true) watching(it, frame.sessionId) else it }
        val turn = next.activeTurn ?: return next to emptyList()
        val running = frame.running == true
        return when {
            turn.status == TurnStatus.UNKNOWN && state.authority && !running -> submit(next, turn, identity = true)
            turn.status == TurnStatus.UNKNOWN && running -> next.copy(turns = next.turns.update(turn.id) { it.copy(status = TurnStatus.STREAMING) }) to emptyList()
            turn.status == TurnStatus.UNKNOWN ->
                if (frame.lastQuestion?.trim() == turn.prompt.trim() && frame.lastAnswer != null)
                    next.copy(turns = next.turns.update(turn.id) { it.copy(status = TurnStatus.DONE, final = frame.lastAnswer) }) to emptyList()
                else next to emptyList()
            running -> next to emptyList()
            else -> next.copy(turns = next.turns.update(turn.id) {
                it.copy(status = TurnStatus.DONE, final = frame.lastAnswer ?: it.final ?: it.answer.ifEmpty { null })
            }) to emptyList()
        }
    }

    /**
     * A turn this screen did not send is running in its chat (started from another device, or before
     * the chat was opened): a turn without a question takes its answer.
     */
    private fun watching(state: AssistantState, sessionId: String): AssistantState =
        if (sessionId != state.runtimeId || state.activeTurn != null) state
        else state.copy(turns = (state.turns + AssistantTurn("remote-${state.nextId}-${state.turns.size}", AssistantMode.ASK, "", "",
            status = TurnStatus.STREAMING)).takeLast(MAX_TURNS))

    /** Applies [change] to the running turn when [sessionId] is this panel's chat. */
    private fun ours(state: AssistantState, sessionId: String, change: (AssistantTurn) -> AssistantTurn): Pair<AssistantState, List<AssistantEffect>> {
        if (sessionId != state.runtimeId) return state to emptyList()
        val turn = state.activeTurn ?: return state to emptyList()
        return state.copy(turns = state.turns.update(turn.id, change)) to emptyList()
    }

    fun appendChunk(segments: Map<Int, String>, delta: AssistantFrame.Delta): Map<Int, String> {
        val segment = delta.segment ?: segments.keys.maxOrNull() ?: 0
        val current = segments[segment].orEmpty()
        val offset = delta.offset ?: current.length
        val merged = when {
            offset >= current.length -> current + delta.text
            offset + delta.text.length <= current.length -> current
            else -> current + delta.text.substring(current.length - offset)
        }
        return segments + (segment to merged)
    }

    /** The socket is gone: calls die, the chat must be resumed, and an unreceipted question becomes unknown. */
    private fun dropped(state: AssistantState): Pair<AssistantState, List<AssistantEffect>> =
        state.copy(
            calls = emptyMap(),
            runtimeId = null,
            requests = emptyList(),
            profilesDue = state.profilesDue || state.calls.values.any { it == PendingCall.Profiles },
            // A photo staged on the old socket's session is uploaded again with the question.
            turns = state.turns.map {
                when (it.status) {
                    TurnStatus.SENDING -> it.copy(status = TurnStatus.UNKNOWN)
                    TurnStatus.QUEUED -> it.copy(attached = emptyMap())
                    else -> it
                }
            },
        ) to emptyList()

    private fun mint(state: AssistantState, attempt: Int): Pair<AssistantState, List<AssistantEffect>> {
        val gen = state.gen + 1
        return state.copy(gen = gen, link = AssistantLink.Minting(attempt)) to listOf(AssistantEffect.Mint(gen))
    }

    private fun backoff(state: AssistantState, attempt: Int): Pair<AssistantState, List<AssistantEffect>> =
        state.copy(link = AssistantLink.Backoff(attempt)) to
            listOf(AssistantEffect.RetryAfter(state.gen, GatewayMonitorMachine.retryDelayMs(attempt)))

    private fun List<AssistantTurn>.update(id: String, change: (AssistantTurn) -> AssistantTurn): List<AssistantTurn> =
        map { if (it.id == id) change(it) else it }
}
