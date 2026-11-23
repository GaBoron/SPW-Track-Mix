@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package dev.gaboron.spwtrackmix

import androidx.compose.animation.animateColorAsState
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

private data class ScreenState(
    val status: TrackMixSession.Status,
    val stage: String,
    val fraction: Double
) {
    companion object {
        fun read(session: TrackMixSession): ScreenState {
            val status = session.status()
            return ScreenState(status, status.job()?.stage.orEmpty(), status.job()?.progress ?: 0.0)
        }
    }
}

@Composable
internal fun TrackMixerDialog(session: TrackMixSession, dismiss: () -> Unit) {
    BasicAdaptiveDialog(dismiss, AdaptiveDialogSize.Max) {
        TrackMixerContent(session, dismiss)
    }
}

@Composable
internal fun TrackMixerContent(session: TrackMixSession, dismiss: () -> Unit) {
    var state by remember { mutableStateOf(ScreenState.read(session)) }
    var options by remember { mutableStateOf(session.options()) }
    var manage by remember { mutableStateOf(false) }
    val update = {
        state = ScreenState.read(session)
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
            .heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(20.dp),
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
            ModelAndCache(session, state.status, options) {
                manage = false
                update()
            }
        } else {
            MixerBody(session, state, state.status.mix(), update) { manage = true }
        }
    }
}

@Composable
private fun MixerBody(session: TrackMixSession, state: ScreenState, mix: MixSettings,
                      update: () -> Unit, manage: () -> Unit) {
    val current = state.status.selection()
    val cached = current?.entry() != null
    val ready = cached && state.status.attached()
    val job = state.status.job()
    Text(current?.track()?.title() ?: "当前歌曲", style = SaltTheme.textStyles.main,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    val status = when {
        current == null -> "请在 SPW 中播放本地立体声歌曲"
        current.error().isNotBlank() -> current.error()
        ready && mix.original() -> "六轨已缓存 · 原曲直通"
        ready -> "六轨已缓存 · 实时混音"
        cached -> "六轨已缓存 · 重新打开这首歌以接入混音"
        else -> "原曲直通 · 当前歌曲尚无六轨缓存"
    }
    Text(status, style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText)

    if (job != null) {
        val suffix = if (state.fraction > 0 && state.fraction < 1) " ${(state.fraction * 100).roundToInt()}%" else ""
        Text("${job.title} · ${jobLabel(state.stage)}$suffix",
            style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(SaltTheme.shapes.small).background(SaltTheme.colors.stroke)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(state.fraction.toFloat().coerceIn(0f, 1f))
                .background(SaltTheme.colors.highlight))
        }
    } else if (state.status.notice().isNotBlank()) {
        Text(state.status.notice(), style = SaltTheme.textStyles.sub, color = SaltTheme.colors.subText,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!cached && job == null) {
            Button(onClick = { session.separate(); update() }, text = "分离六轨",
                enabled = current?.identity() != null, size = ControlSize.Small)
        }
        if (job != null) {
            Button(onClick = session::cancel, text = "取消", appearance = ButtonAppearance.Subtle,
                size = ControlSize.Small)
        }
        Button(onClick = manage, text = "模型和缓存", enabled = job == null,
            appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
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
private fun ModelAndCache(session: TrackMixSession, status: TrackMixSession.Status, options: TrackMixOptions,
                          finished: () -> Unit) {
    var cacheGiB by remember { mutableStateOf(options.cacheGiB()) }
    var quality by remember { mutableStateOf(options.quality()) }
    var fp16 by remember { mutableStateOf(options.fp16()) }
    var cpu by remember { mutableStateOf(options.cpu()) }
    var confirm by remember { mutableStateOf<String?>(null) }

    Text("BS-RoFormer SW Fixed", style = SaltTheme.textStyles.main)
    Text(if (status.modelReady()) "模型已下载" else "首次分离时下载模型",
        color = SaltTheme.colors.subText, style = SaltTheme.textStyles.sub)
    Text("音轨缓存 %.2f GiB".format(status.cacheBytes() / 1073741824.0), style = SaltTheme.textStyles.sub)

    RoundedColumn {
        ItemSlider(cacheGiB.toFloat(), { cacheGiB = it.roundToInt() }, "缓存上限", sub = "$cacheGiB GiB",
            valueRange = 1f..100f)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Toggle("高质量 TTA", true, quality) { quality = !quality }
        Toggle("CUDA FP16", !cpu, fp16) { fp16 = !fp16 }
        Toggle("仅 CPU", true, cpu) { cpu = !cpu }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { session.setOptions(cacheGiB, quality, cpu, fp16); finished() },
            text = "保存设置", size = ControlSize.Small)
        Button(onClick = { session.prepareModel(); finished() }, text = "准备模型",
            appearance = ButtonAppearance.Subtle, size = ControlSize.Small)
    }
    Spacer(Modifier.height(2.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { confirm = "当前歌曲缓存" }, text = "清除当前歌曲",
            enabled = status.selection()?.identity() != null, appearance = ButtonAppearance.Plain,
            intent = ButtonIntent.Destructive, size = ControlSize.Small)
        Button(onClick = { confirm = "全部音轨缓存" }, text = "清除全部缓存",
            enabled = status.cacheBytes() > 0, appearance = ButtonAppearance.Plain,
            intent = ButtonIntent.Destructive, size = ControlSize.Small)
        Button(onClick = { confirm = "模型文件" }, text = "清除模型",
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
            }, text = "确认", intent = ButtonIntent.Destructive, size = ControlSize.Small)
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

private fun jobLabel(stage: String): String = when (stage) {
    "runtime" -> "准备本地推理环境"
    "download" -> "下载模型"
    "verify" -> "校验模型"
    "load" -> "载入模型"
    "decode" -> "解码原曲"
    "gpu32" -> "CUDA FP32 分离"
    "gpu16" -> "CUDA FP16 分离"
    "cpu" -> "CPU 分离"
    "fallback" -> "切换推理设备或精度"
    "finish", "cache", "done" -> "写入六轨缓存"
    else -> "正在处理"
}
