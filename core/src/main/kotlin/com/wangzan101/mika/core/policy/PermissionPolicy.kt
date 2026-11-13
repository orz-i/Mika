package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Declarative rule outcome. */
enum class PermissionEffect { Allow, Ask, Deny }

/** Grant / rule persistence scope. */
enum class ApprovalScope {
    /** Single-use grant: consumed after matching one action. */
    Once,
    /** Valid for the duration of the current run/session. */
    Session,
    /** Persisted across runs via [PersistentPermissionStore]. */
    Persistent,
}

/** Pattern matcher for tool names or capability ids. */
sealed interface NameMatcher {
    fun matches(value: String?): Boolean

    data object Any : NameMatcher {
        override fun matches(value: String?) = true
    }

    data class Exact(val value: String) : NameMatcher {
        override fun matches(value: String?) = value == this.value
    }

    /** Simple glob supporting `*` and `?` wildcards. */
    data class Glob(val pattern: String) : NameMatcher {
        private val regex = buildGlobRegex(pattern)
        override fun matches(value: String?) = value != null && regex.matches(value)
    }

    data class RegexMatch(val pattern: Regex) : NameMatcher {
        override fun matches(value: String?) = value != null && pattern.containsMatchIn(value)
    }

    companion object {
        private fun buildGlobRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (ch in pattern) {
                when (ch) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append(".")
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(ch)
                    else -> sb.append(ch)
                }
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}

/** Pattern matcher for JSON arguments passed to a tool call. */
sealed interface ArgMatcher {
    fun matches(element: JsonElement?): Boolean

    data class Exact(val value: String) : ArgMatcher {
        override fun matches(element: JsonElement?) = textOf(element) == value
    }

    data class Prefix(val prefix: String) : ArgMatcher {
        override fun matches(element: JsonElement?) = textOf(element)?.startsWith(prefix) == true
    }

    data class Contains(val substring: String, val ignoreCase: Boolean = false) : ArgMatcher {
        override fun matches(element: JsonElement?) = textOf(element)?.contains(substring, ignoreCase) == true
    }

    data class RegexMatch(val pattern: Regex) : ArgMatcher {
        override fun matches(element: JsonElement?) = textOf(element)?.let { pattern.containsMatchIn(it) } == true
    }

    data class AnyOf(val values: Set<String>) : ArgMatcher {
        override fun matches(element: JsonElement?) = textOf(element)?.let { it in values } == true
    }

    companion object {
        internal fun textOf(element: JsonElement?): String? = when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull
            else -> element.toString()
        }
    }
}

/**
 * Declarative permission rule matching tool name, capability, verb, argument patterns, and risk.
 * When [hard] is true and [effect] is [PermissionEffect.Deny], the rule represents a non-bypassable
 * safety baseline that even `autonomous` mode or `Allow` grants cannot override.
 */
data class PermissionRule(
    val id: String,
    val tool: NameMatcher = NameMatcher.Any,
    val capability: NameMatcher = NameMatcher.Any,
    val verb: Verb? = null,
    val argPatterns: Map<String, ArgMatcher> = emptyMap(),
    val risks: Set<ActionRisk>? = null,
    val paymentOnly: Boolean? = null,
    val effect: PermissionEffect,
    val scope: ApprovalScope = ApprovalScope.Session,
    val hard: Boolean = false,
    val priority: Int = 0,
    val reason: String? = null,
) {
    fun matches(ctx: PermissionContext): Boolean {
        if (!tool.matches(ctx.tool)) return false
        if (!capability.matches(ctx.capability)) return false
        if (verb != null && verb != ctx.verb) return false
        if (risks != null && ctx.risk.level !in risks) return false
        if (paymentOnly != null && ctx.risk.payment != paymentOnly) return false
        for ((key, matcher) in argPatterns) {
            if (!matcher.matches(ctx.args[key])) return false
        }
        return true
    }

    companion object {
        fun allow(
            id: String,
            tool: NameMatcher = NameMatcher.Any,
            capability: NameMatcher = NameMatcher.Any,
            verb: Verb? = null,
            argPatterns: Map<String, ArgMatcher> = emptyMap(),
            scope: ApprovalScope = ApprovalScope.Session,
            priority: Int = 0,
            reason: String? = null,
        ) = PermissionRule(id, tool, capability, verb, argPatterns, null, null, PermissionEffect.Allow, scope, false, priority, reason)

        fun ask(
            id: String,
            tool: NameMatcher = NameMatcher.Any,
            capability: NameMatcher = NameMatcher.Any,
            verb: Verb? = null,
            argPatterns: Map<String, ArgMatcher> = emptyMap(),
            risks: Set<ActionRisk>? = null,
            paymentOnly: Boolean? = null,
            priority: Int = 0,
            reason: String? = null,
        ) = PermissionRule(id, tool, capability, verb, argPatterns, risks, paymentOnly, PermissionEffect.Ask, ApprovalScope.Session, false, priority, reason)

        fun deny(
            id: String,
            tool: NameMatcher = NameMatcher.Any,
            capability: NameMatcher = NameMatcher.Any,
            verb: Verb? = null,
            argPatterns: Map<String, ArgMatcher> = emptyMap(),
            risks: Set<ActionRisk>? = null,
            paymentOnly: Boolean? = null,
            hard: Boolean = false,
            scope: ApprovalScope = ApprovalScope.Session,
            priority: Int = 0,
            reason: String? = null,
        ) = PermissionRule(id, tool, capability, verb, argPatterns, risks, paymentOnly, PermissionEffect.Deny, scope, hard, priority, reason)

        fun hardDeny(
            id: String,
            tool: NameMatcher = NameMatcher.Any,
            capability: NameMatcher = NameMatcher.Any,
            verb: Verb? = null,
            argPatterns: Map<String, ArgMatcher> = emptyMap(),
            risks: Set<ActionRisk>? = null,
            paymentOnly: Boolean? = null,
            reason: String? = null,
        ) = PermissionRule(id, tool, capability, verb, argPatterns, risks, paymentOnly, PermissionEffect.Deny, ApprovalScope.Persistent, hard = true, priority = Int.MAX_VALUE, reason = reason)
    }
}

/** Evaluation context for [PermissionPolicy]. */
data class PermissionContext(
    val tool: String,
    val capability: String? = null,
    val verb: Verb? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val risk: Risk = Risk(ActionRisk.Read),
    val provenance: RunProvenance = RunProvenance(),
    val autonomous: Boolean = false,
    val targetApp: String? = null,
    val selfPackage: String? = null,
    val writesScreen: Boolean = false,
)

/** Outcome of evaluating [PermissionPolicy]. */
sealed interface PermissionDecision {
    data class Allow(val scope: ApprovalScope, val ruleId: String? = null) : PermissionDecision
    data class Ask(val reason: String? = null, val ruleId: String? = null) : PermissionDecision
    data class Deny(val reason: String, val hard: Boolean = false, val ruleId: String? = null) : PermissionDecision
    /** No declarative rule applied; fall back to default [com.wangzan101.mika.core.action.ApprovalPolicy]. */
    data object Abstain : PermissionDecision
}

/** Storage abstraction for [ApprovalScope.Persistent] permission rules. */
interface PersistentPermissionStore {
    fun list(): List<PermissionRule>
    fun save(rule: PermissionRule)
    fun remove(id: String): Boolean
    fun clear()
}

/** Default thread-safe in-memory persistent store for tests and embedded core runners. */
class InMemoryPermissionStore(initial: List<PermissionRule> = emptyList()) : PersistentPermissionStore {
    private val rules = LinkedHashMap<String, PermissionRule>().apply {
        initial.forEach { put(it.id, it) }
    }

    @Synchronized override fun list(): List<PermissionRule> = rules.values.toList()
    @Synchronized override fun save(rule: PermissionRule) { rules[rule.id] = rule }
    @Synchronized override fun remove(id: String): Boolean = rules.remove(id) != null
    @Synchronized override fun clear() { rules.clear() }
}

/**
 * Policy engine deciding whether a tool call should be allowed, asked, or denied before execution.
 */
interface PermissionPolicy {
    fun evaluate(ctx: PermissionContext): PermissionDecision
    /** Non-consuming evaluation for pre-checks (e.g., hard-deny baseline before full classification). */
    fun peek(ctx: PermissionContext): PermissionDecision = evaluate(ctx)
    fun grant(rule: PermissionRule) {}
    fun revoke(ruleId: String): Boolean = false
    fun consumeOnce(ruleId: String): Boolean = false

    companion object {
        val DEFAULT: PermissionPolicy = RuleBasedPermissionPolicy()
    }
}

/**
 * Rule-based [PermissionPolicy] supporting declarative rules across [ApprovalScope.Once],
 * [ApprovalScope.Session], and [ApprovalScope.Persistent], while enforcing hard safety baselines
 * (such as `ownApp` boundary and `hard = true` Deny rules) even in `autonomous` mode.
 */
class RuleBasedPermissionPolicy(
    private val staticRules: List<PermissionRule> = emptyList(),
    private val persistentStore: PersistentPermissionStore = InMemoryPermissionStore(),
) : PermissionPolicy {

    private class Entry(val rule: PermissionRule, val sourceTier: Int)

    private val onceRules = LinkedHashMap<String, PermissionRule>()
    private val sessionRules = LinkedHashMap<String, PermissionRule>()

    @Synchronized
    override fun evaluate(ctx: PermissionContext): PermissionDecision = evaluateInternal(ctx, consume = true)

    @Synchronized
    override fun peek(ctx: PermissionContext): PermissionDecision = evaluateInternal(ctx, consume = false)

    private fun evaluateInternal(ctx: PermissionContext, consume: Boolean): PermissionDecision {
        // 1. Built-in hard safety baseline: Mika never operates on itself, even in autonomous mode.
        if (isOwnAppTarget(ctx)) {
            return PermissionDecision.Deny(reason = "own_app", hard = true, ruleId = BUILTIN_OWN_APP_RULE)
        }

        val candidates = collectCandidates(ctx)

        // 2. Hard Deny rules always win and cannot be bypassed by autonomous mode or Allow grants.
        val hardDeny = candidates.firstOrNull { it.rule.effect == PermissionEffect.Deny && it.rule.hard }
        if (hardDeny != null) {
            return PermissionDecision.Deny(
                reason = hardDeny.rule.reason ?: hardDeny.rule.id,
                hard = true,
                ruleId = hardDeny.rule.id,
            )
        }

        // 3. Autonomous mode bypasses interactive confirmations and non-hard policies after hard baselines pass.
        if (ctx.autonomous) {
            return PermissionDecision.Allow(ApprovalScope.Session, ruleId = AUTONOMOUS_RULE)
        }

        // 4. Pick highest-precedence matching rule: priority desc, runtime scope tier desc, Deny > Allow > Ask.
        val chosen = candidates.maxWithOrNull(PRECEDENCE) ?: return PermissionDecision.Abstain
        val r = chosen.rule
        return when (r.effect) {
            PermissionEffect.Deny -> PermissionDecision.Deny(r.reason ?: r.id, hard = false, ruleId = r.id)
            PermissionEffect.Ask -> PermissionDecision.Ask(r.reason, ruleId = r.id)
            PermissionEffect.Allow -> {
                if (consume && r.scope == ApprovalScope.Once) {
                    onceRules.remove(r.id)
                }
                PermissionDecision.Allow(r.scope, ruleId = r.id)
            }
        }
    }

    @Synchronized
    override fun grant(rule: PermissionRule) {
        when (rule.scope) {
            ApprovalScope.Once -> onceRules[rule.id] = rule
            ApprovalScope.Session -> sessionRules[rule.id] = rule
            ApprovalScope.Persistent -> persistentStore.save(rule)
        }
    }

    @Synchronized
    override fun revoke(ruleId: String): Boolean {
        var removed = onceRules.remove(ruleId) != null
        if (sessionRules.remove(ruleId) != null) removed = true
        if (persistentStore.remove(ruleId)) removed = true
        return removed
    }

    @Synchronized
    override fun consumeOnce(ruleId: String): Boolean = onceRules.remove(ruleId) != null

    @Synchronized
    fun clearSession() {
        onceRules.clear()
        sessionRules.clear()
    }

    private fun collectCandidates(ctx: PermissionContext): List<Entry> {
        val matched = mutableListOf<Entry>()
        onceRules.values.forEach { if (it.matches(ctx)) matched += Entry(it, TIER_ONCE) }
        sessionRules.values.forEach { if (it.matches(ctx)) matched += Entry(it, TIER_SESSION) }
        persistentStore.list().forEach { if (it.matches(ctx)) matched += Entry(it, TIER_PERSISTENT) }
        staticRules.forEach { if (it.matches(ctx)) matched += Entry(it, TIER_STATIC) }
        return matched
    }

    private fun isOwnAppTarget(ctx: PermissionContext): Boolean {
        val self = ctx.selfPackage ?: return false
        if (!ctx.writesScreen) return false
        if (ctx.targetApp == self) return true
        return OWN_APP_ARG_KEYS.any { k ->
            ArgMatcher.textOf(ctx.args[k])?.contains(self) == true
        }
    }

    private companion object {
        const val BUILTIN_OWN_APP_RULE = "builtin.own_app"
        const val AUTONOMOUS_RULE = "autonomous"
        val OWN_APP_ARG_KEYS = listOf("package", "uri")

        const val TIER_STATIC = 0
        const val TIER_PERSISTENT = 1
        const val TIER_SESSION = 2
        const val TIER_ONCE = 3

        fun effectRank(effect: PermissionEffect): Int = when (effect) {
            PermissionEffect.Deny -> 3
            PermissionEffect.Allow -> 2
            PermissionEffect.Ask -> 1
        }

        val PRECEDENCE = Comparator<Entry> { a, b ->
            val p = a.rule.priority.compareTo(b.rule.priority)
            if (p != 0) return@Comparator p
            val t = a.sourceTier.compareTo(b.sourceTier)
            if (t != 0) return@Comparator t
            effectRank(a.rule.effect).compareTo(effectRank(b.rule.effect))
        }
    }
}
