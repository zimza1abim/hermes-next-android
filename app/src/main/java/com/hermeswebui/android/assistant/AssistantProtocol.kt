package com.hermeswebui.android.assistant

import com.hermeswebui.android.webapp.Decision
import com.hermeswebui.android.webapp.GatewayEvent
import com.hermeswebui.android.webapp.GatewayProtocol
import com.hermeswebui.android.webapp.RpcId
import com.hermeswebui.android.webapp.TurnOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One frame of the assistant's own gateway socket, reduced to what the panel shows. */
sealed interface AssistantFrame {
    /** `gateway.ready`; [authority] is the canonical session-authority contract. */
    data class Ready(val authority: Boolean) : AssistantFrame

    /** The answer to one of the panel's calls. Fields the reply does not carry are null. */
    data class Reply(
        val id: Int,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
        val sessionId: String? = null,
        val storedId: String? = null,
        val running: Boolean? = null,
        /** The newest assistant text of a transcript reply, and the user text it answered. */
        val lastAnswer: String? = null,
        val lastQuestion: String? = null,
        /** An image upload's answer: whether it was attached, its server path and type. */
        val attached: Boolean? = null,
        val path: String? = null,
        val mime: String? = null,
        /** A transcript reply's rows, oldest first. */
        val history: List<HistoryRow>? = null,
        /** A `profiles.list` answer. */
        val profiles: List<ProfileRow>? = null,
        /** A `profiles.get_asset` answer's image, as a data URL. */
        val asset: String? = null,
    ) : AssistantFrame {
        val ok: Boolean get() = errorCode == null && errorMessage == null
    }

    data class Started(val sessionId: String) : AssistantFrame

    /**
     * A reply chunk. [segment] and [offset] (UTF-16 units of the segment already sent) let a chunk the
     * server sends again be dropped.
     */
    data class Delta(val sessionId: String, val text: String, val segment: Int?, val offset: Int?) : AssistantFrame

    data class Completed(val sessionId: String, val outcome: TurnOutcome, val text: String?, val error: String?) : AssistantFrame

    data class Request(val request: GatewayEvent.PromptRequest) : AssistantFrame

    /** The server withdrew requests: one id, or every approval with [requestIds] (empty is all of them). */
    data class Withdrawn(val sessionId: String, val rpcId: RpcId?, val requestIds: Set<String>?) : AssistantFrame

    /** A request only a window showing the session can answer; the panel declines it. */
    data class WindowRequest(val rpcId: RpcId) : AssistantFrame
}

/**
 * The gateway calls the panel makes (hermes-agent `apps/shared/src/gateway-contract.generated.ts`).
 * Canonical servers (`session_authority`) and legacy `hermes serve` take slightly different params.
 */
/** One signed-in provider and the models it serves. */
data class CatalogProvider(val slug: String, val name: String, val models: List<AssistantModel>)

object AssistantProtocol {
    /** A pre-admission refusal of an unknown param (4000) or a capability (4094): safe to resend without it. */
    val PRE_ADMISSION_CODES = setOf(4000, 4094)
    private val WINDOW_OWNED = setOf("preview.read", "preview.act", "terminal.read", "window.read", "tour")

    fun capabilities(id: Int): String = call(id, "client.capabilities", JSONObject().put("server_requests", true))

    /** [setup] pins this chat's model and reasoning only; Hermes's own settings are left as they are. */
    fun create(id: Int, authority: Boolean, requestId: String, setup: ChatSetup = ChatSetup(), profile: String? = null): String =
        call(id, "session.create", profile, JSONObject().apply {
            if (authority) put("request_id", requestId)
            setup.model?.let { put("model", it.model).put("provider", it.provider) }
            setup.effort?.let { put("reasoning_effort", it) }
        })

    /**
     * Canonical servers take uploaded photos as `attachments` (path and type). A legacy server keeps
     * them queued on the session from `image.attach_bytes` and its replies carry no type, so none are named.
     */
    fun submit(id: Int, sessionId: String, text: String, submissionId: String?, images: List<ImageAttachment> = emptyList(),
               profile: String? = null): String {
        val attachments = images.filter { it.path != null && it.mime != null }
        return call(id, "prompt.submit", profile, JSONObject().put("session_id", sessionId).put("text", text).apply {
            if (submissionId != null) put("submission_id", submissionId)
            if (attachments.isNotEmpty()) put("attachments", JSONArray(attachments.map { JSONObject().put("path", it.path).put("mime", it.mime) }))
        })
    }

    fun attachImage(id: Int, sessionId: String, base64: String, filename: String, profile: String? = null): String =
        call(id, "image.attach_bytes", profile, JSONObject().put("session_id", sessionId).put("content_base64", base64).put("filename", filename))

    /** Reattaches to [storedId] after a reconnect; the transcript tells how a turn ended while away. */
    fun resume(id: Int, authority: Boolean, storedId: String, profile: String? = null): String =
        call(id, "session.resume", profile, JSONObject().put("session_id", storedId)
            .apply { if (!authority) put("inline_images", false) })

    fun interrupt(id: Int, sessionId: String, profile: String? = null): String =
        call(id, "session.interrupt", profile, JSONObject().put("session_id", sessionId))

    /** The bots: this server's own profile first, then every profile it serves. */
    fun listProfiles(id: Int): String = call(id, "profiles.list", JSONObject().put("include_sessions", false))

    fun avatar(id: Int, profile: String): String =
        call(id, "profiles.get_asset", JSONObject().put("asset", "avatar").put("name", profile))

    fun approval(request: GatewayEvent.ApprovalRequested, allow: Boolean): String? =
        GatewayProtocol.choiceFor(if (allow) Decision.ALLOW else Decision.DENY, request.choices)
            ?.let { GatewayProtocol.encodeApprovalAnswer(request.rpcId, it) }

    fun notShown(rpcId: RpcId): String = GatewayProtocol.encodeNotShown(rpcId)

    fun ping(id: String): String = GatewayProtocol.encodePing(id)

    /** [profile] routes the call to another profile the server serves; null is the server's own. */
    private fun call(id: Int, method: String, profile: String?, params: JSONObject): String =
        call(id, method, if (profile.isNullOrEmpty()) params else params.put("profile", profile))

    private fun call(id: Int, method: String, params: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    fun parse(raw: String): AssistantFrame? {
        val frame = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull() ?: return null
        val method = frame.opt("method") as? String
        if (method == null) return parseReply(frame)
        if (method in WINDOW_OWNED) return RpcId.of(frame.opt("id"))?.let(AssistantFrame::WindowRequest)
        val params = frame.optJSONObject("params")
        if (method == "event" && params != null) {
            val payload = params.optJSONObject("payload") ?: JSONObject()
            val sessionId = text(params, "session_id") ?: text(payload, "session_id")
            when (text(params, "type")) {
                "gateway.ready" -> return AssistantFrame.Ready(payload.opt("session_authority") == true)
                "message.start" -> return sessionId?.let(AssistantFrame::Started)
                "message.delta" -> {
                    val chunk = payload.opt("text") as? String
                    if (sessionId == null || chunk.isNullOrEmpty()) return null
                    return AssistantFrame.Delta(sessionId, chunk, int(payload, "segment"), int(payload, "offset"))
                }
            }
        }
        return when (val event = GatewayProtocol.parseInbound(raw)) {
            is GatewayEvent.TurnEnded -> AssistantFrame.Completed(event.sessionId, event.outcome, event.text, event.error)
            is GatewayEvent.PromptRequest -> AssistantFrame.Request(event)
            is GatewayEvent.RequestCancelled -> AssistantFrame.Withdrawn(event.sessionId, event.rpcId, null)
            is GatewayEvent.ApprovalCancelled -> AssistantFrame.Withdrawn(event.sessionId, null, event.requestIds)
            else -> null
        }
    }

    private fun parseReply(frame: JSONObject): AssistantFrame.Reply? {
        val id = frame.opt("id") as? Int ?: return null
        frame.optJSONObject("error")?.let { error ->
            return AssistantFrame.Reply(id, errorCode = error.opt("code") as? Int ?: -1, errorMessage = text(error, "message"))
        }
        val result = frame.optJSONObject("result") ?: return AssistantFrame.Reply(id)
        val info = result.optJSONObject("info") ?: JSONObject()
        val messages = result.optJSONArray("messages")
        val lastAnswer = lastText(messages, "assistant")
        return AssistantFrame.Reply(
            id,
            sessionId = text(result, "session_id"),
            storedId = text(result, "stored_session_id") ?: text(result, "session_key") ?: text(info, "stored_session_id"),
            running = (result.opt("running") as? Boolean) ?: (info.opt("running") as? Boolean),
            lastAnswer = lastAnswer?.first,
            lastQuestion = lastText(messages, "user", before = lastAnswer?.second)?.first,
            attached = result.opt("attached") as? Boolean,
            path = text(result, "path"),
            mime = text(result, "mime"),
            history = messages?.let(::history),
            profiles = result.optJSONArray("profiles")?.let(::profiles),
            asset = (result.opt("data") as? String)?.takeIf { result.opt("found") == true && it.startsWith("data:image/") },
            errorMessage = if (result.opt("attached") == false) text(result, "message") ?: "사진을 올리지 못했어요" else null,
        )
    }

    /** The newest [role] row with text, optionally before index [before], with its index. */
    private fun lastText(messages: JSONArray?, role: String, before: Int? = null): Pair<String, Int>? {
        if (messages == null) return null
        for (index in (minOf(before ?: messages.length(), messages.length()) - 1) downTo 0) {
            val row = messages.optJSONObject(index) ?: continue
            if (row.opt("role") != role) continue
            val value = text(row, "text") ?: (row.opt("content") as? String)?.takeIf { it.isNotBlank() } ?: continue
            return value to index
        }
        return null
    }

    /**
     * The favourite models in Hermes's shared UI preferences (`/api/ui-preferences`), in starred order.
     * The value is a JSON array of `provider::model` keys, stored as text.
     */
    fun favourites(preferences: JSONObject): List<AssistantModel> {
        val raw = preferences.optJSONObject("values")?.opt(FAVOURITES_KEY) ?: return emptyList()
        val keys = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONTokener(raw).nextValue() as? JSONArray }.getOrNull()
            else -> null
        } ?: return emptyList()
        return (0 until keys.length()).mapNotNull { (keys.opt(it) as? String)?.let(AssistantModel::parse) }.distinct()
    }

    /** The signed-in providers' models from `/api/model/options`, in the server's order. */
    fun catalog(options: JSONObject): List<CatalogProvider> {
        val providers = options.optJSONArray("providers") ?: return emptyList()
        return (0 until providers.length()).mapNotNull { index ->
            val row = providers.optJSONObject(index) ?: return@mapNotNull null
            val slug = text(row, "slug") ?: return@mapNotNull null
            if (row.opt("authenticated") == false) return@mapNotNull null
            val unavailable = strings(row.optJSONArray("unavailable_models")).toSet()
            val models = strings(row.optJSONArray("models")).filter { it !in unavailable }.distinct()
            if (models.isEmpty()) null else CatalogProvider(slug, text(row, "name") ?: slug, models.map { AssistantModel(slug, it) })
        }
    }

    private fun profiles(rows: JSONArray): List<ProfileRow> = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val name = text(row, "name") ?: return@mapNotNull null
        // The name the Bots tab shows, else the profile's display name, else its id.
        val title = row.optJSONObject("ui_meta")?.optJSONObject("hermes-bots")?.let { text(it, "title") } ?: text(row, "display_name")
        ProfileRow(name, title ?: name, own = index == 0, hasAvatar = row.opt("has_avatar") != false)
    }.distinctBy { it.name }

    /**
     * The recent chats of every profile, from the Webapp's own sidebar feed
     * (`/api/profiles/sessions/sidebar`): the same list on every client, whichever chats are open.
     */
    fun sidebar(reply: JSONObject): List<ChatRow>? {
        val rows = reply.optJSONObject("recents")?.optJSONArray("sessions") ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = text(row, "id") ?: return@mapNotNull null
            ChatRow(
                id = id,
                title = text(row, "title"),
                preview = text(row, "preview")?.let(AssistantMarkdown::plain)?.take(MAX_PREVIEW),
                lastActiveAt = ((row.opt("last_active") ?: row.opt("started_at")) as? Number)?.toDouble(),
                running = row.opt("is_active") == true,
                pinned = row.opt("pinned") == true,
                profile = text(row, "profile"),
                defaultProfile = row.opt("is_default_profile") != false,
            )
        }.distinctBy { it.profile to it.id }
    }

    const val SIDEBAR_PATH = "/api/profiles/sessions/sidebar?recents_profile=all&recents_limit=200&cron_limit=1&messaging_limit=1"

    /** The display rows the server projects from a stored transcript; rows with nothing to show are skipped. */
    /** The user's and the bot's own words of a stored transcript; tool rows and empty rows are left out. */
    private fun history(rows: JSONArray): List<HistoryRow> = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val role = when (row.opt("role")) {
            "user" -> HistoryRole.USER
            "assistant" -> HistoryRole.ASSISTANT
            else -> return@mapNotNull null
        }
        (text(row, "text") ?: text(row, "content"))?.let { HistoryRow(role, it) }
    }

    private const val MAX_PREVIEW = 160

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }

    private const val FAVOURITES_KEY = "hermes.desktop.favorite-models"

    private fun text(obj: JSONObject, name: String): String? = (obj.opt(name) as? String)?.takeIf { it.isNotBlank() }

    private fun int(obj: JSONObject, name: String): Int? = (obj.opt(name) as? Number)?.toInt()
}
