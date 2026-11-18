package com.hermeswebui.android.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssistantMediaTest {
    private fun check(text: String, wantText: String, vararg wantSources: String) {
        val result = AssistantMedia.extract(text)
        assertThat(result.text).isEqualTo(wantText)
        assertThat(result.sources).containsExactlyElementsIn(wantSources.toList()).inOrder()
    }

    @Test
    fun `a photo the user sent is taken out of the words`() {
        check("이거 뭐야?\n@image:/Users/x/.hermes/images/a b.png", "이거 뭐야?", "/Users/x/.hermes/images/a b.png")
        check("@image:`/tmp/q.jpg`\n[screenshot]\n설명", "설명", "/tmp/q.jpg")
    }

    @Test
    fun `Markdown images, delivered image files and inline data become pictures`() {
        val data = "data:image/png;base64," + "A".repeat(80)
        check("여기 그림:\n\n![고양이](https://fal.media/x.png)\n\n끝", "여기 그림:\n\n끝", "https://fal.media/x.png")
        check("만들었어요 MEDIA:/Users/x/.hermes/cache/out.webp", "만들었어요", "/Users/x/.hermes/cache/out.webp")
        check("사진\n$data", "사진", data)
        check("![]($data)", "", data)
        check("@image:/a.png\n![](/a.png)", "", "/a.png")
    }

    @Test
    fun `text without images and files that are not images stay as they are`() {
        check("그냥 글", "그냥 글")
        check("보고서 MEDIA:/tmp/r.xlsx", "보고서 MEDIA:/tmp/r.xlsx")
    }
}
