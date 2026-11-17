package com.hermeswebui.android.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.webapp.MonitorEndpoint
import com.hermeswebui.android.webapp.SignInGate
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A scheduled job, as `/api/cron/jobs` lists it. */
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String?,
    val nextRun: String?,
    val lastStatus: String?,
    val paused: Boolean,
    val profile: String?,
)

/** A skill, a toolset or an MCP server: something Hermes can use, and whether it is on. */
data class Capability(val name: String, val detail: String?, val enabled: Boolean, val note: String? = null)

/** Something the server can install: a catalog plugin or a catalog MCP server. */
data class Installable(
    val name: String,
    val title: String,
    val detail: String?,
    /** Where it comes from, shown before installing: a repository or a command. */
    val source: String?,
    val installed: Boolean,
    val plugin: Boolean,
    /** Why this screen cannot install it (it needs keys or a sign-in); null when it can. */
    val blocked: String? = null,
)

/** What a chat's menu changes; a null field is left as it is. */
data class ChatChange(val title: String? = null, val pinned: Boolean? = null, val archived: Boolean? = null, val unread: Boolean? = null)

/** The counters the server checks a change against, from a chat's mutation snapshot. */
data class ChatRevision(val exists: Boolean, val revision: Int, val generation: Int?)

data class Listing<T>(val rows: List<T> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)

/** The Hermes server's own lists the menu shows, read over its HTTP API. Pure parsing; no Android. */
object HermesAdminProtocol {
    fun cronJobs(reply: Any?): List<CronJob>? {
        val rows = reply as? JSONArray ?: (reply as? JSONObject)?.optJSONArray("jobs") ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = text(row, "id") ?: text(row, "job_id") ?: return@mapNotNull null
            CronJob(
                id = id,
                name = text(row, "name") ?: text(row, "prompt")?.lineSequence()?.firstOrNull()?.take(60) ?: id,
                schedule = text(row, "schedule_display"),
                nextRun = text(row, "next_run_at"),
                lastStatus = text(row, "last_status"),
                paused = row.opt("enabled") == false || row.opt("state") == "paused",
                profile = text(row, "profile") ?: text(row, "profile_name"),
            )
        }
    }

    fun skills(reply: Any?): List<Capability>? {
        val rows = reply as? JSONArray ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = text(row, "name") ?: return@mapNotNull null
            Capability(name, text(row, "description")?.take(160), row.opt("enabled") != false, text(row, "category"))
        }.sortedBy { it.name.lowercase() }
    }

    fun toolsets(reply: Any?): List<Capability>? {
        val rows = reply as? JSONArray ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = text(row, "name") ?: return@mapNotNull null
            val needsSetup = row.opt("enabled") == true && row.opt("configured") == false
            Capability(text(row, "label") ?: name, text(row, "description")?.take(160), row.opt("enabled") == true,
                if (needsSetup) "설정 필요" else null)
        }
    }

    fun mcpServers(reply: Any?): List<Capability>? {
        val rows = (reply as? JSONObject)?.optJSONArray("servers") ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = text(row, "name") ?: return@mapNotNull null
            Capability(name, text(row, "url") ?: text(row, "command"), row.opt("enabled") != false, text(row, "transport"))
        }
    }

    /** `/api/dashboard/plugins/catalog`: the curated plugins, with what each one is granted. */
    fun pluginCatalog(reply: Any?): List<Installable>? {
        val rows = (reply as? JSONObject)?.optJSONArray("entries") ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = text(row, "name") ?: return@mapNotNull null
            Installable(name, text(row, "title") ?: name, (text(row, "capability_summary") ?: text(row, "description"))?.take(400),
                text(row, "repo"), row.opt("installed") == true, plugin = true)
        }
    }

    /** `/api/mcp/catalog`: one that needs keys or a sign-in is listed but left to the Webapp. */
    fun mcpCatalog(reply: Any?): List<Installable>? {
        val rows = (reply as? JSONObject)?.optJSONArray("entries") ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = text(row, "name") ?: return@mapNotNull null
            val env = row.optJSONArray("required_env")
            val needsKeys = env != null && (0 until env.length()).any { env.optJSONObject(it)?.opt("required") != false }
            val auth = text(row, "auth_type") ?: "none"
            val blocked = when {
                needsKeys -> "키 입력이 필요해요"
                auth != "none" && auth != "env" -> "로그인이 필요해요"
                else -> null
            }
            val args = row.optJSONArray("args")?.let { list -> (0 until list.length()).mapNotNull { list.opt(it) as? String } }.orEmpty()
            Installable(name, name, text(row, "description")?.take(400),
                text(row, "url") ?: text(row, "install_url") ?: text(row, "command")?.let { (listOf(it) + args).joinToString(" ").take(160) },
                row.opt("installed") == true, plugin = false, blocked = blocked)
        }
    }

    /*
     * The server commits a change to a chat only with a request id and the revision the client last saw
     * (a change without them is refused with 409), so each one first reads the chat's snapshot.
     */
    fun snapshotPath(id: String, profile: String?): String = "/api/sessions/${enc(id)}/mutation-snapshot" + scope(profile, '?')

    fun revision(reply: Any?): ChatRevision? {
        val row = reply as? JSONObject ?: return null
        return ChatRevision(row.opt("exists") == true, (row.opt("runtime_revision") as? Number)?.toInt() ?: 0, (row.opt("runtime_generation") as? Number)?.toInt())
    }

    fun deletePath(id: String, profile: String?, requestId: String, at: ChatRevision): String =
        "/api/sessions/${enc(id)}?request_id=${enc(requestId)}&expected_revision=${at.revision}" +
            (at.generation?.let { "&expected_generation=$it" } ?: "") + scope(profile, '&')

    fun changePath(id: String): String = "/api/sessions/${enc(id)}"

    fun changeBody(change: ChatChange, profile: String?, requestId: String, at: ChatRevision): JSONObject =
        JSONObject().put("request_id", requestId).put("expected_revision", at.revision).apply {
            at.generation?.let { put("expected_generation", it) }
            change.title?.let { put("title", it) }
            change.pinned?.let { put("pinned", it) }
            change.archived?.let { put("archived", it) }
            change.unread?.let { put("unread", it) }
            profile?.let { put("profile", it) }
        }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun scope(profile: String?, lead: Char) = profile?.let { "${lead}profile=" + enc(it) }.orEmpty()

    private fun text(obj: JSONObject, name: String): String? = (obj.opt(name) as? String)?.takeIf { it.isNotBlank() }
}

/** Reads and changes the menu's lists. Main thread only. */
class HermesAdmin(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val repository = SettingsRepository(context.applicationContext)
    val cron = MutableStateFlow(Listing<CronJob>())
    val skills = MutableStateFlow(Listing<Capability>())
    val toolsets = MutableStateFlow(Listing<Capability>())
    val mcp = MutableStateFlow(Listing<Capability>())
    val plugins = MutableStateFlow(Listing<Installable>())
    val mcpCatalog = MutableStateFlow(Listing<Installable>())
    /** The names being installed, so their buttons wait. */
    val installing = MutableStateFlow(emptySet<String>())
    /** A short notice for the last action ("실행을 요청했어요"), shown once. */
    val notice = MutableStateFlow<String?>(null)

    fun refreshCatalogs(profile: String?) {
        read(plugins, "/api/dashboard/plugins/catalog", HermesAdminProtocol::pluginCatalog)
        read(mcpCatalog, "/api/mcp/catalog" + scope(profile), HermesAdminProtocol::mcpCatalog)
    }

    /** Installs [item] on the server and switches it on. A plugin is code that runs on the Hermes machine. */
    fun install(item: Installable, profile: String?) {
        if (item.installed || item.blocked != null || item.name in installing.value) return
        installing.value = installing.value + item.name
        val (path, body) = if (item.plugin)
            "/api/dashboard/agent-plugins/install" to JSONObject().put("identifier", "").put("catalog_name", item.name).put("enable", true)
        else "/api/mcp/catalog/install" + scope(profile) to JSONObject().put("name", item.name).put("env", JSONObject()).put("enable", true)
            .apply { if (profile != null) put("profile", profile) }
        call("POST", path, body) { reply ->
            installing.value = installing.value - item.name
            val refused = (reply as? JSONObject)?.opt("ok") == false
            notice.value = when {
                reply == null -> "${item.title}: 설치하지 못했어요. Webapp에서 다시 해 보세요"
                refused && (reply as JSONObject).opt("consent_required") == true -> "${item.title}: 권한 동의가 필요해요. Webapp에서 설치해 주세요"
                refused -> "${item.title}: 설치가 거부됐어요"
                else -> "${item.title}: 설치했어요"
            }
            refreshCatalogs(profile)
        } ?: run {
            installing.value = installing.value - item.name
            notice.value = "서버가 설정되지 않았어요"
        }
    }

    /** Deletes a chat on the server for good; [done] gets whether it is gone. */
    fun deleteChat(row: ChatRow, done: (Boolean) -> Unit) = at(row, done, absent = true) { revision ->
        call("DELETE", HermesAdminProtocol.deletePath(row.id, row.profile, newRequestId(), revision)) { reply -> done(reply != null) } ?: done(false)
    }

    /** Renames, pins or archives a chat; [done] gets whether the server took it. */
    fun changeChat(row: ChatRow, change: ChatChange, done: (Boolean) -> Unit) = at(row, done, absent = false) { revision ->
        call("PATCH", HermesAdminProtocol.changePath(row.id), HermesAdminProtocol.changeBody(change, row.profile, newRequestId(), revision)) { reply ->
            done((reply as? JSONObject)?.opt("ok") != false && reply != null)
        } ?: done(false)
    }

    /** Reads [row]'s revision, then runs [then] with it. A chat that is already gone answers [absent]. */
    private fun at(row: ChatRow, done: (Boolean) -> Unit, absent: Boolean, then: (ChatRevision) -> Unit) {
        call("GET", HermesAdminProtocol.snapshotPath(row.id, row.profile)) { reply ->
            val revision = HermesAdminProtocol.revision(reply)
            when {
                revision == null -> done(false)
                !revision.exists -> done(absent)
                else -> then(revision)
            }
        } ?: done(false)
    }

    private fun newRequestId() = java.util.UUID.randomUUID().toString()

    val archived = MutableStateFlow(Listing<ChatRow>())

    fun refreshArchived() = read(archived, AssistantProtocol.ARCHIVED_PATH, AssistantProtocol::archived)

    private fun scope(profile: String?) = profile?.let { "?profile=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()

    fun refreshCron() = read(cron, "/api/cron/jobs?profile=all", HermesAdminProtocol::cronJobs)

    /** The capabilities of [profile] (null: the server's own). */
    fun refreshCapabilities(profile: String?) {
        val scope = profile?.let { "?profile=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        read(skills, "/api/skills$scope", HermesAdminProtocol::skills)
        read(toolsets, "/api/tools/toolsets$scope", HermesAdminProtocol::toolsets)
        read(mcp, "/api/mcp/servers$scope", HermesAdminProtocol::mcpServers)
    }

    fun setCronPaused(job: CronJob, paused: Boolean) =
        act(job, if (paused) "pause" else "resume", if (paused) "작업을 멈췄어요" else "작업을 다시 켰어요")

    fun runCron(job: CronJob) = act(job, "trigger", "실행을 요청했어요")

    private fun act(job: CronJob, verb: String, done: String) {
        val scope = job.profile?.let { "?profile=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val path = "/api/cron/jobs/${java.net.URLEncoder.encode(job.id, "UTF-8")}/$verb$scope"
        call("POST", path) { reply ->
            notice.value = if (reply == null) "요청하지 못했어요" else done
            refreshCron()
        } ?: run { notice.value = "서버가 설정되지 않았어요" }
    }

    /** The newest read of each list; an older reply (another bot's, say) does not overwrite it. */
    private val reads = mutableMapOf<MutableStateFlow<*>, Int>()

    private fun <T> read(target: MutableStateFlow<Listing<T>>, path: String, parse: (Any?) -> List<T>?) {
        target.value = target.value.copy(loading = true, failed = false)
        val read = (reads[target] ?: 0) + 1
        reads[target] = read
        call("GET", path) { reply ->
            if (reads[target] != read) return@call
            val rows = parse(reply)
            target.value = if (rows == null) target.value.copy(loading = false, failed = true) else Listing(rows)
        } ?: run { target.value = target.value.copy(loading = false, failed = true) }
    }

    private fun call(method: String, path: String, body: JSONObject? = null, done: (Any?) -> Unit): SignInGate.Handle? {
        val server = repository.getSettings("", "").serverUrl.takeIf { it.isNotBlank() } ?: return null
        val endpoint = MonitorEndpoint.from(server) ?: return null
        return SignInGate.call(endpoint, method, path, body, server) { reply -> handler.post { done(reply) } }
    }
}
