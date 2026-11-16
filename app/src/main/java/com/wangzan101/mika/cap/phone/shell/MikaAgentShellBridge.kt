package com.wangzan101.mika.cap.phone.shell

import android.content.Intent
import android.os.Bundle
import android.view.Surface
import com.agent.use.common.DisplayConfig
import com.agent.use.common.IAgentCustodyListener
import com.agent.use.common.IAgentShell
import com.agent.use.common.network.INetworkCapture
import com.wangzan101.mika.shell.IMikaShell

/**
 * 将 Mika 现有的 IMikaShell 桥接为 AgentDisplayKit 标准的 IAgentShell 接口。
 * 使得 Mika 能够直接利用通用的 AgentDisplayKit 客户端套件。
 */
class MikaAgentShellBridge(private val mikaShell: IMikaShell) : IAgentShell {

    override fun asBinder(): android.os.IBinder = mikaShell.asBinder()

    override fun createDisplay(surface: Surface, config: DisplayConfig): Int {
        return mikaShell.createDisplay(surface, config.width, config.height, config.dpi)
    }

    override fun releaseDisplay(displayId: Int) {
        mikaShell.releaseDisplay(displayId)
    }

    override fun releaseAll() {
        mikaShell.releaseAll()
    }

    override fun destroy() {
        mikaShell.destroy()
    }

    override fun startApp(displayId: Int, packageName: String): Int {
        return mikaShell.startApp(displayId, packageName)
    }

    override fun restartApp(displayId: Int, packageName: String): Int {
        return mikaShell.restartApp(displayId, packageName)
    }

    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int {
        return mikaShell.startAppInUser(displayId, packageName, userId, cold)
    }

    override fun startIntent(displayId: Int, intent: Intent): Int {
        return mikaShell.startView(displayId, intent.action ?: "", intent.dataString ?: "", intent.`package` ?: "")
    }

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int {
        return mikaShell.startView(displayId, action ?: "", uri ?: "", packageName ?: "")
    }

    override fun startShare(displayId: Int, packageName: String, text: String): Int {
        return mikaShell.startShare(displayId, packageName, text)
    }

    override fun topTaskId(displayId: Int): Int {
        return mikaShell.topTaskId(displayId)
    }

    override fun topActivity(displayId: Int): String? {
        return mikaShell.topActivity(displayId)
    }

    override fun displayOfTask(taskId: Int): Int {
        return mikaShell.displayOfTask(taskId)
    }

    override fun displayOfPackage(packageName: String): Int {
        return mikaShell.displayOfPackage(packageName)
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int {
        return mikaShell.displayOfPackageInUser(packageName, userId)
    }

    override fun packageOfTask(taskId: Int): String? {
        return mikaShell.packageOfTask(taskId)
    }

    override fun userOfTask(taskId: Int): Int {
        return mikaShell.userOfTask(taskId)
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        return mikaShell.moveTask(taskId, displayId)
    }

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        return mikaShell.finishDisplay(displayId, keep ?: emptyArray(), close ?: emptyArray())
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        return mikaShell.injectTap(displayId, x, y)
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        return mikaShell.injectKey(displayId, keyCode)
    }

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int {
        return mikaShell.injectCtrlKey(displayId, keyCode)
    }

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        return mikaShell.injectStroke(displayId, xs, ys, times)
    }

    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
        return mikaShell.injectAsciiText(displayId, taskId, packageName, text)
    }

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean {
        return mikaShell.setAudioMuted(packageName, muted)
    }

    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean {
        return mikaShell.setAudioMutedInUser(packageName, userId, muted)
    }

    override fun getAudioMode(packageName: String): String? {
        return mikaShell.getAudioMode(packageName)
    }

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        return mikaShell.getAudioModeInUser(packageName, userId)
    }

    override fun setAudioMode(packageName: String, mode: String): Boolean {
        return mikaShell.setAudioMode(packageName, mode)
    }

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        return mikaShell.setAudioModeInUser(packageName, userId, mode)
    }

    override fun setClipText(text: String): Int {
        return mikaShell.setClipText(text)
    }

    override fun restoreClip(): Boolean {
        return mikaShell.restoreClip()
    }

    override fun savedClipText(): String? {
        return mikaShell.savedClipText()
    }

    override fun restoreClipText(original: String, ours: String): Boolean {
        return mikaShell.restoreClipText(original, ours)
    }

    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean {
        return mikaShell.visibleOnMainScreen(packageName, userId)
    }

    override fun startCustody(displayId: Int, listener: IAgentCustodyListener?): Boolean {
        return mikaShell.startCustody(displayId, listener)
    }

    override fun stopCustody(displayId: Int) {
        mikaShell.stopCustody(displayId)
    }

    override fun launchOrigin(taskId: Int): Bundle? {
        return mikaShell.launchOrigin(taskId)
    }

    override fun resolveJumpTarget(
        taskId: Int,
        interstitialPackage: String?,
        initiatorPackage: String?,
    ): String? {
        return mikaShell.resolveJumpTarget(taskId, interstitialPackage, initiatorPackage)
    }

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        return mikaShell.runSystemCommand(displayId, command)
    }

    override fun runCommand(command: String): Bundle {
        return mikaShell.runCommand(command)
    }

    override fun listUsers(): String? {
        return mikaShell.listUsers()
    }

    override fun listPackages(userId: Int): String? {
        return mikaShell.listPackages(userId)
    }

    override fun isSystemPackage(packageName: String): Boolean {
        return mikaShell.isSystemPackage(packageName)
    }

    override fun appCopyUsers(): Bundle? {
        return mikaShell.appCopyUsers()
    }

    override fun identity(): IntArray {
        return mikaShell.identity()
    }

    override fun wakeDevice(): Boolean {
        return mikaShell.wakeDevice()
    }

    override fun dismissDeviceKeyguard(): Boolean {
        return mikaShell.dismissDeviceKeyguard()
    }

    override fun revealDeviceCredential(): Boolean {
        return mikaShell.revealDeviceCredential()
    }

    override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean {
        return mikaShell.revealDeviceCredentialTap(x, y)
    }

    override fun enterDeviceCredential(kind: Int, credential: ByteArray, clear: Int): Boolean {
        return mikaShell.enterDeviceCredential(kind, credential, clear)
    }

    override fun getNetworkCapture(): INetworkCapture? = null
}

/** 扩展方法：将 IMikaShell 转换为 IAgentShell */
fun IMikaShell.asAgentShell(): IAgentShell = MikaAgentShellBridge(this)
