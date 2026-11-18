package com.hermeswebui.android.assistant.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermeswebui.android.assistant.AssistantController
import com.hermeswebui.android.assistant.Rose
import com.hermeswebui.android.assistant.RoseType
import kotlinx.coroutines.delay

/**
 * 음성 대화 settings: key, model, voice, speed, tone, per-bot overrides, hand-off.
 *
 * Picking a voice or a speed plays it immediately. There is no way to choose a voice without hearing it,
 * so every choice on this screen is also a preview.
 */
class VoiceSettingsActivity : ComponentActivity() {

    private val key by lazy { GeminiKey(this) }
    private val store by lazy { VoiceSettingsStore(this) }
    private val preview by lazy { VoicePreview(applicationContext, key) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var settings by remember { mutableStateOf(store.load()) }
            var masked by remember { mutableStateOf(key.masked()) }
            var draftKey by remember { mutableStateOf("") }
            var editingKey by remember { mutableStateOf(key.get() == null) }
            var notice by remember { mutableStateOf<String?>(null) }
            var bots by remember { mutableStateOf(listOf<String>()) }
            var editingBot by remember { mutableStateOf<String?>(null) }
            var playing by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val assistant = AssistantController.obtainChats(applicationContext)
                assistant.refreshProfiles()
                while (bots.isEmpty()) {
                    bots = assistant.state.value.profiles.mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
                    delay(300)
                }
            }

            fun save(next: VoiceSettings) {
                settings = next
                store.save(next)
            }

            fun previewCurrent(next: VoiceSettings) {
                playing = true
                preview.play(next, editingBot) { error ->
                    playing = false
                    notice = error
                }
            }

            fun pickVoice(voice: Voice) {
                val bot = editingBot
                val next = if (bot == null) settings.copy(voice = voice) else {
                    val current = settings.perBot[bot]
                    settings.copy(perBot = settings.perBot + (bot to BotVoice(voice, current?.persona ?: settings.persona)))
                }
                save(next)
                previewCurrent(next)
            }

            fun pickSpeed(speed: Float) {
                val next = settings.copy(speed = speed)
                save(next)
                previewCurrent(next)
            }

            fun pickPreset(preset: PersonaPreset) {
                val bot = editingBot
                val next = if (bot == null) settings.copy(persona = settings.persona.copy(preset = preset)) else {
                    val voice = settings.perBot[bot]?.voice ?: settings.voice
                    settings.copy(perBot = settings.perBot + (bot to
                        BotVoice(voice, Persona(preset, settings.persona.warmth, settings.persona.custom))))
                }
                save(next)
                previewCurrent(next)
            }

            Column(Modifier.fillMaxWidth().background(Rose.Panel)) {
                TopBar(playing = playing) {
                    preview.stop()
                    playing = false
                    finish()
                }
                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { Spacer(Modifier.height(2.dp)) }

                    item {
                        Card {
                            SectionTitle("Gemini 키", if (masked == null) "아직 없어요" else "저장됨 · $masked")
                            if (!editingKey) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("키가 저장되어 있어요", style = RoseType.Body, modifier = Modifier.weight(1f))
                                    Chip("변경") { editingKey = true }
                                }
                            } else {
                                OutlinedTextField(
                                    value = draftKey,
                                    onValueChange = { draftKey = it },
                                    label = { Text("새 키를 붙여 넣으세요") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Chip("저장", primary = true) {
                                        if (draftKey.isBlank()) {
                                            notice = "키가 비어 있어요"
                                        } else {
                                            key.save(draftKey)
                                            draftKey = ""
                                            masked = key.masked()
                                            editingKey = false
                                            notice = "키를 저장했어요"
                                        }
                                    }
                                    if (masked != null) Chip("지우기") {
                                        key.clear()
                                        masked = null
                                        editingKey = true
                                    }
                                }
                            }
                            Text("무료 등급은 대화 내용이 Google의 제품 개선에 쓰일 수 있어요.",
                                style = RoseType.Meta, modifier = Modifier.padding(top = 6.dp))
                        }
                    }

                    item {
                        Card {
                            SectionTitle("모델", "3.8 Live가 기본, 3.1 Flash는 더 가벼워요")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiveModel.entries.forEach { model ->
                                    Chip(model.label, primary = settings.model == model) {
                                        save(settings.copy(model = model))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card {
                            SectionTitle("목소리", "누르면 바로 들려드려요")
                            Text("${Voice.entries.size}개 · 지금 ${settings.voiceFor(editingBot).label}",
                                style = RoseType.Meta)
                        }
                    }
                    items(Voice.entries.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            pair.forEach { voice ->
                                VoiceCard(
                                    voice = voice,
                                    selected = settings.voiceFor(editingBot) == voice,
                                    modifier = Modifier.weight(1f),
                                ) { pickVoice(voice) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    item {
                        Card {
                            SectionTitle("말 속도", "1.0이 기본 · 지금 ${formatSpeed(settings.speed)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                VoiceSettings.SPEEDS.forEach { speed ->
                                    Chip(formatSpeed(speed), primary = settings.speed == speed) { pickSpeed(speed) }
                                }
                            }
                            Text("미리 듣기는 지금 고른 목소리·말투·속도로 읽어요.",
                                style = RoseType.Meta, modifier = Modifier.padding(top = 6.dp))
                        }
                    }

                    item {
                        Card {
                            SectionTitle("SOUL.md", "서버에서 자동으로 받아와요")
                            Text(
                                "통화를 시작할 때 서버의 SOUL.md를 읽어 지시문에 넣어요. 앱에서는 볼 수도, 고칠 수도 없어요.",
                                style = RoseType.Meta,
                            )
                        }
                    }

                    item {
                        Card {
                            SectionTitle("통화에서 부를 이름", "비우면 '헤르메스의 음성 비서'")
                            OutlinedTextField(
                                value = settings.assistantName,
                                onValueChange = { text -> save(settings.copy(assistantName = text.take(20))) },
                                label = { Text("예: 한채린") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    item {
                        Card {
                            SectionTitle("대답 언어", "기본 6개 · 나머지는 코드로 직접")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                VoiceLanguage.entries.forEach { language ->
                                    Chip(
                                        if (language == VoiceLanguage.CUSTOM) "직접"
                                        else language.label,
                                        primary = settings.language == language,
                                    ) { save(settings.copy(language = language)) }
                                }
                            }
                            if (settings.language == VoiceLanguage.CUSTOM) {
                                OutlinedTextField(
                                    value = settings.customLanguage,
                                    onValueChange = { text -> save(settings.copy(customLanguage = text.take(12))) },
                                    label = { Text("언어 코드 (예: zh-CN, pt-BR)") },
                                    singleLine = true,
                                    isError = VoiceLanguage.normalize(settings.customLanguage) == null && settings.customLanguage.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                            Text(
                                "대답은 고른 언어로만 해요. 알아듣는 언어는 말하는 대로 따라가요 — 인식은 서버가 정합니다.",
                                style = RoseType.Meta,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    item {
                        Card {
                            SectionTitle("말투", "성격과 애교의 정도")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PersonaPreset.entries.forEach { preset ->
                                    Chip(preset.label, primary = settings.personaFor(editingBot).preset == preset) {
                                        pickPreset(preset)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("강도 ${settings.personaFor(editingBot).warmth}/3", style = RoseType.Label)
                            Slider(
                                value = settings.personaFor(editingBot).warmth.toFloat(),
                                onValueChange = { value ->
                                    val warmth = value.toInt().coerceIn(0, 3)
                                    val bot = editingBot
                                    if (bot == null) {
                                        save(settings.copy(persona = settings.persona.copy(warmth = warmth)))
                                    } else {
                                        val voice = settings.perBot[bot]?.voice ?: settings.voice
                                        val persona = (settings.perBot[bot]?.persona ?: settings.persona).copy(warmth = warmth)
                                        save(settings.copy(perBot = settings.perBot + (bot to BotVoice(voice, persona))))
                                    }
                                },
                                valueRange = 0f..3f,
                                steps = 2,
                            )
                            if (settings.personaFor(editingBot).preset == PersonaPreset.CUSTOM) {
                                OutlinedTextField(
                                    value = settings.persona.custom,
                                    onValueChange = { text -> save(settings.copy(persona = settings.persona.copy(custom = text))) },
                                    label = { Text("성격과 말투를 직접 적기") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    if (bots.isNotEmpty()) {
                        item {
                            Card {
                                SectionTitle("봇별 목소리", if (editingBot == null) "지금은 모든 봇이 같아요" else "${editingBot}만 따로")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Chip("기본", primary = editingBot == null) { editingBot = null }
                                    bots.take(4).forEach { bot ->
                                        Chip(bot, primary = editingBot == bot) { editingBot = bot }
                                    }
                                }
                                if (editingBot != null) {
                                    Text("위에서 목소리·말투를 고르면 이 봇에만 저장돼요.",
                                        style = RoseType.Meta, modifier = Modifier.padding(top = 6.dp))
                                    Chip("이 봇은 기본값 따르기") {
                                        save(settings.copy(perBot = settings.perBot - editingBot!!))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Hermes에게 넘기기", style = RoseType.Body)
                                    Text("내 데이터·파일·서버·일정·검색·기억이 필요할 때", style = RoseType.Meta)
                                }
                                Switch(checked = settings.handOff, onCheckedChange = { save(settings.copy(handOff = it)) })
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("자막", style = RoseType.Body)
                                    Text("통화 화면에 마지막 두 줄을 보여 줘요", style = RoseType.Meta)
                                }
                                Switch(checked = settings.subtitles, onCheckedChange = { save(settings.copy(subtitles = it)) })
                            }
                        }
                    }

                    notice?.let { message ->
                        item { Text(message, style = RoseType.Label, color = Rose.ChipText) }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    override fun onDestroy() {
        preview.stop()
        super.onDestroy()
    }
}

private fun formatSpeed(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) "%.1f".format(speed) else "%.2f".format(speed)
    return "${text}x"
}

@Composable
private fun TopBar(playing: Boolean, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Press(onBack, "뒤로") {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Rose.Soft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, null, tint = Rose.Deep, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("음성 대화", style = RoseType.Greeting.copy(fontSize = 22.sp))
            Text(if (playing) "미리 듣는 중…" else "목소리 · 말투 · 속도", style = RoseType.Label)
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (playing) Rose.Deep else Rose.Soft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.VolumeUp,
                null,
                tint = if (playing) Color.White else Rose.Deep,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White)
            .border(1.dp, Rose.Border, RoundedCornerShape(22.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(title: String, detail: String? = null) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, style = RoseType.Body.copy(fontWeight = FontWeight.SemiBold, color = Rose.Strong, fontSize = 17.sp))
        if (detail != null) Text(detail, style = RoseType.Meta)
    }
}

@Composable
private fun VoiceCard(voice: Voice, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Press(onClick, voice.label, modifier = modifier) {
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (selected) Rose.Deep else Rose.Soft)
                .border(1.dp, if (selected) Rose.Deep else Rose.Border, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    voice.label,
                    style = RoseType.Body.copy(
                        color = if (selected) Color.White else Rose.Text,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                    ),
                    maxLines = 1,
                )
                Text(
                    voice.range,
                    style = RoseType.Meta.copy(color = if (selected) Color.White.copy(alpha = 0.85f) else Rose.Muted),
                )
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Chip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Press(onClick, label) {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (primary) Rose.Deep else Rose.Soft)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = RoseType.Label.copy(
                    color = if (primary) Color.White else Rose.Deep,
                    fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
        }
    }
}

@Composable
private fun Press(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "press",
    )
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) { content() }
}