package com.wangzan101.mika.cap.phone.shell

import com.agent.use.common.shell.ShellLinks as AauShellLinks
import com.wangzan101.mika.core.tool.LinkPolicy

/** 外壳服务打开链接前的两项判断，委托给 [LinkPolicy] 与 AAU [AauShellLinks]。 */
internal object ShellLinks {
    fun refuse(action: String?, uri: String?, pkg: String?): Int? =
        if (LinkPolicy.check(action, uri, pkg) != null) ShellService.START_INVALID_LINK else null

    fun inUse(candidates: List<String>, onMainScreen: Set<String>): Boolean =
        AauShellLinks.inUse(candidates, onMainScreen)
}
