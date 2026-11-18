package com.hermeswebui.android.assistant.voice

import android.graphics.RuntimeShader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AGSL은 앱에서 처음 인스턴스를 만드는 순간 컴파일된다. 문법 오류가 있으면 그 자리에서
 * IllegalArgumentException에 줄 번호가 붙어 나오므로, 이 한 줄이 셰이더의 실제 검증이다.
 */
@RunWith(AndroidJUnit4::class)
class AuraShaderCompileTest {

    @Test
    fun theAuraShaderCompilesOnThisDevice() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val shader = RuntimeShader(AuraAgsl.SOURCE)
        assertThat(shader).isNotNull()
    }

    @Test
    fun everyUniformTheShaderDeclaresCanBeSet() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val shader = AuraShader(AuraPalette.Nova, mini = false)
        val u = AuraUniforms()
        u.time = 1f; u.flow = 2f
        // 이름이 하나라도 어긋나면 여기서 IllegalArgumentException이 난다.
        shader.update(u, 1080f, 2400f, 0f, 0f)
    }
}
