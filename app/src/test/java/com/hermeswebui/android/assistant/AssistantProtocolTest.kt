package com.hermeswebui.android.assistant

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webapp.GatewayEvent
import com.hermeswebui.android.webapp.TurnOutcome
import org.json.JSONObject
import org.junit.Test

class AssistantProtocolTest {
    @Test
    fun `gateway ready reports the session-authority contract`() {
        assertThat(AssistantProtocol.parse("""{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}"""))
            .isEqualTo(AssistantFrame.Ready(true))
        assertThat(AssistantProtocol.parse("""{"method":"event","params":{"type":"gateway.ready","payload":{}}}"""))
            .isEqualTo(AssistantFrame.Ready(false))
    }

    @Test
    fun `reply chunks keep their segment and offset, and empty boundaries are dropped`() {
        assertThat(AssistantProtocol.parse(
            """{"method":"event","params":{"type":"message.delta","session_id":"s","payload":{"text":"Hi","segment":1,"offset":4}}}"""
        )).isEqualTo(AssistantFrame.Delta("s", "Hi", 1, 4))
        assertThat(AssistantProtocol.parse(
            """{"method":"event","params":{"type":"message.delta","session_id":"s","payload":{"text":null}}}"""
        )).isNull()
    }

    @Test
    fun `turn ends, approvals and window requests are recognised`() {
        assertThat(AssistantProtocol.parse(
            """{"method":"event","params":{"type":"message.complete","session_id":"s","payload":{"text":"Done","status":"complete"}}}"""
        )).isEqualTo(AssistantFrame.Completed("s", TurnOutcome.COMPLETE, "Done", null))
        val approval = AssistantProtocol.parse(
            """{"jsonrpc":"2.0","id":"a1","method":"approval","params":{"session_id":"s","command":"ls","choices":["once","deny"]}}"""
        ) as AssistantFrame.Request
        assertThat((approval.request as GatewayEvent.ApprovalRequested).command).isEqualTo("ls")
        assertThat(AssistantProtocol.parse("""{"jsonrpc":"2.0","id":5,"method":"preview.read","params":{}}"""))
            .isInstanceOf(AssistantFrame.WindowRequest::class.java)
    }

    @Test
    fun `a transcript reply yields the newest answer and the question it answered`() {
        val reply = AssistantProtocol.parse(
            """{"jsonrpc":"2.0","id":3,"result":{"session_id":"live","stored_session_id":"stored","running":false,"messages":[
                {"role":"user","text":"first"},{"role":"assistant","text":"one"},
                {"role":"user","text":"second"},{"role":"tool","text":"x"},{"role":"assistant","text":"two"}]}}"""
        ) as AssistantFrame.Reply
        assertThat(reply.ok).isTrue()
        assertThat(reply.sessionId).isEqualTo("live")
        assertThat(reply.storedId).isEqualTo("stored")
        assertThat(reply.lastAnswer).isEqualTo("two")
        assertThat(reply.lastQuestion).isEqualTo("second")
        val error = AssistantProtocol.parse("""{"jsonrpc":"2.0","id":4,"error":{"code":4000,"message":"bad"}}""") as AssistantFrame.Reply
        assertThat(error.ok).isFalse()
        assertThat(error.errorCode).isEqualTo(4000)
    }

    @Test
    fun `an image upload reply carries its path and type, and a refusal its message`() {
        val ok = AssistantProtocol.parse("""{"jsonrpc":"2.0","id":8,"result":{"attached":true,"path":"/u/a.jpg","mime":"image/jpeg"}}""") as AssistantFrame.Reply
        assertThat(ok.ok).isTrue()
        assertThat(ok.path).isEqualTo("/u/a.jpg")
        assertThat(ok.mime).isEqualTo("image/jpeg")
        val refused = AssistantProtocol.parse("""{"jsonrpc":"2.0","id":9,"result":{"attached":false,"message":"too big"}}""") as AssistantFrame.Reply
        assertThat(refused.ok).isFalse()
        assertThat(refused.errorMessage).isEqualTo("too big")
    }

    @Test
    fun `an external start names only a known mode`() {
        assertThat(AssistantActivity.parseMode("Translate")).isEqualTo(AssistantMode.TRANSLATE)
        assertThat(AssistantActivity.parseMode("summarize")).isEqualTo(AssistantMode.SUMMARY)
        assertThat(AssistantActivity.parseMode("delete everything")).isNull()
    }

    @Test
    fun `favourite models are read from Hermes's shared preferences in starred order`() {
        val stored = JSONObject().put("values", JSONObject().put("hermes.desktop.favorite-models",
            """["anthropic::claude-opus-5-5","bad","openai-codex::gpt-6.1-sol","anthropic::claude-opus-5-5"]"""))
        assertThat(AssistantProtocol.favourites(stored)).containsExactly(
            AssistantModel("anthropic", "claude-opus-5-5"),
            AssistantModel("openai-codex", "gpt-6.1-sol"),
        ).inOrder()
        assertThat(AssistantProtocol.favourites(JSONObject().put("values", JSONObject()))).isEmpty()
    }

    @Test
    fun `a chosen model and reasoning are pinned on the new chat only`() {
        val setup = ChatSetup(AssistantModel("anthropic", "claude-opus-5-5[1m]"), "high")
        val params = JSONObject(AssistantProtocol.create(3, false, "r", setup)).getJSONObject("params")
        assertThat(params.getString("model")).isEqualTo("claude-opus-5-5[1m]")
        assertThat(params.getString("provider")).isEqualTo("anthropic")
        assertThat(params.getString("reasoning_effort")).isEqualTo("high")
        val plain = JSONObject(AssistantProtocol.create(3, false, "r")).getJSONObject("params")
        assertThat(plain.has("model") || plain.has("reasoning_effort")).isFalse()
    }

    @Test
    fun `the model list keeps signed-in providers and drops unavailable models`() {
        val options = JSONObject("""{"providers":[
            {"slug":"anthropic","name":"Anthropic","authenticated":true,"models":["claude-opus-5-5","claude-old"],"unavailable_models":["claude-old"]},
            {"slug":"xai","name":"xAI","authenticated":false,"models":["grok-4.7"]},
            {"slug":"empty","name":"Empty","models":[]}]}""")
        assertThat(AssistantProtocol.catalog(options))
            .containsExactly(CatalogProvider("anthropic", "Anthropic", listOf(AssistantModel("anthropic", "claude-opus-5-5"))))
    }

    @Test
    fun `model ids read as short names`() {
        assertThat(modelName("claude-opus-5-5[1m]")).isEqualTo("Opus 5.5 · 1M")
        assertThat(modelName("gpt-6.1-sol")).isEqualTo("GPT 6.1 Sol")
        assertThat(modelName("gemini-4-argon")).isEqualTo("Gemini 4 Argon")
    }

    @Test
    fun `the sidebar feed becomes chat rows, a transcript reply history rows, and the roster bots`() {
        val list = AssistantProtocol.sidebar(JSONObject("""{"recents":{"sessions":[
            {"id":"a","title":"Trip","preview":"**first** `line`","last_active":1700000000.5,"pinned":true,"profile":"default","is_default_profile":true,"is_active":false},
            {"id":"b","title":"","started_at":12,"is_active":true,"profile":"patch-pilot","is_default_profile":false},{"title":"no id"}]},"cron":{}}"""))
        assertThat(list).containsExactly(
            ChatRow("a", "Trip", "first line", 1700000000.5, pinned = true, profile = "default"),
            ChatRow("b", null, null, 12.0, running = true, profile = "patch-pilot", defaultProfile = false),
        ).inOrder()
        assertThat(AssistantProtocol.sidebar(JSONObject("{}"))).isNull()

        val resumed = AssistantProtocol.parse("""{"jsonrpc":"2.0","id":5,"result":{"session_id":"live","messages":[
            {"role":"user","text":"hello"},{"role":"tool","name":"terminal","context":"ls -la"},
            {"role":"assistant","text":"hi"},{"role":"assistant","text":"  "},{"role":"system","text":"note"}]}}""") as AssistantFrame.Reply
        assertThat(resumed.history).containsExactly(HistoryRow(HistoryRole.USER, "hello"), HistoryRow(HistoryRole.ASSISTANT, "hi")).inOrder()

        val roster = AssistantProtocol.parse("""{"jsonrpc":"2.0","id":6,"result":{"profiles":[
            {"name":"default","display_name":"Main","has_avatar":true},
            {"name":"patch-pilot","display_name":"x","ui_meta":{"hermes-bots":{"title":"Pilot"}},"has_avatar":false},{"name":"bare"}]}}""") as AssistantFrame.Reply
        assertThat(roster.profiles).containsExactly(
            ProfileRow("default", "Main", own = true), ProfileRow("patch-pilot", "Pilot", hasAvatar = false), ProfileRow("bare", "bare"),
        ).inOrder()
        val asset = AssistantProtocol.parse("""{"jsonrpc":"2.0","id":7,"result":{"found":true,"data":"data:image/png;base64,AA"}}""") as AssistantFrame.Reply
        assertThat(asset.asset).isEqualTo("data:image/png;base64,AA")
        assertThat(JSONObject(AssistantProtocol.resume(1, true, "s", "bot")).getJSONObject("params").getString("profile")).isEqualTo("bot")
    }
}
