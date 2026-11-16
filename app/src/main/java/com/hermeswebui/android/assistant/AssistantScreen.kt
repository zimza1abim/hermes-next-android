package com.hermeswebui.android.assistant

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermeswebui.android.webapp.GatewayEvent
import com.hermeswebui.android.webapp.RpcId
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.painterResource
import com.hermeswebui.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The panel is a side tool, so it stays small:
 *  - Greeting: header, IDLE_BODY, the mode chips and the input. This is the size it opens at.
 *  - Conversation: the chips step aside and the messages get CHAT_BODY, about a fifth more panel.
 *  - Expanded (the header button): the messages take the rest of the screen, at once.
 * The keyboard lifts the panel; it never squeezes the messages unless the screen itself is too short.
 */
private val IDLE_BODY = 117.dp
private val CHAT_BODY = 228.dp

internal class AssistantActions(
    val onDraft: (String) -> Unit,
    val onMode: (AssistantMode) -> Unit,
    val onModel: (AssistantModel?) -> Unit,
    val onEffort: (String?) -> Unit,
    val onBrowseModels: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onAgain: (String) -> Unit,
    val onClose: () -> Unit,
    val onNewChat: () -> Unit,
    val onRetry: () -> Unit,
    val onApprove: (RpcId, Boolean) -> Unit,
    val onOpenApp: () -> Unit,
    val onReplace: (String) -> Unit,
    val onPickPhotos: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onRemovePhoto: (String) -> Unit,
    val thumbnail: (String) -> ImageBitmap?,
)

@Composable
internal fun AssistantScreen(
    state: AssistantState,
    draft: String,
    mode: AssistantMode,
    photos: List<String>,
    preparing: Int,
    replaceable: Boolean,
    models: ModelChoice,
    actions: AssistantActions,
) {
    val context = LocalContext.current
    val calm = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // Text drawn without a style of its own (buttons, chips) follows the picked font too.
    CompositionLocalProvider(LocalCalmMotion provides calm, LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = HermesFonts.family)) {
        Panel(state, draft, mode, photos, preparing, replaceable, models, actions)
    }
}

@Composable
private fun Panel(
    state: AssistantState,
    draft: String,
    mode: AssistantMode,
    photos: List<String>,
    preparing: Int,
    replaceable: Boolean,
    models: ModelChoice,
    actions: AssistantActions,
) {
    val calm = LocalCalmMotion.current
    val appear = remember { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    val conversation = state.turns.isNotEmpty() || state.requests.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    // Back steps down one level at a time: a large panel becomes small before the panel closes.
    BackHandler { if (expanded) expanded = false else actions.onClose() }

    // A quiet tick when an answer lands, for a panel that is often glanced at rather than watched.
    val view = LocalView.current
    val done = state.turns.count { it.status == TurnStatus.DONE }
    val seenDone = remember { intArrayOf(done) }
    LaunchedEffect(done) {
        if (done > seenDone[0] && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        seenDone[0] = done
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(Rose.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions.onClose)
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = (1f - appear.value) * 22.dp.toPx()
                    alpha = appear.value
                }
                .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .background(Rose.Panel)
                // Swallows taps on the panel itself, so only the scrim closes it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            DotGrid(Modifier.fillMaxWidth().height(180.dp))
            PearlEdge(Modifier.align(Alignment.TopCenter))
            Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                Header(state, expanded, onExpand = { expanded = !expanded }, actions)
                // The body takes what the header, the input and the keyboard leave, up to its own height;
                // expanded, it takes all of it. Sized in layout, so nothing here recomposes with the keyboard.
                Box(
                    Modifier
                        .weight(1f, fill = expanded)
                        .then(if (expanded) Modifier else Modifier.height(if (conversation) CHAT_BODY else IDLE_BODY))
                        .padding(horizontal = 20.dp)
                ) {
                    if (conversation) Conversation(state, replaceable, actions) else Greeting(models, actions)
                }
                Footer(state, draft, mode, photos, preparing, conversation, actions)
            }
        }
    }
}

@Composable
private fun Header(state: AssistantState, expanded: Boolean, onExpand: () -> Unit, actions: AssistantActions) {
    val turn = state.activeTurn
    val connecting = state.link is AssistantLink.Minting || state.link is AssistantLink.Connecting || state.link is AssistantLink.Attaching
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(working = turn != null || connecting, answersDone = state.turns.count { it.status == TurnStatus.DONE })
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Hermes", style = RoseType.Wordmark, maxLines = 1)
            val (label, color) = if (turn != null) "WRITING" to Rose.Deep else linkLabel(state.link)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, blinking = turn != null || connecting)
                Text(label, style = RoseType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                if (turn != null) Elapsed(turn.id)
            }
        }
        if (state.turns.isNotEmpty() && !state.busy) RoundIcon(Icons.Rounded.EditNote, "새 대화", onClick = actions.onNewChat)
        if (state.storedId != null) RoundIcon(Icons.AutoMirrored.Rounded.OpenInNew, "Hermes 앱에서 이 대화 열기", onClick = actions.onOpenApp)
        RoundIcon(
            if (expanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
            if (expanded) "작게 보기" else "크게 보기",
            onClick = onExpand,
        )
        RoundIcon(Icons.Rounded.Close, "닫기", filled = true, onClick = actions.onClose)
    }
}

internal fun linkLabel(link: AssistantLink) = when (link) {
    AssistantLink.Ready -> "ONLINE" to Rose.Online
    is AssistantLink.Minting, is AssistantLink.Connecting, is AssistantLink.Attaching -> "CONNECTING" to Rose.Rose
    is AssistantLink.Backoff -> "OFFLINE" to Rose.Muted
    AssistantLink.SignedOut -> "SIGN-IN NEEDED" to Rose.Deep
    AssistantLink.Unsupported -> "NO GATEWAY" to Rose.Deep
    AssistantLink.Idle -> "STANDBY" to Rose.Border
}

/** " · 00:12" after WRITING: how long the answer has been running. */
@Composable
internal fun Elapsed(turnId: String) {
    val started = remember(turnId) { System.currentTimeMillis() }
    val now = remember(turnId) { mutableLongStateOf(started) }
    LaunchedEffect(turnId) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now.longValue - started) / 1_000).coerceAtLeast(0)
    Text(" · %02d:%02d".format(seconds / 60, seconds % 60), style = RoseType.Meta, maxLines = 1)
}

/** The starred models, the full list as far as it was read, and what new chats are created with. */
internal data class ModelChoice(val favourites: List<AssistantModel>, val setup: ChatSetup, val catalog: Catalog)

@Composable
private fun Greeting(models: ModelChoice, actions: AssistantActions) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("반가워요", style = RoseType.Label)
            BeatingHeart(Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            ModelChip(models, actions)
        }
        Text("무엇을 도와드릴까요?", style = RoseType.Greeting, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun Conversation(state: AssistantState, replaceable: Boolean, actions: AssistantActions) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    // Follows the newest text until the user scrolls up to read; reaching the bottom again resumes it.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress, atBottom) {
        if (atBottom) follow = true else if (list.isScrollInProgress) follow = false
    }
    val count = state.history.size + state.turns.size + state.requests.size
    LaunchedEffect(count) {
        if (count > 0) {
            follow = true
            list.scrollToItem(count - 1, Int.MAX_VALUE / 2)
        }
    }
    // The answer keeps growing on screen after its last chunk (see smoothReveal), and the panel can change
    // size: whenever the content outgrows the view while following, pin the bottom again.
    LaunchedEffect(list) {
        snapshotFlow { list.canScrollForward }.collect { more ->
            val last = list.layoutInfo.totalItemsCount - 1
            if (more && follow && !list.isScrollInProgress && last >= 0) list.scrollToItem(last, Int.MAX_VALUE / 2)
        }
    }
    val lastTurn = state.turns.lastOrNull()?.id
    val activeTurn = state.activeTurn?.id
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.history, key = { index, _ -> "history-$index" }) { index, row ->
                // The bot is named once per answer, not on each part a tool call split it into.
                HistoryItem(row, named = index == 0 || state.history[index - 1].role == HistoryRole.USER)
            }
            items(state.turns, key = { it.id }) { turn ->
                // Fades in; no placement animation, which made the growing answer stutter.
                Box(Modifier.animateItem(placementSpec = null)) {
                    TurnItem(
                        turn,
                        canReplace = replaceable && turn.id == lastTurn && turn.mode == AssistantMode.TRANSLATE,
                        behindAnother = activeTurn != null && activeTurn != turn.id,
                        actions = actions,
                    )
                }
            }
            items(state.requests, key = { it.rpcId.json }) { request ->
                Box(Modifier.animateItem(placementSpec = null)) { RequestCard(request, actions) }
            }
        }
        if (!follow && !atBottom) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(Rose.Soft)
                    .clickable(role = Role.Button) {
                        follow = true
                        scope.launch { if (count > 0) list.animateScrollToItem(count - 1, Int.MAX_VALUE / 2) }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Rose.ChipText, modifier = Modifier.size(16.dp))
                Text("최신 답변", fontSize = 12.sp, color = Rose.ChipText)
            }
        }
    }
}

@Composable
private fun TurnItem(turn: AssistantTurn, canReplace: Boolean, behindAnother: Boolean, actions: AssistantActions) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.align(Alignment.End).widthIn(max = 300.dp), horizontalAlignment = Alignment.End) {
            if (turn.mode != AssistantMode.ASK) Text(turn.mode.name, style = RoseType.Meta, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp))
            if (turn.images.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                turn.images.forEach { id -> PhotoThumb(actions.thumbnail(id), size = 74.dp) }
            }
            if (turn.question.isNotEmpty()) QuestionBubble(turn.question, maxLines = 8)
        }
        Column(Modifier.padding(top = 14.dp)) {
            BotName()
            val answer = turn.answer
            val shown = smoothReveal(turn.id, answer)
            val writing = turn.status == TurnStatus.STREAMING || shown.length < answer.length
            when {
                shown.isNotEmpty() -> Answer(shown, writing)
                // Waiting for the socket or the chat looks the same as waiting for the answer: one calm indicator.
                turn.status.active || (turn.status == TurnStatus.QUEUED && !behindAnother) -> ThinkingIndicator()
                turn.status == TurnStatus.DONE -> Text("답변이 비어 있어요", style = RoseType.Label)
            }
            statusNote(turn, behindAnother)?.let {
                Text(it, style = RoseType.Label, color = if (turn.status == TurnStatus.FAILED) Rose.Deep else Rose.Muted, modifier = Modifier.padding(top = 8.dp))
            }
            when {
                turn.status == TurnStatus.FAILED || turn.status == TurnStatus.STOPPED -> Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton("다시 보내기", filled = false, icon = Icons.Rounded.Refresh) { actions.onAgain(turn.id) }
                    // What arrived before a stop is still worth keeping.
                    if (answer.isNotEmpty() && !writing) CopyButton(answer)
                }
                turn.status == TurnStatus.DONE && answer.isNotEmpty() && !writing -> AnswerActions(answer, canReplace, actions)
            }
        }
    }
}

/** Who answers in this conversation; null in the quick panel, which names nobody. */
internal class Bot(val name: String, val image: ImageBitmap?)

internal val LocalBot = compositionLocalOf<Bot?> { null }

/** A bot's picture in a ringed circle; the Hermes icon stands in for a bot without one. */
@Composable
internal fun BotAvatar(image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val ring = modifier.size(size).border(1.5.dp, Rose.Petal, CircleShape).padding(2.5.dp).clip(CircleShape).background(Rose.Soft)
    if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = ring)
    else Image(painterResource(R.drawable.hermes_agent_icon), contentDescription = null, contentScale = ContentScale.Crop, modifier = ring)
}

/** The bot's picture and @name above its answer. */
@Composable
private fun BotName() {
    val bot = LocalBot.current ?: return
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BotAvatar(bot.image, 38.dp)
        Text("@${bot.name}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Rose.Deep, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun QuestionBubble(text: String, maxLines: Int) {
    val shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    Text(
        text,
        style = RoseType.Body.copy(fontSize = 15.5.sp, lineHeight = 24.sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .shadow(6.dp, shape, ambientColor = Lilac.copy(alpha = 0.3f), spotColor = Lilac.copy(alpha = 0.3f))
            .clip(shape).background(Rose.Bubble)
            .padding(horizontal = 15.dp, vertical = 11.dp),
    )
}

/** One row of the transcript from before this screen's own turns. */
@Composable
private fun HistoryItem(row: HistoryRow, named: Boolean) {
    when (row.role) {
        HistoryRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.widthIn(max = 300.dp)) { QuestionBubble(row.text, maxLines = 24) }
        }
        HistoryRole.ASSISTANT -> Column {
            if (named) BotName()
            Answer(row.text, writing = false)
        }
    }
}

/** The answer as paragraph blocks: while it streams only the last block changes, so only it is laid out again. */
@Composable
internal fun Answer(shown: String, writing: Boolean) {
    val blocks = remember(shown) { AssistantMarkdown.blocks(shown) }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEachIndexed { index, block ->
                val tail = writing && index == blocks.lastIndex
                val code = remember(block) { AssistantMarkdown.codeBlock(block) }
                if (code != null) CodeBlock(code)
                else {
                    val styled = remember(block, tail) {
                        AssistantMarkdown.render(block, caret = tail).let { if (tail) shimmerTail(it) else it }
                    }
                    Text(styled, style = RoseType.Body)
                }
            }
        }
    }
}

/** A fenced block on its own: long lines scroll sideways instead of wrapping, and the code copies alone. */
@Composable
private fun CodeBlock(code: AssistantMarkdown.Code) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Rose.Soft)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(code.language.ifEmpty { "code" }.uppercase(), style = RoseType.Meta, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { clipboard.setText(AnnotatedString(code.text)) }
                    .semantics { contentDescription = "코드 복사" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ContentCopy, null, tint = Rose.Muted, modifier = Modifier.size(15.dp)) }
        }
        Text(
            code.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = Rose.Text, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun AnswerActions(answer: String, canReplace: Boolean, actions: AssistantActions) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canReplace) PillButton("바꿔 넣기", filled = true) { actions.onReplace(answer) }
        CopyButton(answer)
    }
}

@Composable
internal fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    PillButton(if (copied) "복사됨" else "복사", filled = false, icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy) {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }
}

private fun statusNote(turn: AssistantTurn, behindAnother: Boolean): String? = when (turn.status) {
    TurnStatus.QUEUED -> when {
        turn.images.size > turn.attached.size -> "사진 올리는 중 ${turn.attached.size}/${turn.images.size}"
        behindAnother -> "앞 질문이 끝나면 보낼게요"
        else -> null
    }
    TurnStatus.UNKNOWN -> "연결이 끊겼어요. 질문이 닿았는지 확인하고 있어요"
    TurnStatus.STOPPED -> "멈췄어요"
    TurnStatus.FAILED -> turn.error ?: "답변을 받지 못했어요"
    else -> null
}

@Composable
internal fun RequestCard(request: GatewayEvent.PromptRequest, actions: AssistantActions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Rose.Field)
            .border(1.dp, Rose.Rose, RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (request) {
            is GatewayEvent.ApprovalRequested -> {
                Text("명령을 실행해도 될까요?", style = RoseType.Body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                (request.command ?: request.description ?: request.toolName)?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Rose.Text, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Rose.Soft).padding(horizontal = 12.dp, vertical = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("거절", filled = false, modifier = Modifier.weight(1f)) { actions.onApprove(request.rpcId, false) }
                    PillButton("한 번 허용", filled = true, modifier = Modifier.weight(1f)) { actions.onApprove(request.rpcId, true) }
                }
            }
            is GatewayEvent.ClarifyRequested -> {
                Text("Hermes가 물어봐요", style = RoseType.Body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                request.questions.mapNotNull { it.question }.joinToString("\n").takeIf { it.isNotBlank() }?.let { Text(it, style = RoseType.Body) }
                PillButton("Hermes 앱에서 답하기", filled = true, onClick = actions.onOpenApp)
            }
        }
    }
}

@Composable
internal fun Footer(
    state: AssistantState,
    draft: String,
    mode: AssistantMode,
    photos: List<String>,
    preparing: Int,
    conversation: Boolean,
    actions: AssistantActions,
) {
    // The + tray: photo sources, and in a conversation the mode chips that stepped aside for the messages.
    var tray by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp, top = 6.dp)) {
        Banner(state.link, actions)
        if (photos.isNotEmpty() || preparing > 0) Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { id ->
                Box {
                    PhotoThumb(actions.thumbnail(id), size = 72.dp)
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Rose.Field.copy(alpha = 0.92f))
                            .clickable(role = Role.Button) { actions.onRemovePhoto(id) }.semantics { contentDescription = "사진 빼기" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, null, tint = Rose.Text, modifier = Modifier.size(14.dp)) }
                }
            }
            repeat(preparing) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Rose.Soft), contentAlignment = Alignment.Center) {
                    Text("READING", style = RoseType.Meta.copy(fontSize = 9.sp))
                }
            }
        }
        if (tray) {
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("사진 고르기", filled = false, icon = Icons.Rounded.Image) { tray = false; actions.onPickPhotos() }
                PillButton("사진 찍기", filled = false, icon = Icons.Rounded.PhotoCamera) { tray = false; actions.onTakePhoto() }
            }
            if (conversation) ModeChips(mode, actions.onMode)
        }
        if (!conversation) ModeChips(mode, actions.onMode)
        InputPill(state.activeTurn != null, draft, mode, photos.isNotEmpty(), onAttach = { tray = !tray }, onSent = { tray = false }, actions)
    }
}

/** The model and reasoning new chats run with: the models starred in Hermes first, every model a tap away. */
@Composable
internal fun ModelChip(models: ModelChoice, actions: AssistantActions) {
    var open by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val setup = models.setup
    val custom = setup.model != null || setup.effort != null
    val sheen = flowing(4_200, "model", running = custom)
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(Rose.Field)
                .then(if (custom) Modifier.pearlSheen { sheen.value } else Modifier)
                .border(1.dp, if (open || custom) Rose.Rose else Rose.Border, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button) { browsing = false; open = true }
                .semantics { contentDescription = "모델과 추론 강도 고르기" }
                .heightIn(min = 32.dp)
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◆", fontSize = 9.sp, color = if (custom) Rose.Deep else Rose.Petal)
            val effort = EFFORTS.firstOrNull { it.first == setup.effort && it.first != null }?.second
            Text(
                listOfNotNull(setup.model?.let { modelName(it.model) } ?: "기본 모델", effort).joinToString(" · "),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = Rose.ChipText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp),
            )
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Rose.Muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Rose.Panel,
            border = BorderStroke(1.dp, Rose.Border),
            shadowElevation = 6.dp,
            modifier = Modifier.heightIn(max = 420.dp).width(272.dp),
        ) {
            val pick: (AssistantModel?) -> Unit = { open = false; actions.onModel(it) }
            if (browsing) AllModels(models, onBack = { browsing = false }, onRetry = actions.onBrowseModels, onPick = pick)
            else {
                MenuLabel("THINKING")
                EffortChips(setup.effort, actions.onEffort)
                MenuLabel("MODEL")
                ModelRow("기본 모델", "Hermes 설정", setup.model == null) { pick(null) }
                models.favourites.forEach { entry ->
                    ModelRow(modelName(entry.model), entry.provider, entry == setup.model) { pick(entry) }
                }
                setup.model?.takeIf { it !in models.favourites }?.let { entry ->
                    ModelRow(modelName(entry.model), entry.provider, true) { pick(entry) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { browsing = true; actions.onBrowseModels() }
                        .heightIn(min = 44.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("모든 모델", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Rose.Deep, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Rose.Deep, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Reasoning levels offered, as Hermes's composer offers them; null leaves Hermes's setting. */
private val EFFORTS = listOf(null to "기본", "none" to "끔", "low" to "낮게", "medium" to "보통", "high" to "높게", "xhigh" to "최고")

@Composable
private fun EffortChips(effort: String?, onEffort: (String?) -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EFFORTS.forEach { (value, label) ->
            val selected = value == effort
            Text(
                label,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Rose.ChipText else Rose.Muted,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Rose.Bubble else Rose.Field)
                    .border(1.dp, if (selected) Rose.Rose else Rose.Border, RoundedCornerShape(14.dp))
                    .selectable(selected = selected, role = Role.RadioButton) { onEffort(value) }
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, style = RoseType.Meta, modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 6.dp))
}

/** Every signed-in provider's models, searchable. */
@Composable
private fun AllModels(models: ModelChoice, onBack: () -> Unit, onRetry: () -> Unit, onPick: (AssistantModel) -> Unit) {
    var query by remember { mutableStateOf("") }
    val catalog = models.catalog
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onBack).heightIn(min = 40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "뒤로", tint = Rose.Muted, modifier = Modifier.size(20.dp))
        Text("모든 모델", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Rose.Text, modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (catalog.loading) Text("LOADING", style = RoseType.Meta.copy(color = Rose.Rose))
    }
    BasicTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        textStyle = RoseType.Body.copy(fontSize = 14.sp),
        cursorBrush = SolidColor(Rose.Deep),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(Rose.Field).border(1.dp, Rose.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        decorationBox = { field ->
            Box {
                if (query.isEmpty()) Text("모델 찾기", fontSize = 14.sp, color = Rose.Muted)
                field()
            }
        },
    )
    val needle = query.trim().lowercase()
    val shown = catalog.providers.mapNotNull { provider ->
        val hits = provider.models.filter {
            needle.isEmpty() || it.model.lowercase().contains(needle) || modelName(it.model).lowercase().contains(needle) ||
                provider.name.lowercase().contains(needle)
        }
        if (hits.isEmpty()) null else provider to hits
    }
    when {
        catalog.failed -> Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("목록을 불러오지 못했어요", fontSize = 13.sp, color = Rose.Muted, modifier = Modifier.weight(1f))
            PillButton("다시", filled = false, onClick = onRetry)
        }
        shown.isEmpty() && !catalog.loading -> Text(
            if (needle.isEmpty()) "로그인된 제공자가 없어요" else "찾는 모델이 없어요",
            fontSize = 13.sp, color = Rose.Muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
    shown.forEach { (provider, hits) ->
        MenuLabel(provider.name.uppercase())
        hits.forEach { entry ->
            ModelRow(modelName(entry.model), entry.model, entry == models.setup.model) { onPick(entry) }
        }
    }
}

@Composable
private fun ModelRow(name: String, provider: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Rose.Bubble else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(name, fontSize = 14.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = Rose.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(provider, style = RoseType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = Rose.Deep, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

/** "claude-opus-5-5[1m]" reads "Opus 5.5 · 1M", "gpt-6.1-sol" reads "GPT 6.1 Sol". */
internal fun modelName(id: String): String {
    val context = Regex("\\[([^\\]]+)]$").find(id)?.groupValues?.get(1)
    val words = mutableListOf<String>()
    id.substringBefore('[').removePrefix("claude-").split('-', '_', '/').filter { it.isNotEmpty() }.forEach { part ->
        val number = part.all { it.isDigit() || it == '.' }
        if (number && words.lastOrNull()?.last()?.isDigit() == true) words[words.lastIndex] = words.last() + "." + part
        else words += when {
            part in UPPER -> part.uppercase()
            part.first().isLetter() -> part.replaceFirstChar { it.uppercase() }
            else -> part
        }
    }
    val name = words.joinToString(" ").ifEmpty { id }
    return if (context != null) "$name · ${context.uppercase()}" else name
}

private val UPPER = setOf("gpt", "glm", "oss")

@Composable
private fun ModeChips(mode: AssistantMode, onMode: (AssistantMode) -> Unit) {
    val sheen = flowing(3_600, "chip")
    Row(Modifier.padding(bottom = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistantMode.entries.forEach { entry ->
            val selected = entry == mode
            Text(
                modeLabel(entry),
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Rose.ChipText else Rose.Muted,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Rose.Bubble else Rose.Panel)
                    .then(if (selected) Modifier.pearlSheen { sheen.value } else Modifier)
                    .border(1.dp, if (selected) Rose.Rose else Rose.Border, RoundedCornerShape(18.dp))
                    .selectable(selected = selected, role = Role.RadioButton) { onMode(entry) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 15.dp, vertical = 8.dp),
            )
        }
    }
}

private fun modeLabel(mode: AssistantMode) = when (mode) {
    AssistantMode.ASK -> "질문"
    AssistantMode.SEARCH -> "검색"
    AssistantMode.TRANSLATE -> "번역"
    AssistantMode.SUMMARY -> "요약"
}

@Composable
internal fun Banner(link: AssistantLink, actions: AssistantActions) {
    val (text, button, action) = when (link) {
        AssistantLink.SignedOut -> Triple("Hermes 앱에서 다시 로그인해 주세요", "앱 열기", actions.onOpenApp)
        AssistantLink.Unsupported -> Triple("공식 Hermes Webapp 서버에서만 쓸 수 있어요", "다시 확인", actions.onRetry)
        is AssistantLink.Backoff -> Triple("서버에 닿지 않아요. 쓰던 글은 그대로 있어요", "다시 연결", actions.onRetry)
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Rose.Soft)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.5.sp, color = Rose.Text, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        PillButton(button, filled = link == AssistantLink.SignedOut, onClick = action)
    }
}

@Composable
private fun InputPill(
    answering: Boolean,
    draft: String,
    mode: AssistantMode,
    hasPhotos: Boolean,
    onAttach: () -> Unit,
    onSent: () -> Unit,
    actions: AssistantActions,
) {
    var focused by remember { mutableStateOf(false) }
    val enabled = draft.isNotBlank() || hasPhotos
    // The light only runs where it is drawn: the field's edge while typing, the send button when it can send.
    val flow = flowing(5_000, "field", running = focused)
    val sheen = flowing(3_600, "send", running = enabled)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (focused) Modifier.shadow(12.dp, shape, ambientColor = Lilac.copy(alpha = 0.3f), spotColor = Lilac.copy(alpha = 0.3f)) else Modifier)
            .clip(shape).background(Rose.Field)
            .border(1.dp, Rose.Border, shape)
            .then(if (focused) Modifier.pearlBorder(1.5.dp, 28.dp) { flow.value } else Modifier)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Rose.Soft).clickable(role = Role.Button, onClick = onAttach)
                .semantics { contentDescription = "사진과 모드" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, null, tint = Rose.Deep, modifier = Modifier.size(20.dp)) }
        BasicTextField(
            value = draft,
            onValueChange = actions.onDraft,
            textStyle = RoseType.Body,
            cursorBrush = SolidColor(Rose.Deep),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "질문 입력" },
            decorationBox = { field ->
                Box {
                    if (draft.isEmpty()) Text(placeholder(mode), style = RoseType.Body.copy(color = Rose.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
            },
        )
        if (answering && draft.isBlank() && !hasPhotos) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).border(BorderStroke(2.dp, Rose.Deep), CircleShape)
                    .clickable(role = Role.Button, onClick = actions.onStop).semantics { contentDescription = "답변 중지" },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Rose.Deep)) }
        } else {
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(140), label = "send")
            Box(
                Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
                    .background(if (enabled) Rose.Deep else Rose.Disabled)
                    .then(if (enabled) Modifier.pearlSheen { sheen.value } else Modifier)
                    .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button) {
                        // Sending is the end of typing: the keyboard goes away and the answer gets the room.
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        keyboard?.hide()
                        focus.clearFocus()
                        onSent()
                        actions.onSend()
                    }
                    .semantics { contentDescription = "보내기" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = if (enabled) Color.White else Rose.Deep, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun placeholder(mode: AssistantMode) = when (mode) {
    AssistantMode.ASK -> "무엇이든 물어보세요"
    AssistantMode.SEARCH -> "웹에서 찾아볼 것을 적어 주세요"
    AssistantMode.TRANSLATE -> "번역할 글을 붙여 넣으세요"
    AssistantMode.SUMMARY -> "요약할 글을 붙여 넣으세요"
}

@Composable
internal fun PhotoThumb(image: ImageBitmap?, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(Rose.Soft).border(1.dp, Rose.Border, RoundedCornerShape(18.dp))) {
        if (image != null) Image(image, contentDescription = "첨부한 사진", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun RoundIcon(icon: ImageVector, label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).padding(1.dp).clip(CircleShape).background(if (filled) Rose.Soft else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Rose.Muted, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun PillButton(label: String, filled: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (filled) Rose.Deep else Rose.Field)
            .then(if (filled) Modifier else Modifier.border(1.dp, Rose.Border, RoundedCornerShape(22.dp)))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (filled) Color.White else Rose.Text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 14.5.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal,
            color = if (filled) Color.White else Rose.Text, maxLines = 1)
    }
}

/** The newest characters of a streaming answer fade from the text colour into faint lilac, so each word arrives softly. */
private fun shimmerTail(text: AnnotatedString, length: Int = 10): AnnotatedString {
    // The last two characters are the caret.
    val end = (text.length - 2).coerceAtLeast(0)
    val start = (end - length).coerceAtLeast(0)
    if (end <= start) return text
    return buildAnnotatedString {
        append(text)
        addStyle(
            SpanStyle(brush = Brush.horizontalGradient(listOf(Rose.Text, Color(0xFFC27BA4), Lilac.copy(alpha = 0.55f), Lilac.copy(alpha = 0.2f)))),
            start, end,
        )
    }
}
