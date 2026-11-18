package com.hermeswebui.android.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The microphone and the speaker of a call.
 *
 * Input is 16 kHz mono PCM sent in 20 ms slices (the size the plan asks for); output is the 24 kHz PCM
 * the server returns, written as it arrives. Echo cancellation, noise suppression and gain control come
 * from the device, which is what keeps the bot from hearing itself on a loudspeaker.
 */
class LiveAudio(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var record: AudioRecord? = null
    private var micThread: Thread? = null
    private val micRunning = AtomicBoolean(false)

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingDeque<ByteArray>()
    private val playRunning = AtomicBoolean(false)

    private val effects = mutableListOf<Any>()
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var lastAmplitude = 0f

    // ── 재생 엔벨로프와 재생 위치 ─────────────────────────────
    private val envLock = Any()
    /** 20ms 창 엔벨로프: 창 끝 프레임(플러시 이후 누적, 원본 24kHz) → RMS. 원형 버퍼. */
    private val envEnd = LongArray(ENV_SLOTS)
    private val envVal = FloatArray(ENV_SLOTS)
    private var envHead = 0
    private var envCount = 0
    /** 플러시 이후 큐에 넣은 총 프레임. CaptionTimeline의 기준점이 된다. */
    @Volatile
    private var enqueued = 0L
    /** 마지막 flush 직후의 playbackHeadPosition. */
    @Volatile
    private var headOffset = 0L

    /** 1.0 is the model's own pace. Applied to the player, not the request: the server accepted a
     *  `speed` field but a measured 0.8 vs 1.5 changed the duration by 8%, i.e. it is ignored. */
    @Volatile
    private var speed = 1f

    /** True when the app may record; the call screen shows the reason when it may not. */
    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Requesting audio focus now keeps the call from talking over music; losing it pauses the call. */
    fun requestFocus(onLost: (permanent: Boolean) -> Unit, onRegained: () -> Unit): Boolean {
        // A reconnect asks again; the old request must not stay held or the system keeps counting it.
        abandonFocus()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    // LOSS means something took audio for good (a call kept it); TRANSIENT comes back.
                    AudioManager.AUDIOFOCUS_LOSS -> onLost(true)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onLost(false)
                    AudioManager.AUDIOFOCUS_GAIN -> onRegained()
                }
            }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Speaker, earpiece or Bluetooth, chosen by Android; [speaker] only asks for the loudspeaker. */
    fun route(speaker: Boolean) {
        @Suppress("DEPRECATION")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // isSpeakerphoneOn is deprecated and does not move a Bluetooth headset into the call; the
            // communication device does. A connected headset wins, then the requested route.
            val devices = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            val builtin = devices.firstOrNull {
                it.type == if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            (headset ?: builtin)?.let { runCatching { audioManager.setCommunicationDevice(it) } }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    /** Returns false when the device refuses the recorder; a call that cannot listen must say so. */
    fun startMicrophone(onChunk: (ByteArray) -> Unit): Boolean {
        if (micRunning.get()) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            LiveProtocol.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuffer, CHUNK_BYTES * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            LiveProtocol.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        enableDeviceEffects(recorder.audioSessionId)
        record = recorder
        micRunning.set(true)
        recorder.startRecording()
        micThread = Thread({
            val chunk = ByteArray(CHUNK_BYTES)
            var errors = 0
            while (micRunning.get()) {
                val read = try {
                    recorder.read(chunk, 0, chunk.size)
                } catch (dead: IllegalStateException) {
                    break
                }
                if (read < 0) {
                    // A recorder that keeps failing is gone (unplugged, taken); spinning on it would burn
                    // a core for the rest of the call.
                    if (++errors > 20) break else continue
                }
                if (read == 0) continue
                errors = 0
                if (read == chunk.size) lastAmplitude = rms(chunk)
                onChunk(chunk.copyOf(read))
            }
        }, "hermes-voice-mic").apply { isDaemon = true; start() }
        return true
    }

    fun stopMicrophone() {
        micRunning.set(false)
        // stop() unblocks a pending read, so the join is short and the main thread is not parked.
        record?.let { runCatching { it.stop() } }
        micThread?.join(200)
        micThread?.interrupt()
        micThread = null
        record?.let { runCatching { it.release() } }
        record = null
        releaseDeviceEffects()
    }

    /** Time-stretches playback (pitch kept) so a chosen speed is audible without asking the server. */
    fun setSpeed(newSpeed: Float) {
        val clamped = newSpeed.coerceIn(0.5f, 2f)
        if (clamped == speed) return
        speed = clamped
        track?.let { applySpeed(it) }
    }

    private fun applySpeed(track: AudioTrack) {
        runCatching {
            track.playbackParams = track.playbackParams.setSpeed(speed).setPitch(1f)
        }
    }

    /** Writes the chunk as soon as it is taken from the queue: the first audio starts playing at once. */
    fun play(pcm: ByteArray) {
        ensureTrack()
        // 20ms 창마다 RMS를 남긴다: 화면은 "지금 스피커에서 나오는 소리"를 이 값으로 안다.
        synchronized(envLock) {
            var from = 0
            while (from < pcm.size) {
                val to = minOf(pcm.size, from + ENV_WINDOW_BYTES)
                enqueued += (to - from) / 2
                envEnd[envHead] = enqueued
                envVal[envHead] = rms(pcm, from, to)
                envHead = (envHead + 1) % ENV_SLOTS
                envCount = minOf(envCount + 1, ENV_SLOTS)
                from = to
            }
        }
        playQueue.addLast(pcm)
    }

    /** Interruption: the queue is emptied now, and what is already buffered in the track is flushed. */
    fun stopPlayback() {
        playQueue.clear()
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            // flush 뒤 head가 0으로 리셋되든 아니든, 지금 값을 기준점으로 잡으면 둘 다 맞는다.
            headOffset = it.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            runCatching { it.play() }
        }
        synchronized(envLock) { enqueued = 0; envCount = 0; envHead = 0 }
    }

    /** 플러시 이후 실제로 스피커로 나간 프레임 수(원본 레이트 기준, 배속과 무관). */
    fun playedFrames(): Long {
        val t = track ?: return 0L
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (head - headOffset).coerceIn(0L, enqueued)
    }

    fun enqueuedFrames(): Long = enqueued

    /** 지금 들리고 있는 20ms 창의 RMS. 아무것도 안 나오면 0. */
    fun playbackLevel(): Float {
        val played = playedFrames()
        synchronized(envLock) {
            if (envCount == 0 || played >= enqueued) return 0f
            val start = (envHead - envCount + ENV_SLOTS) % ENV_SLOTS
            for (k in 0 until envCount) {
                val i = (start + k) % ENV_SLOTS
                if (envEnd[i] > played) return envVal[i]
            }
            return 0f
        }
    }

    /** Nothing is playing or queued. */
    fun playbackIdle(): Boolean = playQueue.isEmpty()

    fun amplitude(): Float = lastAmplitude

    fun release() {
        stopMicrophone()
        playRunning.set(false)
        playQueue.clear()
        playThread?.interrupt()
        playThread?.join(300)
        playThread = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        abandonFocus()
        @Suppress("DEPRECATION")
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    private fun ensureTrack() {
        if (track == null) {
            val minBuffer = AudioTrack.getMinBufferSize(
                LiveProtocol.OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val built = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(LiveProtocol.OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer, CHUNK_BYTES * 4))
                .build()
            track = built
            built.play()
            applySpeed(built)
        }
        if (playRunning.get()) return
        playRunning.set(true)
        playThread = Thread({
            while (playRunning.get()) {
                // poll, not take: a thread parked on take() outlives the call and never sees the flag,
                // which is how a call leaks a thread each time.
                val chunk = try {
                    playQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    return@Thread
                } ?: continue
                track?.let { live ->
                    if (live.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { live.play() }
                    var offset = 0
                    while (offset < chunk.size) {
                        val written = live.write(chunk, offset, chunk.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                }
            }
        }, "hermes-voice-play").apply { isDaemon = true; start() }
    }

    private fun enableDeviceEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; effects += it }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.also { it.enabled = true; effects += it }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.also { it.enabled = true; effects += it }
        }
    }

    private fun releaseDeviceEffects() {
        effects.forEach { effect ->
            when (effect) {
                is AcousticEchoCanceler -> runCatching { effect.enabled = false; effect.release() }
                is NoiseSuppressor -> runCatching { effect.enabled = false; effect.release() }
                is AutomaticGainControl -> runCatching { effect.enabled = false; effect.release() }
            }
        }
        effects.clear()
    }

    private fun rms(chunk: ByteArray): Float = rms(chunk, 0, chunk.size)

    private fun rms(chunk: ByteArray, from: Int, to: Int): Float {
        var sum = 0.0
        var count = 0
        var i = from
        while (i + 1 < to) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / Short.MAX_VALUE
        return min(1.0, max(0.0, rms)).toFloat()
    }

    companion object {
        /** 20 ms at 16 kHz mono, 16-bit. */
        const val CHUNK_BYTES = 640

        /** 재생 엔벨로프 창: 20ms × 24kHz × 2바이트. */
        const val ENV_WINDOW_BYTES = 960

        /** 링 크기: 20ms × 4096 ≈ 82초. 봇 한 턴은 이보다 짧다. */
        const val ENV_SLOTS = 4096

        /** Bluetooth routing is owned by Android; we only report the change, never force it. */
        val supportsEchoCancellation: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
    }
}
