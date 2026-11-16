package com.hermeswebui.android.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full-screen chats: the list of earlier chats, and one conversation. It shares every part of a
 * conversation with the quick panel (`AssistantScreen`), in the same rose style.
 */
@Composable
internal fun ChatScreen(
    state: AssistantState,
    draft: String,
    mode: AssistantMode,
    photos: List<String>,
    preparing: Int,
    models: ModelChoice,
    actions: AssistantActions,
    inChat: Boolean,
    onOpen: (ChatRow) -> Unit,
    onNew: () -> Unit,
    onBackToList: () -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val calm = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalCalmMotion provides calm) {
        BackHandler { if (inChat) onBackToList() else onExit() }
        Box(Modifier.fillMaxSize().background(Rose.Panel)) {
            DotGrid(Modifier.fillMaxWidth().height(180.dp))
            Column(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                if (inChat) ChatPane(state, draft, mode, photos, preparing, models, actions, onBackToList, onNew)
                else ListPane(state, actions, onOpen, onNew, onRefresh)
            }
        }
    }
}

@Composable
private fun ColumnScope.ListPane(state: AssistantState, actions: AssistantActions, onOpen: (ChatRow) -> Unit, onNew: () -> Unit, onRefresh: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val connecting = state.link is AssistantLink.Minting || state.link is AssistantLink.Connecting || state.link is AssistantLink.Attaching
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(working = connecting || state.chatsLoading, answersDone = 0)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Hermes", style = RoseType.Wordmark, maxLines = 1)
            val (label, color) = linkLabel(state.link)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, blinking = connecting)
                Text(label, style = RoseType.Meta, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
        RoundIcon(Icons.Rounded.Refresh, "목록 새로 고침", onClick = onRefresh)
        RoundIcon(Icons.Rounded.EditNote, "새 대화", filled = true, onClick = onNew)
    }
    Row(
        Modifier.padding(horizontal = 20.dp).padding(top = 14.dp).fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp))
            .background(Rose.Field).border(1.dp, Rose.Border, RoundedCornerShape(23.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Rose.Muted, modifier = Modifier.size(18.dp))
        Box(Modifier.padding(start = 8.dp).weight(1f)) {
            if (query.isEmpty()) Text("대화 찾기", style = RoseType.Label)
            BasicTextField(query, { query = it }, singleLine = true, textStyle = RoseType.Body.copy(fontSize = 15.sp),
                cursorBrush = SolidColor(Rose.Rose), modifier = Modifier.fillMaxWidth())
        }
    }
    Box(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp)) { Banner(state.link, actions) }
    val needle = query.trim()
    val rows = remember(state.chats, state.live, needle) {
        state.chats
            .filter { needle.isEmpty() || it.title.orEmpty().contains(needle, true) || it.preview.orEmpty().contains(needle, true) }
            .sortedByDescending { it.pinned }
    }
    when {
        rows.isEmpty() && state.chatsLoading -> Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) { ThinkingIndicator() }
        rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
            Text(if (needle.isEmpty()) "아직 대화가 없어요" else "찾는 대화가 없어요", style = RoseType.Label)
        }
        else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row -> ChatRowItem(row, running = row.running || row.id in state.live) { onOpen(row) } }
        }
    }
}

@Composable
private fun ChatRowItem(row: ChatRow, running: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelFlower(22.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title ?: "제목 없는 대화", style = RoseType.Body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (running) Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) { StatusDot(Rose.Deep, blinking = true) }
            }
            row.preview?.let { Text(it, style = RoseType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
        row.lastActiveAt?.let { Text(ago(it), style = RoseType.Meta, maxLines = 1, modifier = Modifier.padding(start = 10.dp)) }
    }
}

@Composable
private fun ColumnScope.ChatPane(
    state: AssistantState,
    draft: String,
    mode: AssistantMode,
    photos: List<String>,
    preparing: Int,
    models: ModelChoice,
    actions: AssistantActions,
    onBack: () -> Unit,
    onNew: () -> Unit,
) {
    val turn = state.activeTurn
    val connecting = state.link is AssistantLink.Minting || state.link is AssistantLink.Connecting || state.link is AssistantLink.Attaching
    val fresh = state.storedId == null && state.turns.isEmpty()
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "대화 목록", onClick = onBack)
        Column(Modifier.padding(start = 4.dp).weight(1f)) {
            Text(state.title ?: if (fresh) "새 대화" else "대화", style = RoseType.Body.copy(fontSize = 16.5.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (label, color) = if (turn != null) "WRITING" to Rose.Deep else linkLabel(state.link)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, blinking = turn != null || connecting)
                Text(label, style = RoseType.Meta, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                if (turn != null) Elapsed(turn.id)
            }
        }
        if (fresh) ModelChip(models, actions)
        else if (!state.busy) RoundIcon(Icons.Rounded.EditNote, "새 대화", onClick = onNew)
    }
    Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
        when {
            state.history.isNotEmpty() || state.turns.isNotEmpty() || state.requests.isNotEmpty() -> Conversation(state, replaceable = false, actions = actions)
            state.opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ThinkingIndicator() }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("반가워요", style = RoseType.Label)
                    BeatingHeart(Modifier.padding(start = 6.dp))
                }
                Text("무엇을 도와드릴까요?", style = RoseType.Greeting, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(80.dp))
            }
        }
    }
    Footer(state, draft, mode, photos, preparing, conversation = true, actions = actions)
}

/** How long ago [seconds] (Unix time) was, in the short form a list row has room for. */
internal fun ago(seconds: Double, now: Long = System.currentTimeMillis()): String {
    val minutes = (now / 1_000 - seconds.toLong()) / 60
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        minutes < 24 * 60 -> "${minutes / 60}시간 전"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}일 전"
        else -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(seconds.toLong() * 1_000))
    }
}
