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
    fun `a chosen model is pinned on the new chat only`() {
        val params = JSONObject(AssistantProtocol.create(3, false, "r", AssistantModel("anthropic", "claude-opus-5-5[1m]"))).getJSONObject("params")
        assertThat(params.getString("model")).isEqualTo("claude-opus-5-5[1m]")
        assertThat(params.getString("provider")).isEqualTo("anthropic")
        assertThat(JSONObject(AssistantProtocol.create(3, false, "r")).getJSONObject("params").has("model")).isFalse()
    }

    @Test
    fun `model ids read as short names`() {
        assertThat(modelName("claude-opus-5-5[1m]")).isEqualTo("Opus 5.5 · 1M")
        assertThat(modelName("gpt-6.1-sol")).isEqualTo("GPT 6.1 Sol")
        assertThat(modelName("gemini-4-argon")).isEqualTo("Gemini 4 Argon")
    }
}
