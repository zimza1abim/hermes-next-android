package com.hermeswebui.android.assistant

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The screens of the native app. Back goes from a menu's own screen to the menu, then to the chats. */
internal enum class Pane { LIST, CHAT, MENU, FONT, CRON, CAPABILITIES, INSTALL, ARCHIVE }

/** Whether the app icon opens the native chats instead of the Webapp page. On by default. */
object NativeHome {
    private const val PREFS = "hermes_chats_home"
    private const val KEY = "native_home"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

internal class MenuView(
    val fonts: Listing<FontChoice>,
    val cron: Listing<CronJob>,
    val skills: Listing<Capability>,
    val toolsets: Listing<Capability>,
    val mcp: Listing<Capability>,
    val plugins: Listing<Installable>,
    val mcpCatalog: Listing<Installable>,
    val installing: Set<String>,
    val archived: Listing<ChatRow>,
    val notice: String?,
    val nativeHome: Boolean,
    /** Whose capabilities are listed: the open tab's bot, or the general profile. */
    val capabilitiesOf: String,
)

internal class MenuActions(
    val onPane: (Pane) -> Unit,
    val onPickFont: (FontChoice?) -> Unit,
    val onCronPaused: (CronJob, Boolean) -> Unit,
    val onCronRun: (CronJob) -> Unit,
    val onInstall: (Installable) -> Unit,
    val onUnarchive: (ChatRow) -> Unit,
    val onRefresh: (Pane) -> Unit,
    val onAppSettings: () -> Unit,
    val onWebapp: () -> Unit,
    /** Opens the voice-call settings (key, voice, tone). */
    val onVoiceSettings: () -> Unit,
    /** Re-reads the chat list; the header no longer carries a refresh button of its own. */
    val onRefreshChats: () -> Unit,
    val onNativeHome: (Boolean) -> Unit,
    val onNoticeShown: () -> Unit,
)

@Composable
internal fun ColumnScope.MenuPanes(pane: Pane, view: MenuView, actions: MenuActions) {
    when (pane) {
        Pane.MENU -> {
            PaneHeader("메뉴", onBack = { actions.onPane(Pane.LIST) })
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                item { SectionTitle("Hermes", null, Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)) }
                item { MenuRow(Icons.Rounded.Schedule, "크론 작업", "예약 작업을 보고, 멈추고, 바로 실행해요") { actions.onPane(Pane.CRON) } }
                item { MenuRow(Icons.Rounded.Extension, "스킬 · 도구 · MCP", "${view.capabilitiesOf}에 켜져 있는 것들") { actions.onPane(Pane.CAPABILITIES) } }
                item { MenuRow(Icons.Rounded.Download, "플러그인 · MCP 설치", "Hermes 카탈로그에서 골라 설치해요") { actions.onPane(Pane.INSTALL) } }
                item { MenuRow(Icons.Rounded.Archive, "보관한 대화", "보관한 대화를 보고 다시 꺼내요") { actions.onPane(Pane.ARCHIVE) } }
                item { MenuRow(Icons.Rounded.Refresh, "대화 목록 새로 고침", "서버에서 목록과 봇 목록을 다시 읽어요", onClick = actions.onRefreshChats) }
                item { SectionTitle("앱", null, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp)) }
                item { MenuRow(Icons.Rounded.TextFields, "글꼴", HermesFonts.pickedLabel(LocalContext.current) ?: "휴대폰 기본 글꼴") { actions.onPane(Pane.FONT) } }
                item { MenuRow(Icons.Rounded.Mic, "음성 대화", "목소리·말투, Gemini 키, Hermes에게 넘기기", onClick = actions.onVoiceSettings) }
                item { MenuRow(Icons.Rounded.Notifications, "알림 · 앱 설정", "알림 종류, 미리보기, 배터리, 서버 주소", onClick = actions.onAppSettings) }
                item { MenuRow(Icons.Rounded.Language, "Webapp 열기", "로그인과 나머지 기능은 Webapp 화면에서", onClick = actions.onWebapp) }
                item {
                    MenuRow(
                        Icons.Rounded.Home,
                        "앱을 열면 이 화면으로 시작",
                        if (view.nativeHome) "켜짐: 앱 아이콘이 네이티브 대화를 열어요" else "꺼짐: 앱 아이콘이 Webapp을 열어요",
                        trailing = { Toggle(view.nativeHome) },
                    ) { actions.onNativeHome(!view.nativeHome) }
                }
            }
        }
        Pane.FONT -> {
            PaneHeader("글꼴", onBack = { actions.onPane(Pane.MENU) })
            when {
                view.fonts.loading && view.fonts.rows.isEmpty() -> Loading()
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    item { FontRow(null, selected = HermesFonts.pickedId == null) { actions.onPickFont(null) } }
                    items(view.fonts.rows, key = { it.id }) { font ->
                        FontRow(font, selected = HermesFonts.pickedId == font.id) { actions.onPickFont(font) }
                    }
                    if (view.fonts.rows.isEmpty()) item { Note("휴대폰에 설치된 갤럭시 글꼴이 없어요. 설정 › 디스플레이 › 글꼴에서 내려받으면 여기 나와요.") }
                }
            }
        }
        Pane.CRON -> {
            PaneHeader("크론 작업", onBack = { actions.onPane(Pane.MENU) }) { RoundIcon(Icons.Rounded.Refresh, "새로 고침") { actions.onRefresh(Pane.CRON) } }
            Notice(view.notice, actions.onNoticeShown)
            when {
                view.cron.loading && view.cron.rows.isEmpty() -> Loading()
                view.cron.failed && view.cron.rows.isEmpty() -> EmptyState("작업 목록을 읽지 못했어요")
                view.cron.rows.isEmpty() -> EmptyState("예약된 작업이 없어요")
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()) {
                    items(view.cron.rows, key = { "${it.profile}/${it.id}" }) { job -> CronRow(job, actions) }
                }
            }
        }
        Pane.CAPABILITIES -> {
            PaneHeader("스킬 · 도구 · MCP", onBack = { actions.onPane(Pane.MENU) }) {
                RoundIcon(Icons.Rounded.Refresh, "새로 고침") { actions.onRefresh(Pane.CAPABILITIES) }
            }
            var query by rememberSaveable { mutableStateOf("") }
            SearchField(query, { query = it }, "이름이나 설명으로 찾기", Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
            val needle = query.trim()
            fun Listing<Capability>.matching() = if (needle.isEmpty()) this else
                copy(rows = rows.filter { it.name.contains(needle, true) || it.detail.orEmpty().contains(needle, true) })
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                section("도구 모음", view.toolsets.matching(), needle.isNotEmpty())
                section("MCP 서버", view.mcp.matching(), needle.isNotEmpty())
                section("스킬", view.skills.matching(), needle.isNotEmpty())
            }
        }
        Pane.INSTALL -> {
            PaneHeader("플러그인 · MCP 설치", onBack = { actions.onPane(Pane.MENU) }) {
                RoundIcon(Icons.Rounded.Refresh, "새로 고침") { actions.onRefresh(Pane.INSTALL) }
            }
            var query by rememberSaveable { mutableStateOf("") }
            SearchField(query, { query = it }, "플러그인, MCP 서버 찾기", Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
            Notice(view.notice, actions.onNoticeShown)
            val needle = query.trim()
            fun Listing<Installable>.matching() = if (needle.isEmpty()) this else copy(rows = rows.filter {
                it.title.contains(needle, true) || it.name.contains(needle, true) || it.detail.orEmpty().contains(needle, true) || it.source.orEmpty().contains(needle, true)
            })
            // A plugin is code that will run on the Hermes machine, so installing one is confirmed first.
            var asking by remember { mutableStateOf<Installable?>(null) }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()) {
                catalog("플러그인", view.plugins.matching(), view.installing, needle.isNotEmpty()) { asking = it }
                catalog("MCP 서버", view.mcpCatalog.matching(), view.installing, needle.isNotEmpty()) { asking = it }
            }
            asking?.let { item ->
                ConfirmDialog(
                    title = "${item.title} 설치",
                    body = (if (item.plugin) "Hermes가 돌아가는 컴퓨터에 이 플러그인의 코드를 설치하고 켜요." else "이 MCP 서버를 Hermes 설정에 추가하고 켜요.") +
                        (item.source?.let { "\n\n$it" } ?: ""),
                    confirm = "설치",
                    onDismiss = { asking = null },
                ) {
                    asking = null
                    actions.onInstall(item)
                }
            }
        }
        Pane.ARCHIVE -> {
            PaneHeader("보관한 대화", onBack = { actions.onPane(Pane.MENU) }) {
                RoundIcon(Icons.Rounded.Refresh, "새로 고침") { actions.onRefresh(Pane.ARCHIVE) }
            }
            Notice(view.notice, actions.onNoticeShown)
            when {
                view.archived.loading && view.archived.rows.isEmpty() -> Loading()
                view.archived.failed && view.archived.rows.isEmpty() -> EmptyState("보관한 대화를 읽지 못했어요")
                view.archived.rows.isEmpty() -> EmptyState("보관한 대화가 없어요")
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(view.archived.rows, key = { "${it.profile}/${it.id}" }) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.title ?: "제목 없는 대화", style = RoseType.Body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(row.profile?.let { "@$it" }, row.lastActiveAt?.let(::ago)).joinToString(" · "), style = RoseType.Label, maxLines = 1)
                            }
                            PillButton("꺼내기", filled = false, modifier = Modifier.padding(start = 10.dp)) { actions.onUnarchive(row) }
                        }
                    }
                }
            }
        }
        Pane.LIST, Pane.CHAT -> Unit
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.catalog(
    title: String, listing: Listing<Installable>, installing: Set<String>, searching: Boolean, onInstall: (Installable) -> Unit,
) {
    item(key = "catalog-$title") {
        SectionTitle(title, "${listing.rows.count { it.installed }} / ${listing.rows.size} 설치됨".takeIf { listing.rows.isNotEmpty() }, Modifier.padding(top = 8.dp, start = 4.dp))
    }
    when {
        listing.loading && listing.rows.isEmpty() -> item(key = "catalog-load-$title") { Text("읽는 중…", style = RoseType.Label, modifier = Modifier.padding(4.dp)) }
        listing.failed && listing.rows.isEmpty() -> item(key = "catalog-fail-$title") { Text("카탈로그를 읽지 못했어요", style = RoseType.Label, modifier = Modifier.padding(4.dp)) }
        listing.rows.isEmpty() -> item(key = "catalog-none-$title") { Text(if (searching) "찾는 것이 없어요" else "카탈로그가 비어 있어요", style = RoseType.Label, modifier = Modifier.padding(4.dp)) }
        else -> items(listing.rows.size, key = { "catalog-$title-${listing.rows[it].name}" }) { index ->
            val item = listing.rows[index]
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Rose.Field).border(1.dp, Rose.Border, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(item.title, style = RoseType.Body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.detail?.let { Text(it, style = RoseType.Label, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                item.source?.let { Text(it, style = RoseType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        item.installed -> Text("설치됨", style = RoseType.Label.copy(color = Rose.Online))
                        item.name in installing -> Text("설치하는 중…", style = RoseType.Label)
                        item.blocked != null -> Text("${item.blocked}. Webapp에서 설치해 주세요", style = RoseType.Label)
                        else -> PillButton("설치", filled = true) { onInstall(item) }
                    }
                }
            }
        }
    }
}

/** A yes/no question over the screen, in the app's own style. */
@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Rose.Panel).border(1.dp, Rose.Border, RoundedCornerShape(26.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = RoseType.Body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
            Text(body, style = RoseType.Body.copy(fontSize = 14.5.sp, lineHeight = 22.sp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("취소", filled = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                PillButton(confirm, filled = true, modifier = Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, listing: Listing<Capability>, searching: Boolean) {
    item(key = "head-$title") {
        SectionTitle(title, "${listing.rows.count { it.enabled }} / ${listing.rows.size} 켜짐".takeIf { listing.rows.isNotEmpty() }, Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp))
    }
    when {
        listing.loading && listing.rows.isEmpty() -> item(key = "load-$title") { Text("읽는 중…", style = RoseType.Label, modifier = Modifier.padding(4.dp)) }
        listing.failed && listing.rows.isEmpty() -> item(key = "fail-$title") { Text("읽지 못했어요", style = RoseType.Label, modifier = Modifier.padding(4.dp)) }
        listing.rows.isEmpty() -> item(key = "none-$title") { Text(if (searching) "찾는 것이 없어요" else "없어요", style = RoseType.Label, modifier = Modifier.padding(4.dp)) }
        else -> items(listing.rows.size, key = { "$title-$it" }) { index -> CapabilityRow(listing.rows[index]) }
    }
}

@Composable
private fun CapabilityRow(row: Capability) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (row.enabled) Rose.Online else Rose.Border))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = RoseType.Body.copy(fontSize = 15.sp, color = if (row.enabled) Rose.Text else Rose.Muted), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                row.note?.let { Text("  $it", style = RoseType.Meta, maxLines = 1) }
            }
            row.detail?.let { Text(it, style = RoseType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CronRow(job: CronJob, actions: MenuActions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Rose.Field).border(1.dp, Rose.Border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (job.paused) Rose.Border else Rose.Online))
            Text(job.name, style = RoseType.Body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f))
        }
        val facts = listOfNotNull(job.profile?.let { "@$it" }, job.schedule, job.nextRun?.let { "다음 ${shortTime(it)}" }.takeUnless { job.paused },
            job.lastStatus?.let { "지난 실행 $it" }, "멈춤".takeIf { job.paused })
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = RoseType.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("지금 실행", filled = true) { actions.onCronRun(job) }
            PillButton(if (job.paused) "다시 켜기" else "멈추기", filled = false) { actions.onCronPaused(job, !job.paused) }
        }
    }
}

/** `2026-11-16T09:00:00+09:00` as `11-16 09:00`. */
internal fun shortTime(iso: String): String =
    Regex("^\\d{4}-(\\d{2}-\\d{2})[T ](\\d{2}:\\d{2})").find(iso)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: iso.take(16)

@Composable
private fun FontRow(font: FontChoice?, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current.applicationContext
    // Each name is drawn in its own font, read off the main thread.
    val family by produceState<FontFamily?>(null, font?.id) {
        value = font?.let { withContext(Dispatchers.IO) { HermesFonts.typeface(context, it.id) } }?.let(::FontFamily)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) Rose.Bubble else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(font?.label ?: "휴대폰 기본 글꼴", fontFamily = if (font == null) FontFamily.Default else family, fontSize = 17.sp, color = Rose.Text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(if (font == null) "휴대폰 설정의 글꼴을 따라요" else "가나다라 마바사 Hermes 123", fontFamily = if (font == null) FontFamily.Default else family,
                fontSize = 13.sp, color = Rose.Muted, maxLines = 1)
        }
        if (selected) Icon(Icons.Rounded.Check, "선택됨", tint = Rose.Deep, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun PaneHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", onClick = onBack)
        Text(title, style = RoseType.Body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = Modifier.padding(start = 4.dp).weight(1f))
        trailing()
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, trailing: @Composable () -> Unit = {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Rose.Muted, modifier = Modifier.size(20.dp))
}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Rose.Soft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Rose.Deep, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = RoseType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(detail, style = RoseType.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** The search field every list of the native screens uses. */
@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp))
            .background(Rose.Field).border(1.dp, Rose.Border, RoundedCornerShape(23.dp)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Rose.Muted, modifier = Modifier.size(18.dp))
        Box(Modifier.padding(start = 8.dp).weight(1f)) {
            if (query.isEmpty()) Text(hint, style = RoseType.Label.copy(fontSize = 14.5.sp), maxLines = 1)
            BasicTextField(query, onQuery, singleLine = true, textStyle = RoseType.Body.copy(fontSize = 15.sp),
                cursorBrush = SolidColor(Rose.Deep), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) Box(
            Modifier.size(38.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { onQuery("") },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "지우기", tint = Rose.Muted, modifier = Modifier.size(16.dp)) }
    }
}

/** A section's name over its rows, with an optional count beside it. */
@Composable
internal fun SectionTitle(title: String, count: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RoseType.Label.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Rose.ChipText))
        if (count != null) Text("  $count", style = RoseType.Meta)
    }
}

/** An empty list: the flower and one line. */
@Composable
internal fun EmptyState(text: String) {
    Column(Modifier.fillMaxSize().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelFlower(28.dp)
        Text(text, style = RoseType.Label.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Rose.Deep else Rose.Border).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(22.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)) }
}

@Composable
private fun ColumnScope.Loading() {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) { ThinkingIndicator() }
}

@Composable
private fun Note(text: String) {
    Text(text, style = RoseType.Label, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp))
}

/** A line that reports the last action and clears itself. */
@Composable
internal fun Notice(text: String?, onShown: () -> Unit) {
    if (text == null) return
    LaunchedEffect(text) {
        delay(2_500)
        onShown()
    }
    Text(text, fontSize = 13.5.sp, color = Rose.ChipText,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Rose.Soft).padding(horizontal = 14.dp, vertical = 9.dp))
}
