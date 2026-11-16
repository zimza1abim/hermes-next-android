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
    /** A short notice for the last action ("실행을 요청했어요"), shown once. */
    val notice = MutableStateFlow<String?>(null)

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

    private fun <T> read(target: MutableStateFlow<Listing<T>>, path: String, parse: (Any?) -> List<T>?) {
        target.value = target.value.copy(loading = true, failed = false)
        call("GET", path) { reply ->
            val rows = parse(reply)
            target.value = if (rows == null) target.value.copy(loading = false, failed = true) else Listing(rows)
        } ?: run { target.value = target.value.copy(loading = false, failed = true) }
    }

    private fun call(method: String, path: String, done: (Any?) -> Unit): SignInGate.Handle? {
        val server = repository.getSettings("", "").serverUrl.takeIf { it.isNotBlank() } ?: return null
        val endpoint = MonitorEndpoint.from(server) ?: return null
        return SignInGate.call(endpoint, method, path, null, server) { reply -> handler.post { done(reply) } }
    }
}
