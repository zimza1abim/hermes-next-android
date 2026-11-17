package com.hermeswebui.android.assistant

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webapp.TicketResult
import com.hermeswebui.android.webapp.TurnOutcome
import org.json.JSONObject
import org.junit.Test

class AssistantMachineTest {
    private var state = AssistantState()
    private val sent = mutableListOf<JSONObject>()
    private val effects = mutableListOf<AssistantEffect>()

    private fun feed(vararg inputs: AssistantInput) {
        inputs.forEach { input ->
            val (next, out) = AssistantMachine.reduce(state, input)
            state = next
            effects += out
            out.filterIsInstance<AssistantEffect.Send>().forEach { sent += JSONObject(it.frame) }
        }
    }

    private fun gen() = state.gen
    private fun frame(frame: AssistantFrame) = AssistantInput.Received(gen(), frame)
    private fun lastCall(method: String) = sent.last { it.optString("method") == method }
    private fun reply(method: String, result: AssistantFrame.Reply.() -> AssistantFrame.Reply = { this }) =
        frame(AssistantFrame.Reply(lastCall(method).getInt("id")).result())

    /** Panel shown, socket ready on a server of the given contract. */
    private fun connect(authority: Boolean = true) {
        feed(AssistantInput.Shown(true))
        feed(AssistantInput.Ticket(gen(), TicketResult("t")))
        feed(AssistantInput.Opened(gen()))
        feed(frame(AssistantFrame.Ready(authority)))
    }

    /** The retry fires and a new socket comes up; each step reads the generation the previous one made. */
    private fun reconnect(authority: Boolean) {
        feed(AssistantInput.RetryDue(gen()))
        feed(AssistantInput.Ticket(gen(), TicketResult("t")))
        feed(AssistantInput.Opened(gen()))
        feed(frame(AssistantFrame.Ready(authority)))
    }

    private fun ask(text: String = "hello", id: String = "turn-1") =
        feed(AssistantInput.Ask(id, AssistantMode.ASK, text, text))

    private fun created() = feed(reply("session.create") { copy(sessionId = "live-1", storedId = "stored-1") })

    @Test
    fun `a question creates the chat, is submitted with its own id and streams to a final answer`() {
        connect()
        assertThat(sent.map { it.getString("method") }).containsExactly("client.capabilities")
        ask()
        assertThat(lastCall("session.create").getJSONObject("params").has("request_id")).isTrue()
        created()
        val submit = lastCall("prompt.submit").getJSONObject("params")
        assertThat(submit.getString("session_id")).isEqualTo("live-1")
        assertThat(submit.getString("submission_id")).isEqualTo("turn-1")
        feed(reply("prompt.submit"))
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.ACCEPTED)

        feed(
            frame(AssistantFrame.Delta("live-1", "Hel", segment = 0, offset = 0)),
            frame(AssistantFrame.Delta("live-1", "Hel", segment = 0, offset = 0)),
            frame(AssistantFrame.Delta("live-1", "lo", segment = 0, offset = 3)),
            frame(AssistantFrame.Delta("other-chat", "noise", segment = 0, offset = 0)),
        )
        assertThat(state.turns.single().answer).isEqualTo("Hello")
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.STREAMING)
        feed(frame(AssistantFrame.Completed("live-1", TurnOutcome.COMPLETE, "Hello there", null)))
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.DONE)
        assertThat(state.turns.single().answer).isEqualTo("Hello there")
    }

    @Test
    fun `one question runs at a time and the next waits for the first to finish`() {
        connect()
        ask("one", "a")
        created()
        ask("two", "b")
        assertThat(sent.count { it.optString("method") == "prompt.submit" }).isEqualTo(1)
        assertThat(state.turns.last().status).isEqualTo(TurnStatus.QUEUED)
        feed(frame(AssistantFrame.Completed("live-1", TurnOutcome.COMPLETE, "done", null)))
        assertThat(lastCall("prompt.submit").getJSONObject("params").getString("submission_id")).isEqualTo("b")
    }

    @Test
    fun `a legacy server refusing the submission id gets the question once more without it`() {
        connect(authority = false)
        ask()
        assertThat(lastCall("session.create").getJSONObject("params").has("request_id")).isFalse()
        created()
        feed(reply("prompt.submit") { copy(errorCode = 4000, errorMessage = "invalid params") })
        assertThat(lastCall("prompt.submit").getJSONObject("params").has("submission_id")).isFalse()
        feed(reply("prompt.submit") { copy(errorCode = 4000, errorMessage = "invalid params") })
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.FAILED)
    }

    @Test
    fun `a question whose receipt was lost is resent with the same id only on a canonical server`() {
        connect(authority = true)
        ask()
        created()
        feed(AssistantInput.Closed(gen()))
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.UNKNOWN)
        reconnect(true)
        assertThat(lastCall("session.resume").getJSONObject("params").getString("session_id")).isEqualTo("stored-1")
        feed(reply("session.resume") { copy(sessionId = "live-2", storedId = "stored-1", running = false) })
        val resent = lastCall("prompt.submit").getJSONObject("params")
        assertThat(resent.getString("submission_id")).isEqualTo("turn-1")
        assertThat(resent.getString("session_id")).isEqualTo("live-2")
    }

    @Test
    fun `on a legacy server a lost receipt is settled from the transcript, never by resending`() {
        connect(authority = false)
        ask("what time is it")
        created()
        feed(AssistantInput.Closed(gen()))
        reconnect(false)
        val submits = sent.count { it.optString("method") == "prompt.submit" }
        feed(reply("session.resume") {
            copy(sessionId = "live-2", running = false, lastQuestion = "what time is it", lastAnswer = "Noon")
        })
        assertThat(sent.count { it.optString("method") == "prompt.submit" }).isEqualTo(submits)
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.DONE)
        assertThat(state.turns.single().answer).isEqualTo("Noon")
    }

    @Test
    fun `closing the panel keeps the socket for a running answer, then lingers before closing`() {
        connect()
        ask()
        created()
        feed(AssistantInput.Shown(false))
        assertThat(effects.filterIsInstance<AssistantEffect.Close>()).isEmpty()
        feed(frame(AssistantFrame.Completed("live-1", TurnOutcome.COMPLETE, "ok", null)))
        val linger = effects.filterIsInstance<AssistantEffect.LingerAfter>().single()
        assertThat(state.link).isEqualTo(AssistantLink.Ready)
        feed(AssistantInput.LingerDue(linger.token))
        assertThat(state.link).isEqualTo(AssistantLink.Idle)
        assertThat(effects.last()).isInstanceOf(AssistantEffect.Close::class.java)
    }

    @Test
    fun `reopening during the linger reuses the socket and a stale timer cannot close it`() {
        connect()
        feed(AssistantInput.Shown(false))
        val linger = effects.filterIsInstance<AssistantEffect.LingerAfter>().single()
        val mints = effects.count { it is AssistantEffect.Mint }
        feed(AssistantInput.Shown(true))
        assertThat(effects.count { it is AssistantEffect.Mint }).isEqualTo(mints)
        feed(AssistantInput.LingerDue(linger.token))
        assertThat(state.link).isEqualTo(AssistantLink.Ready)
        assertThat(effects.none { it is AssistantEffect.Close }).isTrue()
    }

    @Test
    fun `a refused sign-in waits for the panel to open again`() {
        feed(AssistantInput.Shown(true))
        feed(AssistantInput.Ticket(gen(), TicketResult(null, signedOut = true)))
        assertThat(state.link).isEqualTo(AssistantLink.SignedOut)
        feed(AssistantInput.Shown(false), AssistantInput.Shown(true))
        assertThat(state.link).isInstanceOf(AssistantLink.Minting::class.java)
    }

    @Test
    fun `stop interrupts the running turn and drops queued ones`() {
        connect()
        ask("one", "a")
        created()
        ask("two", "b")
        feed(AssistantInput.Stop)
        assertThat(lastCall("session.interrupt").getJSONObject("params").getString("session_id")).isEqualTo("live-1")
        assertThat(state.turns.last().status).isEqualTo(TurnStatus.STOPPED)
    }

    private fun attachIds() = effects.filterIsInstance<AssistantEffect.Attach>()

    @Test
    fun `photos are uploaded to the new chat first and named in the question on a canonical server`() {
        connect()
        feed(AssistantInput.Ask("turn-1", AssistantMode.ASK, "what flower", "what flower", images = listOf("p1", "p2")))
        created()
        assertThat(attachIds().map { it.imageId to it.sessionId }).containsExactly("p1" to "live-1", "p2" to "live-1")
        assertThat(sent.none { it.optString("method") == "prompt.submit" }).isTrue()
        val (first, second) = attachIds()
        feed(frame(AssistantFrame.Reply(first.id, attached = true, path = "/up/p1.jpg", mime = "image/jpeg")))
        assertThat(sent.none { it.optString("method") == "prompt.submit" }).isTrue()
        feed(frame(AssistantFrame.Reply(second.id, attached = true, path = "/up/p2.jpg", mime = "image/jpeg")))
        val attachments = lastCall("prompt.submit").getJSONObject("params").getJSONArray("attachments")
        assertThat((0 until attachments.length()).map { attachments.getJSONObject(it).getString("path") })
            .containsExactly("/up/p1.jpg", "/up/p2.jpg").inOrder()
    }

    @Test
    fun `a legacy server keeps uploaded photos on the session, so the question names none`() {
        connect(authority = false)
        feed(AssistantInput.Ask("turn-1", AssistantMode.ASK, "", "describe", images = listOf("p1")))
        created()
        feed(frame(AssistantFrame.Reply(attachIds().single().id, attached = true, path = "/tmp/p1.jpg")))
        assertThat(lastCall("prompt.submit").getJSONObject("params").has("attachments")).isFalse()
    }

    @Test
    fun `a refused photo fails the question instead of sending it without the photo`() {
        connect()
        feed(AssistantInput.Ask("turn-1", AssistantMode.ASK, "what", "what", images = listOf("p1")))
        created()
        feed(frame(AssistantFrame.Reply(attachIds().single().id, attached = false, errorMessage = "too large")))
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.FAILED)
        assertThat(sent.none { it.optString("method") == "prompt.submit" }).isTrue()
    }

    @Test
    fun `asking a failed question again replaces it with a fresh turn`() {
        connect()
        ask("hello", "a")
        created()
        feed(reply("prompt.submit") { copy(errorCode = 5000, errorMessage = "boom") })
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.FAILED)
        feed(AssistantInput.Again("a", "b"))
        assertThat(state.turns.map { it.id }).containsExactly("b")
        assertThat(lastCall("prompt.submit").getJSONObject("params").getString("submission_id")).isEqualTo("b")
        // A turn that is still running cannot be asked again.
        feed(AssistantInput.Again("b", "c"))
        assertThat(state.turns.map { it.id }).containsExactly("b")
    }

    @Test
    fun `a chunk sent again is not appended twice`() {
        val first = AssistantMachine.appendChunk(emptyMap(), AssistantFrame.Delta("s", "abc", 0, 0))
        val overlap = AssistantMachine.appendChunk(first, AssistantFrame.Delta("s", "bcd", 0, 1))
        assertThat(overlap[0]).isEqualTo("abcd")
        val next = AssistantMachine.appendChunk(overlap, AssistantFrame.Delta("s", "x", 1, 0))
        assertThat(next).containsExactly(0, "abcd", 1, "x")
    }

    @Test
    fun `the bots are read once the socket is up, each with its picture, and a chat of another bot names its profile`() {
        feed(AssistantInput.RefreshProfiles)
        assertThat(state.wanted).isTrue()
        feed(AssistantInput.Ticket(gen(), TicketResult("t")), AssistantInput.Opened(gen()))
        feed(frame(AssistantFrame.Ready(true)))
        val rows = listOf(ProfileRow("default", "Hermes", own = true), ProfileRow("patch-pilot", "Pilot", hasAvatar = false))
        feed(reply("profiles.list") { copy(profiles = rows) })
        assertThat(state.profiles).isEqualTo(rows)
        val asked = sent.filter { it.optString("method") == "profiles.get_asset" }
        assertThat(asked.map { it.getJSONObject("params").getString("name") }).containsExactly("default")
        feed(frame(AssistantFrame.Reply(asked.single().getInt("id"), asset = "data:image/png;base64,AAAA")))
        assertThat(effects).contains(AssistantEffect.Avatar("default", "data:image/png;base64,AAAA"))

        feed(AssistantInput.Shown(true), AssistantInput.OpenChat("s-1", "Fix", profile = "patch-pilot"))
        assertThat(lastCall("session.resume").getJSONObject("params").getString("profile")).isEqualTo("patch-pilot")
        assertThat(state.bot).isEqualTo(rows[1])
        feed(reply("session.resume") { copy(sessionId = "live-1", storedId = "s-1b", history = emptyList()) })
        ask("go")
        assertThat(lastCall("prompt.submit").getJSONObject("params").getString("profile")).isEqualTo("patch-pilot")
        // The same list row again is the chat already open, whatever durable id the server answered with.
        val resumes = sent.count { it.optString("method") == "session.resume" }
        feed(frame(AssistantFrame.Completed("live-1", TurnOutcome.COMPLETE, "done", null)))
        feed(AssistantInput.OpenChat("s-1", "Fix", profile = "patch-pilot"))
        assertThat(sent.count { it.optString("method") == "session.resume" }).isEqualTo(resumes)
        assertThat(state.turns).hasSize(1)

        feed(AssistantInput.SelectProfile(null))
        assertThat(state.profile).isNull()
        assertThat(state.turns).isEmpty()
        ask("hello", id = "turn-2")
        assertThat(lastCall("session.create").getJSONObject("params").has("profile")).isFalse()
    }

    @Test
    fun `opening an earlier chat attaches to it and shows its transcript, then a question goes to that chat`() {
        connect()
        feed(AssistantInput.OpenChat("stored-9", "Old chat"))
        assertThat(state.opening).isTrue()
        assertThat(lastCall("session.resume").getJSONObject("params").getString("session_id")).isEqualTo("stored-9")
        val history = listOf(HistoryRow(HistoryRole.USER, "q"), HistoryRow(HistoryRole.ASSISTANT, "a"))
        feed(reply("session.resume") { copy(sessionId = "live-9", storedId = "stored-9", history = history) })
        assertThat(state.opening).isFalse()
        assertThat(state.history).isEqualTo(history)
        assertThat(state.turns).isEmpty()
        assertThat(state.title).isEqualTo("Old chat")

        ask("more")
        assertThat(sent.none { it.optString("method") == "session.create" }).isTrue()
        assertThat(lastCall("prompt.submit").getJSONObject("params").getString("session_id")).isEqualTo("live-9")
        // A reconnect resumes the same chat; its transcript now repeats this screen's turn and is not taken again.
        feed(AssistantInput.Closed(gen()))
        reconnect(true)
        feed(reply("session.resume") { copy(sessionId = "live-10", storedId = "stored-9", history = history + HistoryRow(HistoryRole.USER, "more")) })
        assertThat(state.history).isEqualTo(history)

        // A running turn keeps its chat.
        feed(AssistantInput.OpenChat("stored-2", null))
        assertThat(state.storedId).isEqualTo("stored-9")
    }

    @Test
    fun `the chats screen can leave a chat whose answer is being written`() {
        connect()
        feed(AssistantInput.OpenChat("stored-9", null))
        feed(reply("session.resume") { copy(sessionId = "live-9", storedId = "stored-9", history = emptyList()) })
        ask("again")
        feed(reply("prompt.submit"))
        assertThat(state.busy).isTrue()
        feed(AssistantInput.OpenChat("stored-2", null))
        assertThat(state.storedId).isEqualTo("stored-9")
        feed(AssistantInput.OpenChat("stored-2", null, leave = true))
        assertThat(state.storedId).isEqualTo("stored-2")
        assertThat(state.turns).isEmpty()
        // The answer is not stopped; it goes on at the server and no longer writes into this chat.
        assertThat(sent.none { it.optString("method") == "session.interrupt" }).isTrue()
        feed(frame(AssistantFrame.Delta("live-9", "late", 0, 0)))
        assertThat(state.turns).isEmpty()
    }

    @Test
    fun `a question not sent yet keeps its chat even when asked to leave`() {
        feed(AssistantInput.Shown(true))
        ask("first")
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.QUEUED)
        feed(AssistantInput.OpenChat("stored-2", null, leave = true))
        assertThat(state.storedId).isNull()
        assertThat(state.turns).hasSize(1)
    }

    @Test
    fun `a turn this screen did not send is followed in the opened chat`() {
        connect()
        feed(AssistantInput.OpenChat("stored-9", null))
        feed(reply("session.resume") { copy(sessionId = "live-9", storedId = "stored-9", running = true, history = listOf(HistoryRow(HistoryRole.USER, "q"))) })
        assertThat(state.turns.single().status).isEqualTo(TurnStatus.STREAMING)
        assertThat(state.turns.single().question).isEmpty()
        feed(frame(AssistantFrame.Delta("live-9", "partial", 0, 0)))
        feed(frame(AssistantFrame.Completed("live-9", TurnOutcome.COMPLETE, "whole", null)))
        assertThat(state.turns.single().answer).isEqualTo("whole")

        feed(frame(AssistantFrame.Started("live-9")))
        assertThat(state.turns).hasSize(2)
        feed(frame(AssistantFrame.Started("other")))
        assertThat(state.turns).hasSize(2)
    }

    @Test
    fun `a new chat leaves the opened one`() {
        connect()
        feed(AssistantInput.OpenChat("stored-9", "Old"))
        feed(reply("session.resume") { copy(sessionId = "live-9", storedId = "stored-9", history = listOf(HistoryRow(HistoryRole.USER, "q"))) })
        feed(AssistantInput.NewChat)
        assertThat(state.history).isEmpty()
        assertThat(state.storedId).isNull()
        assertThat(state.title).isNull()
    }
}
