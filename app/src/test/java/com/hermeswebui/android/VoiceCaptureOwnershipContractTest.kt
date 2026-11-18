package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Hermes WebUI owns voice capture, wake detection, STT and TTS. Android stays a thin host:
 * no native listener that could run beside WebUI's, and no foreground service type that
 * would let microphone capture outlive the activity.
 *
 * One exception, by design (docs/voice-acceptance.md): the native voice call. A Hermes voice call is its
 * own microphone owner — it records 16 kHz for Gemini Live and plays 24 kHz back — and the plan requires
 * it to survive the screen going off, which on Android 14+ needs a `microphone` foreground service.
 * The exception is bounded to the voice package and to the one service that serves it, so the original
 * invariant still has teeth everywhere else: nothing outside `assistant/voice/` may record, and no other
 * service may declare the microphone.
 */
class VoiceCaptureOwnershipContractTest {
    private val mainDir: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory }
        ?: error("Run from the repository root or the app module")

    private val manifest: String = File(mainDir, "AndroidManifest.xml").readText()

    private val kotlinSources: Map<String, String> = File(mainDir, "java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(mainDir).invariantSeparatorsPath to it.readText() }

    /** The only service allowed to ask for the microphone, and the only package allowed to record. */
    private val voiceServiceName = ".assistant.voice.VoiceService"
    private val voicePackage = "assistant/voice/"

    @Test
    fun `only the voice call service may hold the microphone`() {
        val services = Regex("""<service[^>]*android:name="([^"]+)"[^>]*android:foregroundServiceType="([^"]*)"""")
            .findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertThat(services).isNotEmpty()
        val microphoneServices = services.filter { (_, type) -> type.contains("microphone") }
        assertThat(microphoneServices.map { it.first }).containsExactly(voiceServiceName)
        // The permission is requested only because that one service declares the type.
        assertThat(manifest).contains("FOREGROUND_SERVICE_MICROPHONE")
    }

    @Test
    fun `only the voice call package creates a microphone listener`() {
        val nativeCaptureApis = listOf(
            "android.media.AudioRecord",
            "android.media.MediaRecorder",
            "android.speech.SpeechRecognizer",
            "android.speech.RecognizerIntent"
        )

        val outside = kotlinSources.filterKeys { !it.contains(voicePackage) }
        assertThat(outside).isNotEmpty()
        outside.forEach { (path, source) ->
            nativeCaptureApis.forEach { api ->
                assertWithMessage(path).that(source).doesNotContain(api)
            }
        }
        // And inside the voice package it is one file that owns the microphone, not several.
        val recorders = kotlinSources.filterKeys { it.contains(voicePackage) }
            .filterValues { it.contains("android.media.AudioRecord") }
            .keys
        assertThat(recorders.map { it.substringAfterLast('/') }).containsExactly("LiveAudio.kt")
    }

    @Test
    fun `android does not play speech of its own that could overlap WebUI capture`() {
        kotlinSources.forEach { (path, source) ->
            assertWithMessage(path).that(source).doesNotContain("android.speech.tts")
        }
    }

    @Test
    fun `foreground signal is origin-guarded like the other WebUI runtime scripts`() {
        val mainActivity = kotlinSources.entries
            .single { it.key.endsWith("MainActivity.kt") }
            .value
        val signal = mainActivity
            .substringAfter("private fun signalAppForegroundToWebUi(")
            .substringBefore("\n    }")

        assertThat(signal).contains("matchesConfiguredWebUiRoute(webView.url)")
        assertThat(signal).contains("HermesWebUiScripts.buildOriginGuardedRuntimeScript(")
        assertThat(signal).contains("HermesWebUiScripts.buildAppForegroundScript(active)")
    }

    @Test
    fun `background services never touch WebView capture`() {
        val backgroundSources = kotlinSources
            .filterKeys { it.contains("/background/") }
        assertThat(backgroundSources).isNotEmpty()
        backgroundSources.forEach { (path, source) ->
                assertWithMessage(path).that(source).doesNotContain("PermissionRequest")
                assertWithMessage(path).that(source).doesNotContain("RECORD_AUDIO")
            }
    }

    @Test
    fun `playback restriction is tightened only for permitted main-frame navigation`() {
        val activity = kotlinSources.entries.single { it.key.endsWith("MainActivity.kt") }.value
        val navigation = activity.substringAfter("override fun shouldOverrideUrlLoading(")
            .substringBefore("override fun onPageStarted(")
        assertThat(navigation).contains("val handled = if (request.isForMainFrame && proxyAuthNavigation.allows(serverUrl, target))")
        assertThat(navigation).contains("handleMainWebViewNavigation(target)")
        assertThat(navigation).contains("if (!handled && request.isForMainFrame)")
        assertThat(navigation).contains("restrictMediaPlaybackBeforeNavigation(target)")
        assertThat(activity).contains("HermesMediaPlaybackPolicy.beforeAllowedMainFrameNavigation(webView, target, currentWebTrustPolicy())")
        assertThat(activity).contains("HermesMediaPlaybackPolicy.onDocumentReady(view, currentWebTrustPolicy())")
    }
}
