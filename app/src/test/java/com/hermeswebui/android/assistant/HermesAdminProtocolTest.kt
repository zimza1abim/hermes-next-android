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
}
