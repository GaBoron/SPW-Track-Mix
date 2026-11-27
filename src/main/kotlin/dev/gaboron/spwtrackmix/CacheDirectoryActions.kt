@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.gaboron.spwtrackmix

import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal class CacheDirectoryActions(private val session: TrackMixSession) : AutoCloseable {
    private val picking = AtomicBoolean()
    @Volatile private var closed = false

    fun choose() {
        if (closed || !picking.compareAndSet(false, true)) return
        EventQueue.invokeLater {
            if (closed) {
                picking.set(false)
                return@invokeLater
            }
            val owner = try {
                val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                generateSequence(activeWindow) { it.owner }.firstNotNullOfOrNull { window ->
                    when (window) {
                        is ComposeWindow -> window.windowHandle
                        is ComposeDialog -> window.windowHandle
                        else -> null
                    }
                } ?: 0L
            } catch (failure: RuntimeException) {
                picking.set(false)
                reportPickerFailure(failure)
                return@invokeLater
            }
            Thread.ofPlatform().daemon().name("Track Mix folder picker").start {
                try {
                    val initial = session.cacheDirectory().takeIf { Files.isDirectory(it) }
                    val folder = WindowsFolderPicker.choose(owner, initial) ?: return@start
                    change(folder)
                } catch (failure: Throwable) {
                    reportPickerFailure(failure)
                } finally {
                    picking.set(false)
                }
            }
        }
    }

    fun reset() = change(null)

    private fun reportPickerFailure(failure: Throwable) {
        System.getLogger(javaClass.name).log(System.Logger.Level.WARNING, "Could not pick a cache directory", failure)
        toast("无法打开 Windows 文件夹选择器", WorkshopApi.Ui.ToastType.Error)
    }

    private fun change(folder: Path?) {
        if (closed) return
        val accepted = session.changeCacheDirectory(folder) { result ->
            toast(result.message(), if (result.success()) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Warning)
        }
        if (!accepted) toast("请等待当前后台任务结束后再切换缓存目录", WorkshopApi.Ui.ToastType.Warning)
    }

    private fun toast(message: String, type: WorkshopApi.Ui.ToastType) {
        EventQueue.invokeLater {
            if (!closed) WorkshopApi.ui.toast(message, type)
        }
    }

    override fun close() {
        closed = true
    }
}
