package com.hermeswebui.android.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
/** What the chats screen shows besides the conversation state: the list, the bots' pictures and the open tab. */
internal class ChatsView(
    val chats: List<ChatRow>,
    val loading: Boolean,
    val avatars: Map<String, ImageBitmap>,
    val avatarKey: (String) -> String,
    /** The bot whose chats are listed: [ALL] for every chat, null for the main bot (the server's own profile). */
    val tab: String?,
    val pane: Pane,
    val notice: String? = null,
) {
    companion object {
        /** Every chat of every bot, newest first. */
        const val ALL = "*"
    }
}

internal class ChatsActions(
    val onOpen: (ChatRow) -> Unit,
    val onNew: () -> Unit,
    val onTab: (String?) -> Unit,
    val onDelete: (ChatRow) -> Unit,
    val onRename: (ChatRow, String) -> Unit,
    val onPin: (ChatRow, Boolean) -> Unit,
    val onArchive: (ChatRow) -> Unit,
    val onPoll: () -> Unit,
    val onNoticeShown: () -> Unit,
    val onBackToList: () -> Unit,
    val onRefresh: () -> Unit,
    val onMenu: () -> Unit,
    val onExit: () -> Unit,
)

@Composable
internal fun ChatScreen(
    state: AssistantState,
    draft: String,
    mode: AssistantMode,
    photos: List<String>,
    preparing: Int,
    models: ModelChoice,
    actions: AssistantActions,
    view: ChatsView,
    chats: ChatsActions,
    menu: MenuView,
    menuActions: MenuActions,
) {
    val context = LocalContext.current
    val calm = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // The open chat's bot: the profile it belongs to, else the server's own.
    val botRow = state.bot
    val botName = botRow?.title ?: state.profile
    val bot = botName?.let { Bot(it, view.avatars[view.avatarKey(botRow?.name ?: state.profile.orEmpty())]) }
    CompositionLocalProvider(
        LocalCalmMotion provides calm, LocalBot provides bot,
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = HermesFonts.family),
    ) {
        BackHandler {
            when (view.pane) {
                Pane.LIST -> chats.onExit()
                Pane.CHAT -> chats.onBackToList()
                Pane.MENU -> menuActions.onPane(Pane.LIST)
                else -> menuActions.onPane(Pane.MENU)
            }
        }
        Box(Modifier.fillMaxSize().background(Rose.Panel)) {
            DotGrid(Modifier.fillMaxWidth().height(180.dp))
            Column(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                when (view.pane) {
                    Pane.CHAT -> ChatPane(state, draft, mode, photos, preparing, models, actions, bot, chats.onBackToList, actions.onNewChat)
                    Pane.LIST -> ListPane(state, actions, view, chats)
                    else -> MenuPanes(view.pane, menu, menuActions)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ListPane(state: AssistantState, actions: AssistantActions, view: ChatsView, chats: ChatsActions) {
    var query by rememberSaveable { mutableStateOf("") }
    val connecting = state.link is AssistantLink.Minting || state.link is AssistantLink.Connecting || state.link is AssistantLink.Attaching
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(working = connecting || view.loading, answersDone = 0)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("Hermes", style = RoseType.Wordmark, maxLines = 1)
            val (label, color) = linkLabel(state.link)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, blinking = connecting)
                Text(label, style = RoseType.Meta, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
        // The main action first, then the two that are used less; the menu sits at the edge, where menus are.
        RoundIcon(Icons.Rounded.Add, "새 대화", primary = true, onClick = chats.onNew)
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Rounded.Refresh, "목록 새로 고침", filled = true, onClick = chats.onRefresh)
        Spacer(Modifier.width(4.dp))
        RoundIcon(Icons.Rounded.Menu, "메뉴", filled = true, onClick = chats.onMenu)
    }
    // General chats and each bot's chats are separate lists. Bots come from the server's roster, or,
    // until it is read, from the profiles the chats themselves name.
    val own = state.profiles.firstOrNull { it.own }?.name
    fun general(row: ChatRow) = if (own != null) row.profile == null || row.profile == own else row.defaultProfile
    val bots = remember(state.profiles, view.chats) {
        state.profiles.filterNot { it.own }.ifEmpty {
            view.chats.filterNot(::general).mapNotNull { it.profile }.distinct().map { ProfileRow(it, it) }
        }
    }
    // One page per tab, in the order of the chips: every chat, the main bot, then each bot. A swipe
    // sideways moves to the next one.
    val tabs = remember(bots) { listOf<String?>(ChatsView.ALL, null) + bots.map { it.name } }
    val pager = rememberPagerState(initialPage = tabs.indexOf(view.tab).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    val chips = rememberLazyListState()
    // A bot's tab can be asked for before the roster names the bot; it is shown once it does.
    LaunchedEffect(tabs) {
        val wanted = tabs.indexOf(view.tab)
        if (wanted >= 0 && wanted != pager.currentPage) pager.scrollToPage(wanted)
    }
    LaunchedEffect(pager, tabs) {
        snapshotFlow { pager.currentPage }.drop(1).collect { page -> if (page in tabs.indices) chats.onTab(tabs[page]) }
    }
    LaunchedEffect(pager.currentPage) { chips.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    // A running chat's row is read again while the list is on screen, so it stops blinking when it ends.
    val anyRunning = view.chats.any { it.running }
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            delay(6_000)
            chats.onPoll()
        }
    }
    val main = state.profiles.firstOrNull { it.own }?.title
    LazyRow(
        state = chips,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        items(tabs.size, key = { tabs[it] ?: "" }) { index ->
            val selected = pager.currentPage == index
            val go = { scope.launch { pager.animateScrollToPage(index) }; Unit }
            when (val tab = tabs[index]) {
                ChatsView.ALL -> TabChip("전체", null, selected, picture = false, onClick = go)
                // The main bot: the server's own profile, by the name its answers carry.
                null -> TabChip(main?.let { "@$it" } ?: "일반", view.avatars[view.avatarKey(own ?: "default")], selected, onClick = go)
                else -> TabChip("@${bots.firstOrNull { it.name == tab }?.title ?: tab}", view.avatars[view.avatarKey(tab)], selected, onClick = go)
            }
        }
    }
    SearchField(query, { query = it }, "대화 찾기", Modifier.padding(horizontal = 20.dp).padding(top = 12.dp))
    Box(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp)) { Banner(state.link, actions) }
    Notice(view.notice, chats.onNoticeShown)

    var acting by remember { mutableStateOf<ChatRow?>(null) }
    var renaming by remember { mutableStateOf<ChatRow?>(null) }
    var deleting by remember { mutableStateOf<ChatRow?>(null) }
    acting?.let { row ->
        ChatSheet(
            row,
            onDismiss = { acting = null },
            onRename = { acting = null; renaming = row },
            onPin = { acting = null; chats.onPin(row, !row.pinned) },
            onArchive = { acting = null; chats.onArchive(row) },
            onDelete = { acting = null; deleting = row },
        )
    }
    renaming?.let { row ->
        RenameDialog(row.title.orEmpty(), onDismiss = { renaming = null }) { title ->
            renaming = null
            chats.onRename(row, title)
        }
    }
    deleting?.let { row ->
        ConfirmDialog(
            title = "이 대화를 지울까요?",
            body = "\"${row.title ?: "제목 없는 대화"}\"\n\nHermes에서 완전히 지워져요. 다른 기기에서도 사라지고 되돌릴 수 없어요.",
            confirm = "지우기",
            onDismiss = { deleting = null },
        ) {
            deleting = null
            chats.onDelete(row)
        }
    }
    val needle = query.trim()
    HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { tabs.getOrNull(it) ?: "" }) { page ->
        ChatList(tabs.getOrNull(page), state.profiles, view, own, needle, onAct = { acting = it }, onOpen = chats.onOpen)
    }
}

/** One tab's chats. [tab] is [ChatsView.ALL], null for the main bot, or a bot's profile name. */
@Composable
private fun ChatList(tab: String?, profiles: List<ProfileRow>, view: ChatsView, own: String?, needle: String, onAct: (ChatRow) -> Unit, onOpen: (ChatRow) -> Unit) {
    val rows = remember(view.chats, tab, own, needle, profiles) {
        fun general(row: ChatRow) = if (own != null) row.profile == null || row.profile == own else row.defaultProfile
        // A bot's standing chat is not among the recent chats: it heads its bot's tab and joins the full list.
        val standing = when (tab) {
            ChatsView.ALL -> profiles.mapNotNull { it.chat }
            else -> listOfNotNull(profiles.firstOrNull { if (tab == null) it.own else it.name == tab }?.chat)
        }
        (standing + view.chats
            .filter { tab == ChatsView.ALL || (if (tab == null) general(it) else it.profile == tab) }
            .filter { row -> standing.none { it.id == row.id && it.profile == row.profile } })
            .filter { needle.isEmpty() || it.title.orEmpty().contains(needle, true) || it.preview.orEmpty().contains(needle, true) }
    }
    // Bot chat, app chats, each messaging platform and cron runs are separate sections, in that order.
    val sections = remember(rows, tab) {
        // The full list is one run of every kind of chat, the newest at the top.
        if (tab == ChatsView.ALL) return@remember listOf("전체" to rows.sortedByDescending { it.lastActiveAt ?: 0.0 })
        val messaging = rows.filter { it.kind == ChatKind.MESSAGING }.groupBy { it.source ?: "messaging" }
        listOf("봇 채팅" to rows.filter { it.kind == ChatKind.BOT }, "대화" to rows.filter { it.kind == ChatKind.CHAT }.sortedByDescending { it.pinned }) +
            messaging.map { (source, threads) -> platformLabel(source) to threads } +
            listOf("크론 작업" to rows.filter { it.kind == ChatKind.CRON })
    }.filter { it.second.isNotEmpty() }
    when {
        rows.isEmpty() && view.loading -> Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) { ThinkingIndicator() }
        rows.isEmpty() -> EmptyState(if (needle.isEmpty()) "아직 대화가 없어요" else "찾는 대화가 없어요")
        else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            sections.forEach { (title, chatRows) ->
                item(key = "section-$title") { SectionTitle(title, "${chatRows.size}", Modifier.padding(start = 10.dp, top = 14.dp, bottom = 2.dp)) }
                items(chatRows, key = { "${it.kind}/${it.profile}/${it.id}" }) { row ->
                    ChatRowItem(
                        row, view.avatars[view.avatarKey(row.profile ?: own ?: "default")],
                        tag = kindTag(row).takeIf { tab == ChatsView.ALL },
                        // A bot's standing chat is the bot itself; it is not renamed or removed from here.
                        onLongClick = { onAct(row) }.takeIf { row.kind != ChatKind.BOT },
                    ) { onOpen(row) }
                }
            }
        }
    }
}

/** What a long press on a chat offers. */
@Composable
private fun ChatSheet(row: ChatRow, onDismiss: () -> Unit, onRename: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Rose.Panel).border(1.dp, Rose.Border, RoundedCornerShape(26.dp)).padding(vertical = 10.dp)) {
            Text(row.title ?: "제목 없는 대화", style = RoseType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 8.dp))
            SheetRow(Icons.Rounded.Edit, "이름 변경", onClick = onRename)
            SheetRow(Icons.Rounded.PushPin, if (row.pinned) "고정 해제" else "맨 위에 고정", onClick = onPin)
            SheetRow(Icons.Rounded.Archive, "보관", "목록에서 숨겨요. 메뉴의 보관한 대화에서 꺼낼 수 있어요", onClick = onArchive)
            SheetRow(Icons.Rounded.DeleteOutline, "삭제", strong = true, onClick = onDelete)
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String? = null, strong: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (strong) Rose.Deep else Rose.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(label, style = RoseType.Body.copy(fontSize = 15.5.sp, color = if (strong) Rose.Deep else Rose.Text, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal))
            if (detail != null) Text(detail, style = RoseType.Label.copy(fontSize = 12.sp))
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var title by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, current.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ready = title.text.isNotBlank() && title.text.trim() != current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Rose.Panel).border(1.dp, Rose.Border, RoundedCornerShape(26.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("이름 변경", style = RoseType.Body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
            BasicTextField(
                title, { title = it.copy(text = it.text.take(120)) }, singleLine = true, textStyle = RoseType.Body.copy(fontSize = 15.5.sp),
                cursorBrush = SolidColor(Rose.Deep),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(16.dp)).background(Rose.Field)
                    .border(1.dp, Rose.Rose, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("취소", filled = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                PillButton("바꾸기", filled = true, modifier = Modifier.weight(1f).alpha(if (ready) 1f else 0.45f)) { if (ready) onRename(title.text.trim()) }
            }
        }
    }
}

/** What kind of chat a row of the full list is; an ordinary chat needs no tag. */
private fun kindTag(row: ChatRow): String? = when (row.kind) {
    ChatKind.BOT -> "봇"
    ChatKind.MESSAGING -> platformLabel(row.source ?: "messaging")
    ChatKind.CRON -> "크론"
    ChatKind.CHAT -> null
}

/** A messaging source as its section title. */
private fun platformLabel(source: String): String = when (source) {
    "telegram" -> "텔레그램"
    "discord" -> "디스코드"
    "slack" -> "슬랙"
    "whatsapp" -> "왓츠앱"
    "email" -> "이메일"
    "sms" -> "문자"
    else -> source.replaceFirstChar { it.uppercase() }
}

@Composable
private fun TabChip(label: String, image: ImageBitmap?, selected: Boolean, picture: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(if (selected) Rose.Bubble else Rose.Field)
            .border(1.dp, if (selected) Rose.Rose else Rose.Border, RoundedCornerShape(25.dp))
            .clickable(role = Role.Tab, onClick = onClick).padding(start = if (picture) 5.dp else 16.dp, end = if (picture) 14.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (picture) BotAvatar(image, 40.dp)
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Rose.ChipText else Rose.Text, maxLines = 1, modifier = Modifier.padding(start = if (picture) 7.dp else 0.dp))
    }
}

@Composable
private fun ChatRowItem(row: ChatRow, image: ImageBitmap?, tag: String?, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick?.let { act -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); act() } })
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The owning bot's picture; the Hermes icon when it has none.
        BotAvatar(image, 46.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned && row.kind != ChatKind.BOT) Icon(Icons.Rounded.PushPin, "고정됨", tint = Rose.Rose, modifier = Modifier.padding(end = 5.dp).size(14.dp))
                Text(row.title ?: "제목 없는 대화", style = RoseType.Body.copy(fontSize = 15.5.sp, fontWeight = if (row.unread) FontWeight.Bold else FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tag != null) Text(tag, style = RoseType.Meta.copy(color = Rose.ChipText), maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(Rose.Soft).padding(horizontal = 6.dp, vertical = 2.dp))
                // The dot blinks while an answer is being written, and until an answer that came is read.
                if (row.running || row.unread) Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (row.running) Rose.Deep else Rose.Rose, blinking = true)
                }
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
    bot: Bot?,
    onBack: () -> Unit,
    onNew: () -> Unit,
) {
    val turn = state.activeTurn
    val connecting = state.link is AssistantLink.Minting || state.link is AssistantLink.Connecting || state.link is AssistantLink.Attaching
    val fresh = state.storedId == null && state.turns.isEmpty()
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "대화 목록", onClick = onBack)
        if (bot != null) BotAvatar(bot.image, 42.dp, Modifier.padding(start = 2.dp))
        Column(Modifier.padding(start = if (bot != null) 9.dp else 4.dp).weight(1f)) {
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
        else RoundIcon(Icons.Rounded.Add, "새 대화", filled = true, onClick = onNew)
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
