package com.hermeswebui.android.assistant

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class HermesAdminProtocolTest {
    @Test
    fun `cron jobs keep their profile and read a paused state from either field`() {
        val jobs = HermesAdminProtocol.cronJobs(JSONArray("""[
            {"id":"a","name":"Morning","schedule_display":"every day 09:00","next_run_at":"2026-11-17T09:00:00+09:00","last_status":"ok","enabled":true,"state":"scheduled","profile":"usage-watch"},
            {"id":"b","prompt":"check mail\nthen report","enabled":true,"state":"paused"},{"id":"c","name":"Off","enabled":false},{"name":"no id"}]"""))
        assertThat(jobs).containsExactly(
            CronJob("a", "Morning", "every day 09:00", "2026-11-17T09:00:00+09:00", "ok", paused = false, profile = "usage-watch"),
            CronJob("b", "check mail", null, null, null, paused = true, profile = null),
            CronJob("c", "Off", null, null, null, paused = true, profile = null),
        ).inOrder()
        assertThat(HermesAdminProtocol.cronJobs(null)).isNull()
        assertThat(shortTime("2026-11-17T09:00:00+09:00")).isEqualTo("11-17 09:00")
    }

    @Test
    fun `skills, toolsets and MCP servers become capability rows`() {
        assertThat(HermesAdminProtocol.skills(JSONArray("""[{"name":"zeta","enabled":false},{"name":"Alpha","description":"does a thing","category":"dev"}]""")))
            .containsExactly(Capability("Alpha", "does a thing", true, "dev"), Capability("zeta", null, false)).inOrder()
        assertThat(HermesAdminProtocol.toolsets(JSONArray("""[{"name":"web","label":"Web","description":"search","enabled":true,"configured":false},{"name":"x","enabled":false}]""")))
            .containsExactly(Capability("Web", "search", true, "설정 필요"), Capability("x", null, false)).inOrder()
        assertThat(HermesAdminProtocol.mcpServers(JSONObject("""{"servers":[{"name":"files","command":"npx files","enabled":true}]}""")))
            .containsExactly(Capability("files", "npx files", true))
        assertThat(HermesAdminProtocol.mcpServers(JSONArray("[]"))).isNull()
    }

    @Test
    fun `a change to a chat carries the request id and the revision the snapshot gave`() {
        val at = HermesAdminProtocol.revision(JSONObject("""{"session_id":"a b","exists":true,"runtime_revision":7,"runtime_generation":2}"""))!!
        assertThat(at).isEqualTo(ChatRevision(true, 7, 2))
        assertThat(HermesAdminProtocol.snapshotPath("a b", "nova")).isEqualTo("/api/sessions/a+b/mutation-snapshot?profile=nova")
        assertThat(HermesAdminProtocol.deletePath("a b", "nova", "r1", at))
            .isEqualTo("/api/sessions/a+b?request_id=r1&expected_revision=7&expected_generation=2&profile=nova")
        assertThat(HermesAdminProtocol.deletePath("x", null, "r1", ChatRevision(true, 0, null))).isEqualTo("/api/sessions/x?request_id=r1&expected_revision=0")
        val body = HermesAdminProtocol.changeBody(ChatChange(title = "새 이름", pinned = true), "nova", "r2", at)
        assertThat(body.toString()).isEqualTo(JSONObject("""{"request_id":"r2","expected_revision":7,"expected_generation":2,"title":"새 이름","pinned":true,"profile":"nova"}""").toString())
        assertThat(HermesAdminProtocol.changeBody(ChatChange(archived = false), null, "r3", ChatRevision(true, 1, null)).has("title")).isFalse()
        assertThat(HermesAdminProtocol.revision(JSONObject("""{"exists":false}"""))).isEqualTo(ChatRevision(false, 0, null))
    }
}
