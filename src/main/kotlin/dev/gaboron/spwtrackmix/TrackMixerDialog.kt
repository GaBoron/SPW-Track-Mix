@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package dev.gaboron.spwtrackmix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonAppearance
import com.moriafly.salt.ui.ButtonDefaults
import com.moriafly.salt.ui.ButtonIntent
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.ItemDivider
import com.moriafly.salt.ui.ItemSlider
import com.moriafly.salt.ui.RoundedColumn
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Text
import com.moriafly.salt.ui.dialog.AdaptiveDialogSize
import com.moriafly.salt.ui.dialog.BasicAdaptiveDialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val trackNames = listOf("人声", "鼓组", "贝斯", "吉他", "钢琴", "其他")
private val presetNames = listOf("原曲", "纯伴奏", "仅人声", "自定义")

@Composable
internal fun TrackMixerDialog(session: TrackMixSession, cacheDirectories: CacheDirectoryActions, dismiss: () -> Unit) {
    BasicAdaptiveDialog(dismiss, AdaptiveDialogSize.Max) {
        TrackMixerContent(session, cacheDirectories, dismiss)
    }
}

@Composable
private fun TrackMixerContent(session: TrackMixSession, cacheDirectories: CacheDirectoryActions, dismiss: () -> Unit) {
    var state by remember { mutableStateOf(session.status()) }
    val openedAfter = remember { state.queue().noticeId() }
    var options by remember { mutableStateOf(session.options()) }
    var manage by remember { mutableStateOf(false) }
    val update = {
        state = session.status()
        options = session.options()
    }
    LaunchedEffect(session) {
        while (true) {
            update()
            delay(160)
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(SaltTheme.shapes.large).background(SaltTheme.colors.popup)
            .heightIn(max = 760.dp).animateContentSize(tween(180))
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (manage) "模型和缓存" else "六轨混音", Modifier.weight(1f),
                style = SaltTheme.textStyles.largeTitle)
            Button(onClick = { if (manage) manage = false else dismiss() },
                text = if (manage) "返回" else "关闭", appearance = ButtonAppearance.Plain,
                size = ControlSize.Small)
        }
        if (manage) {
            ModelAndCache(session, cacheDirectories, state, options) {
                manage = false
                update()
            }
        } else {
            MixerBody(session, state, state.mix(), openedAfter, update) { manage = true }
        }
    }
}

@Composable
private fun MixerBody(session: TrackMixSession, state: TrackMixSession.Status, mix: MixSettings, openedAfter: Int,
                      update: () -> Unit, manage: () -> Unit) {
    val current = state.selection()
    val cached = current?.entry() != null
    val ready = state.ready()
    val quickCached = current?.entry()?.quick() == true
    Text(current?.track()?.title() ?: "当前歌曲", style = SaltTheme.textStyles.main,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    val status = when {
        current == null -> "请在 SPW 中播放本地立体声歌曲"
        current.error().isNotBlank() -> current.error()
        ready && quickCached -> if (mix.original()) "快速分离已缓存 · 原曲直通" else "快速分离 · 实时混音"
        ready && mix.original() -> "六轨已缓存 · 原曲直通"
        ready -> "六轨已缓存 · 实时混音"
        cached -> "六轨已缓存 · 当前播放暂未接入混音"
        state.queue().currentQueued() -> "原曲直通 · 已加入分离队列"
        else -> "原曲直通 · 当前歌曲尚无六轨缓存"
    }
    Text(status, style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText)

    if (state.notice().isNotBlank()) {
        Text(state.notice(), style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.canSeparate()) {
            Button(onClick = { session.separate(false); update() }, text = "标准分离", size = ControlSize.Small)
        }
        if (state.canQuickSeparate()) {
            Button(onClick = { session.separate(true); update() }, text = "快速分离",
                appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
        }
        Button(onClick = manage, text = "模型和缓存", appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
    }
    SeparationQueue(state.queue(), openedAfter) { id ->
        session.cancel(id)
        update()
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MixSettings.Preset.values().forEachIndexed { index, preset ->
            Toggle(presetNames[index], ready, mix.preset == preset) {
                session.setMix { it.withPreset(preset) }
                update()
            }
        }
        Button(onClick = { session.setMix { MixSettings.DEFAULT }; update() }, text = "重置",
            enabled = ready, appearance = ButtonAppearance.Plain, size = ControlSize.Small)
    }

    RoundedColumn {
        trackNames.forEachIndexed { index, name ->
            if (index > 0) ItemDivider()
            val bit = 1 shl index
            Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val interactions = remember { MutableInteractionSource() }
                val dragging by interactions.collectIsDraggedAsState()
                val pressing by interactions.collectIsPressedAsState()
                val value = mix.effectiveVolume(index).toFloat()
                val directlyManipulated = dragging || pressing
                val animatedValue by animateFloatAsState(value, if (directlyManipulated) snap() else tween(180))
                val displayed = if (directlyManipulated) value else animatedValue
                ItemSlider(displayed, { level ->
                    session.setMix { it.withVolume(index, level.roundToInt().coerceIn(0, 150)) }
                    update()
                }, name, Modifier.weight(1f).alpha(if (ready) 1f else 0.68f),
                    sub = "${displayed.roundToInt()}%", enabled = ready, valueRange = 0f..150f,
                    interactionSource = interactions)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Toggle("静音", ready, mix.muteMask() and bit != 0) {
                        session.setMix { it.withMuted(index) }
                        update()
                    }
                    Toggle("独奏", ready, mix.soloMask() and bit != 0) {
                        session.setMix { it.withSolo(index) }
                        update()
                    }
                }
            }
        }
    }
}

@Composable
private fun SeparationQueue(queue: TrackMixJobs.Snapshot, openedAfter: Int, cancel: (Int) -> Unit) {
    val items = queue.items()
    if (items.isNotEmpty()) {
        Text("后台任务", style = SaltTheme.textStyles.main)
        items.forEachIndexed { index, job ->
            val kind = when (job.kind()) {
                TrackMixJobs.Kind.STANDARD -> "标准分离"
                TrackMixJobs.Kind.QUICK -> "快速分离"
                else -> "模型和缓存"
            }
            val fraction = job.progress().fraction().coerceIn(0.0, 1.0)
            val progress = when {
                job.cancelling() -> "正在取消"
                !job.running() -> "排队 · 第 $index 位"
                else -> job.progress().label()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(job.title(), style = SaltTheme.textStyles.main, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$kind · $progress", style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText)
                }
                Button(onClick = { cancel(job.id()) }, text = "取消", enabled = !job.cancelling(),
                    appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(SaltTheme.shapes.small).background(SaltTheme.colors.stroke)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(if (job.running()) fraction.toFloat() else 0f)
                    .background(SaltTheme.colors.highlight))
            }
        }
    }
    val notice = queue.noticeAfter(openedAfter)
    if (notice.isNotBlank()) {
        Text(notice, style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ModelAndCache(session: TrackMixSession, cacheDirectories: CacheDirectoryActions,
                          status: TrackMixSession.Status, options: TrackMixOptions, finished: () -> Unit) {
    var cacheGiB by remember { mutableStateOf(options.cacheGiB()) }
    var quality by remember { mutableStateOf(options.quality()) }
    var fp16 by remember { mutableStateOf(options.fp16()) }
    var cpu by remember { mutableStateOf(options.cpu()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val idle = status.queue().items().isEmpty()

    Text("BS-RoFormer SW Fixed", style = SaltTheme.textStyles.main)
    Text(if (status.modelReady()) "模型已下载" else "首次分离时下载模型",
        color = SaltTheme.colors.subText, style = SaltTheme.textStyles.sub)
    Text("音轨缓存 %.2f GiB".format(status.cacheBytes() / 1073741824.0), style = SaltTheme.textStyles.sub)
    Text(session.cacheDirectory().toString(), style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = cacheDirectories::choose, text = "选择目录", enabled = idle, size = ControlSize.Small)
        Button(onClick = cacheDirectories::reset, text = "恢复默认", enabled = idle && options.cacheParent() != null,
            appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
    }

    RoundedColumn {
        ItemSlider(cacheGiB.toFloat(), { cacheGiB = it.roundToInt() }, "缓存上限", sub = "$cacheGiB GiB",
            valueRange = 1f..100f)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Toggle("高质量 TTA", true, quality) { quality = !quality }
        Toggle("CUDA FP16", !cpu, fp16 && !cpu) { fp16 = !fp16 }
        Toggle("仅 CPU", true, cpu) { cpu = !cpu }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { session.setOptions(cacheGiB, quality, cpu, fp16); finished() },
            text = "保存设置", size = ControlSize.Small)
        Button(onClick = { session.prepareModel(); finished() }, text = "准备模型", enabled = idle,
            appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
    }
    Spacer(Modifier.height(2.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { confirm = "当前歌曲缓存" }, text = "清除当前歌曲",
            enabled = idle && status.selection()?.identity() != null, appearance = ButtonAppearance.Plain,
            intent = ButtonIntent.Destructive, size = ControlSize.Small)
        Button(onClick = { confirm = "全部音轨缓存" }, text = "清除全部缓存",
            enabled = idle && status.cacheBytes() > 0, appearance = ButtonAppearance.Plain,
            intent = ButtonIntent.Destructive, size = ControlSize.Small)
        Button(onClick = { confirm = "模型文件" }, text = "清除模型", enabled = idle,
            appearance = ButtonAppearance.Plain, intent = ButtonIntent.Destructive, size = ControlSize.Small)
    }
    confirm?.let { target ->
        Text("将${target}移入回收站？", color = SaltTheme.colors.warning)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                when (target) {
                    "当前歌曲缓存" -> session.clearCurrent()
                    "全部音轨缓存" -> session.clearAll()
                    "模型文件" -> session.removeModel()
                }
                finished()
            }, text = "确认", enabled = idle, intent = ButtonIntent.Destructive, size = ControlSize.Small)
            Button(onClick = { confirm = null }, text = "取消", appearance = ButtonAppearance.Plain,
                size = ControlSize.Small)
        }
    }
}

@Composable
private fun Toggle(text: String, enabled: Boolean, selected: Boolean, action: () -> Unit) {
    val colors = ButtonDefaults.colors(appearance = if (selected) ButtonAppearance.Filled else ButtonAppearance.Subtle)
    val background by animateColorAsState(colors.containerColor, tween(180))
    val foreground by animateColorAsState(colors.contentColor, tween(180))
    BasicButton(onClick = action, enabled = enabled, size = ControlSize.Small,
        modifier = Modifier.semantics { this.selected = selected },
        colors = colors.copy(containerColor = background, contentColor = foreground)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
