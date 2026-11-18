package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 자막은 소리보다 먼저 나오면 안 된다. 단어별 시각이 API에 없으므로 "여기까지는 말했다"는 두 근거
 * (받은 오디오 길이, 그리고 글자당 하한)만 쓴다. 둘 다 늦는 쪽으로 틀리기 때문에, 규칙이 지켜지는지
 * 시각 없이 숫자로 확인할 수 있다.
 */
class CaptionTimelineTest {

    @Test
    fun `nothing is revealed before any audio has played`() {
        val timeline = CaptionTimeline()
        timeline.onAudio(frames = 2_400, sampleRate = 24_000, enqueuedBefore = 0)
        timeline.onTranscript("안녕 나야 오늘 어땠어", enqueuedNow = 2_400)

        assertThat(timeline.revealedChars(playedFrames = 0)).isEqualTo(0)
    }

    @Test
    fun `revealed letters never run ahead of what was heard`() {
        val timeline = CaptionTimeline()
        val text = "오늘 하루도 정말 고생 많았어 푹 쉬자"
        // 자막이 오디오보다 먼저 도착한 최악의 경우(앵커가 0ms에 몰림)
        timeline.onTranscript(text, enqueuedNow = 0)
        timeline.onAudio(frames = 24_000 * 3, sampleRate = 24_000, enqueuedBefore = 0)

        var last = -1
        for (played in 0..24_000 * 3 step 2_400) {
            val revealed = timeline.revealedChars(played.toLong())
            val heardMs = played / 24f
            assertThat(revealed).isAtLeast(last)
            // 나온 글자는 모두 자기 추정 시작 시각이 지난 글자여야 한다: 자막이 소리를 앞지르지 않는다.
            for (i in 0 until revealed) {
                assertThat(timeline.estimatedStartMs(i)).isLessThan(heardMs)
            }
            if (revealed < text.length) {
                assertThat(timeline.estimatedStartMs(revealed)).isAtLeast(heardMs)
            }
            last = revealed
        }
        assertThat(timeline.revealedChars(24_000L * 3)).isGreaterThan(0)
    }

    @Test
    fun `an interruption keeps the text and how much of it was heard`() {
        val timeline = CaptionTimeline()
        timeline.onAudio(24_000, 24_000, 0)
        timeline.onTranscript("오늘 하루 정말 고생 많았어 푹 쉬자", 24_000)
        val snapshot = timeline.snapshot(12_000)
        assertThat(snapshot.text).startsWith("오늘 하루")
        assertThat(snapshot.revealedChars).isLessThan(snapshot.text.length)
    }

    @Test
    fun `a new turn starts from that turn's own audio`() {
        val timeline = CaptionTimeline()
        timeline.onAudio(24_000, 24_000, 0)
        timeline.onTranscript("첫 번째", 24_000)
        timeline.endTurn()

        // 두 번째 턴은 이미 3초가 재생된 상태에서 시작한다.
        timeline.onAudio(24_000, 24_000, 72_000)
        timeline.onTranscript("두 번째", 96_000)
        assertThat(timeline.currentText()).isEqualTo("두 번째")
        // 이번 턴 기준으로는 아직 아무것도 안 들렸다.
        assertThat(timeline.revealedChars(72_000)).isEqualTo(0)
    }
}
