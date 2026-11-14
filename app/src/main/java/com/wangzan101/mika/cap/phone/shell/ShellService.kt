package com.wangzan101.mika.cap.phone.shell

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.agent.use.common.shell.AgentShellEngine
import com.wangzan101.mika.shell.IMikaShell
import java.io.File
import kotlin.system.exitProcess

/**
 * 运行在 shell 身份(uid 2000)进程里的 Mika 服务，核心实现委托给 AAU [AgentShellEngine]。
 */
class ShellService(
    private val allowedUid: Int,
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {

    private val engine = AgentShellEngine(
        allowedUid = allowedUid,
        stateDir = stateDir,
        ledgerFileName = "mika_audio_mute_$allowedUid",
        displayName = "mika-agent",
        envDisplayVarName = "MIKA_DISPLAY_ID",
        clipLabel = CLIP_LABEL,
        tag = TAG,
        ownPageClass = SystemIntents.OWN_PAGE_CLASS,
        allowSystemIntent = SystemIntents::allowed,
        exitProcessFn = { exitProcess(0) },
    )

    /** 助手进程看 Mika 交来的心愿(见 [setRestoreWish]); Mika 死后据此决定要不要把它拉起来。 */
    @Volatile
    var onRestoreWish: ((Int) -> Unit)? = null

    fun recoverMuted() = engine.recoverMuted()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        engine.createDisplay(surface, width, height, dpi, name = "mika-agent")

    override fun releaseDisplay(displayId: Int) = engine.releaseDisplay(displayId)
    override fun releaseAll() = engine.releaseAll()
    override fun destroy() = engine.destroy()

    override fun startApp(displayId: Int, packageName: String): Int =
        engine.startApp(displayId, packageName)

    override fun restartApp(displayId: Int, packageName: String): Int =
        engine.restartApp(displayId, packageName)

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int =
        engine.startAppInUser(displayId, packageName, userId, cold)

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int =
        engine.startView(displayId, action, uri, packageName)

    override fun startShare(displayId: Int, packageName: String, text: String): Int =
        engine.startShare(displayId, packageName, text)

    override fun startSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int =
        engine.startSystemAction(displayId, intent, asMika)

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? =
        engine.finishDisplay(displayId, keep, close)

    override fun topTaskId(displayId: Int): Int = engine.topTaskId(displayId)
    override fun topActivity(displayId: Int): String? = engine.topActivity(displayId)
    override fun packageOfTask(taskId: Int): String? = engine.packageOfTask(taskId)
    override fun displayOfTask(taskId: Int): Int = engine.displayOfTask(taskId)
    override fun displayOfPackage(packageName: String): Int = engine.displayOfPackage(packageName)
    override fun displayOfPackageInUser(packageName: String, userId: Int): Int =
        engine.displayOfPackageInUser(packageName, userId)
    override fun userOfTask(taskId: Int): Int = engine.userOfTask(taskId)
    override fun moveTask(taskId: Int, displayId: Int): Boolean = engine.moveTask(taskId, displayId)

    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        engine.visibleOnMainScreen(packageName, userId)

    override fun watchMainScreen(windowMs: Int): Int = engine.watchMainScreen(windowMs)
    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = engine.mainArrivals(sinceUptimeMs)

    override fun injectKey(displayId: Int, keyCode: Int): Boolean = engine.injectKey(displayId, keyCode)
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int = engine.injectCtrlKey(displayId, keyCode)
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int =
        engine.injectAsciiText(displayId, taskId, packageName, text)
    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = engine.injectTap(displayId, x, y)
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        engine.injectStroke(displayId, xs, ys, times)

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean =
        engine.setAudioMuted(packageName, muted)
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean =
        engine.setAudioMutedInUser(packageName, userId, muted)
    override fun getAudioMode(packageName: String): String? = engine.getAudioMode(packageName)
    override fun getAudioModeInUser(packageName: String, userId: Int): String? =
        engine.getAudioModeInUser(packageName, userId)
    override fun setAudioMode(packageName: String, mode: String): Boolean =
        engine.setAudioMode(packageName, mode)
    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean =
        engine.setAudioModeInUser(packageName, userId, mode)

    override fun setClipText(text: String): Int = engine.setClipText(text)
    override fun restoreClip(): Boolean = engine.restoreClip()
    override fun savedClipText(): String? = engine.savedClipText()
    override fun restoreClipText(original: String, ours: String): Boolean =
        engine.restoreClipText(original, ours)

    override fun listUsers(): String? = engine.listUsers()
    override fun listPackages(userId: Int): String? = engine.listPackages(userId)
    override fun isSystemPackage(packageName: String): Boolean = engine.isSystemPackage(packageName)
    override fun appCopyUsers(): Bundle? = engine.appCopyUsers()

    override fun wakeDevice(): Boolean = engine.wakeDevice()
    override fun dismissDeviceKeyguard(): Boolean = engine.dismissDeviceKeyguard()
    override fun revealDeviceCredential(): Boolean = engine.revealDeviceCredential()
    override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean =
        engine.revealDeviceCredentialTap(x, y)
    override fun enterDeviceCredential(kind: Int, credential: ByteArray, clear: Int): Boolean =
        engine.enterDeviceCredential(kind, credential, clear)

    override fun identity(): IntArray = engine.identity()
    override fun runSystemCommand(displayId: Int, command: String): Bundle =
        engine.runSystemCommand(displayId, command)
    override fun runCommand(command: String): Bundle = engine.runCommand(command)
    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? = engine.openDevtoolsSocket(name)

    override fun setRestoreWish(flags: Int) {
        onRestoreWish?.invoke(flags)
    }

    companion object {
        private const val TAG = "MikaShell"
        private const val CLIP_LABEL = "Mika"

        const val START_OK = AgentShellEngine.START_OK
        const val START_IN_USE_ON_MAIN = AgentShellEngine.START_IN_USE_ON_MAIN
        const val START_NOT_FOUND = AgentShellEngine.START_NOT_FOUND
        const val START_ERROR = AgentShellEngine.START_ERROR
        const val START_OK_COLD_IGNORED = AgentShellEngine.START_OK_COLD_IGNORED
        const val START_UNRESOLVED = AgentShellEngine.START_UNRESOLVED
        const val START_INVALID_LINK = AgentShellEngine.START_INVALID_LINK
        const val START_IN_USE_BY_OTHER = AgentShellEngine.START_IN_USE_BY_OTHER
        const val START_USER_NOT_ALLOWED = AgentShellEngine.START_USER_NOT_ALLOWED

        const val KEY_COPY_USERS = AgentShellEngine.KEY_COPY_USERS
        const val KEY_COPY_SUMMARY = AgentShellEngine.KEY_COPY_SUMMARY

        const val CLIP_SET = AgentShellEngine.CLIP_SET
        const val CLIP_UNREADABLE = AgentShellEngine.CLIP_UNREADABLE
        const val CLIP_FAILED = AgentShellEngine.CLIP_FAILED

        internal fun displayFlags(sdk: Int, trusted: Boolean): Int =
            AgentShellEngine.displayFlags(sdk, trusted)

        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int =
            AgentShellEngine.injectMode(key, waitForFinish)

        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean =
            AgentShellEngine.sendPair(down, up, fallback)

        internal fun retryWithoutTrust(t: Throwable): Boolean =
            AgentShellEngine.retryWithoutTrust(t)

        internal fun sameCopy(taskPackages: Collection<String>, taskUser: Int?, packageName: String, userId: Int?): Boolean =
            AgentShellEngine.sameCopy(taskPackages, taskUser, packageName, userId)

        internal fun parsePlayAudioMode(output: String): String =
            AgentShellEngine.parsePlayAudioMode(output)
    }
}
