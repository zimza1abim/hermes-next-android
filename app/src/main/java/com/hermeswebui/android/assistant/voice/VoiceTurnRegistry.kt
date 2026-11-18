package com.hermeswebui.android.assistant.voice

/**
 * Pairs a finished chat turn with the call that asked for it.
 *
 * The question goes to Hermes with a submission id the call chose (`voice-<callId>`); the answer comes
 * back as a chat turn with the same id. This map is that pairing — one entry per outstanding question —
 * and it is the only place a result is handed to a call. Main thread only, like the controller.
 */
class VoiceTurnRegistry {

    private val waiting = mutableMapOf<String, (VoiceTurn) -> Unit>()

    fun expect(submissionId: String, done: (VoiceTurn) -> Unit) {
        waiting[submissionId] = done
    }

    fun isWaiting(submissionId: String): Boolean = waiting.containsKey(submissionId)

    val size: Int get() = waiting.size

    /** Removes and returns the waiter for [submissionId]; an unknown id yields null and touches nothing. */
    fun take(submissionId: String): ((VoiceTurn) -> Unit)? = waiting.remove(submissionId)

    fun clear() = waiting.clear()
}
