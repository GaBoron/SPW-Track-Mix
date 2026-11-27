@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.gaboron.spwtrackmix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.moriafly.salt.ui.SaltColors
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.invoke.MethodType
import java.lang.reflect.Method

/** Exact UI bindings for the same Steam host build as the PCM hook. */
internal class TrackMixHostUi(private val session: TrackMixSession) : AutoCloseable {
    val cacheDirectories = CacheDirectoryActions(session)
    private val loader = WorkshopApi::class.java.classLoader
    private val handles = ArrayList<HookHandle>(2)
    private val theme = HostPalette(loader)
    val navigation = resolve(loader, "com.xuncorp.voxzen.ui.navigation.AppNavigationKt", "AppNavigation",
        "(Landroidx/compose/runtime/Composer;I)V")
    val navigationCaller = Class.forName("androidx.compose.ui.\u024e", false, loader)
    private val builder = resolve(loader, "androidx.compose.ui.\u0266", "\u037f",
        "(Lcom/xuncorp/voxzen/data/entity/Track;Lcom/xuncorp/pisces/PiscesMediaItem;" +
            "Landroidx/compose/ui/\u05dc;Lcom/xuncorp/voxzen/ui/navigation/Navigator;" +
            "Landroidx/compose/runtime/State;Landroidx/compose/ui/\u0924;)Lkotlin/Unit;")
    private val dismiss = resolve(loader, "androidx.compose.ui.\u05dc", "\u0529", "()V")
    private val append = resolve(loader, "androidx.compose.ui.\u0924", "\u037f",
        "(Lkotlin/jvm/functions/Function0;ZLkotlin/jvm/functions/Function2;)V")
    private val label = resolve(loader, "com.xuncorp.voxzen.ui.component.\u052c", "\u037f",
        "(Ljava/lang/String;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function2;" +
            "ZLandroidx/compose/runtime/Composer;II)V")
    private var visible by mutableStateOf(false)
    @Volatile private var closed = false

    fun install() {
        try {
            val registry = WorkshopApi.hookRegistrar
            handles += registry.afterComposable(
                navigation.declaringClass.name, navigation.name,
                navigationCaller.name, parameterTypes = emptyList()
            ) {
                if (visible && !closed) {
                    theme.Content {
                        TrackMixerDialog(session, cacheDirectories) { visible = false }
                    }
                }
            }
            handles += installNativeMenu()
        } catch (failure: Throwable) {
            runCatching(::close).onFailure(failure::addSuppressed)
            throw failure
        }
    }

    // Select the entry when the menu is built so rebuild toggles never retransform live host code.
    private fun installNativeMenu(): HookHandle =
        WorkshopApi.hookRegistrar.hookMethod(builder.declaringClass.name, builder.name,
            builder.parameterTypes.map { it.name }, object : MethodHook() {
                override fun after(call: MethodHookParam) {
                    if (closed || call.throwable != null || call.isOriginalSkipped || TrackMixLink.amllRebuildsPlayer()) return
                    val popup = call.args[2] ?: return
                    val scope = call.args[5] ?: return
                    val action: () -> Unit = {
                        dismiss.invoke(popup)
                        open()
                    }
                    val content: @Composable () -> Unit = {
                        label.invoke(null, "六轨混音", Modifier, null, true, currentComposer, 0, 0)
                    }
                    append.invoke(scope, action, true, content)
                }
            })

    fun open() {
        if (!closed) visible = true
    }

    @Synchronized
    override fun close() {
        closed = true
        visible = false
        cacheDirectories.close()
        var failure: Throwable? = null
        handles.asReversed().forEach { handle ->
            runCatching { handle.close() }.onFailure {
                val first = failure
                if (first == null) failure = it else first.addSuppressed(it)
            }
        }
        handles.clear()
        failure?.let { throw it }
    }
}

/** Read the active host palette, including its accent and light/dark state. */
private class HostPalette(loader: ClassLoader) {
    private val configs = resolve(loader, "androidx.compose.ui.\u02b0", "\u037f",
        "(Landroidx/compose/runtime/Composer;)Landroidx/compose/ui/\u02e4;")
    private val isDark = resolve(loader, "androidx.compose.ui.\u02e4", "\u037f", "()Z")
    private val colors = resolve(loader, "androidx.compose.ui.\u02b0", "\u0528",
        "(Landroidx/compose/runtime/Composer;)Landroidx/compose/ui/\u0374;")
    private val getters = listOf("\u037f", "\u0528", "\u0529", "\u052a", "\u052b", "\u052c",
        "\u052d", "\u052e", "\u052f", "\u0560", "\u0588")
        .map { resolve(loader, "androidx.compose.ui.\u0374", it, "()J") }

    @Composable
    fun Content(content: @Composable () -> Unit) {
        val composer: Composer = currentComposer
        val dark = isDark.invoke(configs.invoke(null, composer)) as Boolean
        val palette = colors.invoke(null, composer)
        val values = getters.map { Color((it.invoke(palette) as Long).toULong()) }
        val salt = SaltColors(
            highlight = values[0],
            text = values[1],
            subText = values[2],
            background = values[3],
            subBackground = values[4],
            popup = values[5],
            stroke = values[6],
            onHighlight = values[7],
            success = values[8],
            warning = values[9],
            error = values[10],
        )
        SaltTheme(configs = SaltConfigs.default(isDarkTheme = dark),
            dynamicColors = SaltDynamicColors(light = salt, dark = salt), content = content)
    }
}

private fun resolve(loader: ClassLoader, owner: String, name: String, descriptor: String): Method {
    val type = MethodType.fromMethodDescriptorString(descriptor, loader)
    return Class.forName(owner, false, loader).getDeclaredMethod(name, *type.parameterArray()).also {
        check(it.returnType == type.returnType() && it.trySetAccessible()) { "SPW 界面接口签名已改变" }
    }
}
