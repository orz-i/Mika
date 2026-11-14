package com.wangzan101.mika.core.policy

/**
 * Controls how [com.wangzan101.mika.core.guard.OperationGuards] reacts at its highest ladder threshold.
 */
enum class GuardMode {
    /** Softened guards: emit diagnostic warnings first and stop only at high safety-fuse thresholds. */
    SOFT,
    /** Advisory only: emit diagnostic warnings/notices without stopping the task. */
    WARN_ONLY,
}

/**
 * Execution policy bundle derived from a [RunProfile].
 * Keeps general runs free from anti-crawl / human-like UI pacing components while letting
 * device-operator profiles attach pacing and permissions cleanly.
 */
data class ExecutionPolicy(
    val pacing: PacingPolicy = PacingPolicy.NONE,
    val permissions: PermissionPolicy = PermissionPolicy.DEFAULT,
    val guardMode: GuardMode = GuardMode.SOFT,
    val enableAntiCrawlPacing: Boolean = false,
    val enableIdleScroll: Boolean = false,
) {
    companion object {
        val GENERAL = ExecutionPolicy(
            pacing = PacingPolicy.NONE,
            permissions = PermissionPolicy.DEFAULT,
            guardMode = GuardMode.SOFT,
            enableAntiCrawlPacing = false,
            enableIdleScroll = false,
        )

        val DEVICE_OPERATOR = ExecutionPolicy(
            pacing = PacingPolicy.NONE,
            permissions = PermissionPolicy.DEFAULT,
            guardMode = GuardMode.SOFT,
            enableAntiCrawlPacing = true,
            enableIdleScroll = true,
        )
    }
}

/**
 * Scenario profile distinguishing general agent execution from device/browser operator automation.
 * Phase 2 capability pacing (`PagePacing` / `IdleScroll` / `OutwardPacing`) attaches only under [DeviceOperator].
 */
sealed interface RunProfile {
    val name: String
    val executionPolicy: ExecutionPolicy

    val isDeviceOperator: Boolean get() = this is DeviceOperator
    val enableAntiCrawlPacing: Boolean get() = executionPolicy.enableAntiCrawlPacing
    val enableIdleScroll: Boolean get() = executionPolicy.enableIdleScroll
    val guardMode: GuardMode get() = executionPolicy.guardMode

    fun withPermissions(permissions: PermissionPolicy): RunProfile = when (this) {
        is General -> copy(executionPolicy = executionPolicy.copy(permissions = permissions))
        is DeviceOperator -> copy(executionPolicy = executionPolicy.copy(permissions = permissions))
    }

    fun withPacing(pacing: PacingPolicy): RunProfile = when (this) {
        is General -> copy(executionPolicy = executionPolicy.copy(pacing = pacing))
        is DeviceOperator -> copy(executionPolicy = executionPolicy.copy(pacing = pacing))
    }

    fun withGuardMode(guardMode: GuardMode): RunProfile = when (this) {
        is General -> copy(executionPolicy = executionPolicy.copy(guardMode = guardMode))
        is DeviceOperator -> copy(executionPolicy = executionPolicy.copy(guardMode = guardMode))
    }

    /** General coding, sandbox, web-search, MCP, or conversational tasks without UI anti-crawl pacing. */
    data class General(
        override val executionPolicy: ExecutionPolicy = ExecutionPolicy.GENERAL,
    ) : RunProfile {
        override val name: String get() = "general"
    }

    /** Phone/Web UI operator automation with anti-crawl dwell, idle scrolling, and outward pacing support. */
    data class DeviceOperator(
        override val executionPolicy: ExecutionPolicy = ExecutionPolicy.DEVICE_OPERATOR,
    ) : RunProfile {
        override val name: String get() = "device_operator"
    }

    companion object {
        val GENERAL: RunProfile = General()
        val DEVICE_OPERATOR: RunProfile = DeviceOperator()

        /** Capabilities that operate visible device/browser UI surfaces. */
        val UI_CAPABILITIES: Set<String> = setOf("phone", "web")

        /** Subagent roles that always execute as general tasks without human-like UI pacing. */
        val GENERAL_ROLES: Set<String> = setOf("coder", "reviewer", "researcher")

        /**
         * Resolves the appropriate [RunProfile] for a run based on allowed [capabilities],
         * optional subagent [role], [readOnly] mode, [remote] origin, [explicitUiTarget] marker,
         * and whether human-like pacing is enabled ([paceEnabled]).
         *
         * Returns [General] (zero blanket anti-crawl / idle-scroll delay) when:
         * - [paceEnabled] is false (e.g., `OperatingPace.FAST`),
         * - [readOnly] is true (e.g., background condition check or read-only inspection),
         * - [remote] is true (e.g., programmatic MCP invocation),
         * - [role] is in [GENERAL_ROLES] (`coder`, `reviewer`, `researcher`),
         * - [capabilities] omits UI surfaces (`phone` / `web`) or [capabilities] is `null` without [explicitUiTarget].
         *
         * When [capabilities] is `null` and [explicitUiTarget] is `false` (mixed general tasks), the profile
         * stays [General] while preserving a selective [pacing] policy (such as [SensitiveUiPacingPolicy])
         * so general/sandbox/MCP/read tasks run at full speed.
         * Otherwise returns [DeviceOperator].
         */
        fun resolve(
            capabilities: Set<String>? = null,
            role: String? = null,
            readOnly: Boolean = false,
            paceEnabled: Boolean = true,
            remote: Boolean = false,
            explicitUiTarget: Boolean = false,
            pacing: PacingPolicy = PacingPolicy.NONE,
            permissions: PermissionPolicy = PermissionPolicy.DEFAULT,
            guardMode: GuardMode = GuardMode.SOFT,
        ): RunProfile {
            val hasUiCapability = explicitUiTarget || (capabilities != null && capabilities.any { it in UI_CAPABILITIES })
            val generalRole = role != null && role.trim().lowercase() in GENERAL_ROLES
            return if (!paceEnabled || readOnly || remote || !hasUiCapability || generalRole) {
                val generalPacing = if (paceEnabled && !readOnly && !remote && !generalRole && capabilities == null) {
                    pacing
                } else {
                    PacingPolicy.NONE
                }
                General(
                    ExecutionPolicy.GENERAL.copy(
                        pacing = generalPacing,
                        permissions = permissions,
                        guardMode = guardMode,
                    ),
                )
            } else {
                DeviceOperator(
                    ExecutionPolicy.DEVICE_OPERATOR.copy(
                        pacing = pacing,
                        permissions = permissions,
                        guardMode = guardMode,
                    ),
                )
            }
        }

        /**
         * Resolves a child subagent's [RunProfile] attenuated by [parentProfile].
         * Child runs never escalate pacing beyond [parentProfile] and default to [General] for subagent roles,
         * read-only runs, or non-UI capability sets (`sandbox`, `mcp`).
         */
        fun resolveForChild(
            parentProfile: RunProfile,
            capabilities: Set<String>? = null,
            role: String? = null,
            readOnly: Boolean = false,
            explicitProfile: RunProfile? = null,
        ): RunProfile {
            val generalRole = role != null && role.trim().lowercase() in GENERAL_ROLES
            val isNonUiChild = capabilities != null && capabilities.none { it in UI_CAPABILITIES }
            if (parentProfile is General) {
                val childPacing = if (readOnly || generalRole || isNonUiChild) {
                    PacingPolicy.NONE
                } else {
                    parentProfile.executionPolicy.pacing
                }
                return General(
                    ExecutionPolicy.GENERAL.copy(
                        pacing = childPacing,
                        permissions = parentProfile.executionPolicy.permissions,
                        guardMode = explicitProfile?.guardMode ?: parentProfile.guardMode,
                    ),
                )
            }
            val resolved = resolve(
                capabilities = capabilities,
                role = role,
                readOnly = readOnly,
                paceEnabled = parentProfile.enableAntiCrawlPacing,
                explicitUiTarget = false,
                pacing = parentProfile.executionPolicy.pacing,
                permissions = parentProfile.executionPolicy.permissions,
                guardMode = explicitProfile?.guardMode ?: parentProfile.guardMode,
            )
            return if (explicitProfile is General && explicitProfile !== GENERAL) {
                General(
                    ExecutionPolicy.GENERAL.copy(
                        permissions = parentProfile.executionPolicy.permissions,
                        guardMode = explicitProfile.guardMode,
                    ),
                )
            } else {
                resolved
            }
        }
    }
}
