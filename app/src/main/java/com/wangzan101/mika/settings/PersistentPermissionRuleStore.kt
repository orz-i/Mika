package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.policy.ApprovalScope
import com.wangzan101.mika.core.policy.ArgMatcher
import com.wangzan101.mika.core.policy.NameMatcher
import com.wangzan101.mika.core.policy.PermissionEffect
import com.wangzan101.mika.core.policy.PermissionRule
import com.wangzan101.mika.core.policy.PersistentPermissionStore
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.EffectKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed [PersistentPermissionStore] persisting [ApprovalScope.Persistent] permission rules
 * across runs and app restarts using atomic temp-file replacement.
 */
class PersistentPermissionRuleStore(private val file: File) : PersistentPermissionStore {

    private val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val rules = LinkedHashMap<String, PermissionRule>()

    init {
        loadFromDisk()
    }

    @Synchronized
    override fun list(): List<PermissionRule> = rules.values.toList()

    @Synchronized
    override fun save(rule: PermissionRule) {
        rules[rule.id] = rule
        persistToDisk()
    }

    @Synchronized
    override fun remove(id: String): Boolean {
        val removed = rules.remove(id) != null
        if (removed) persistToDisk()
        return removed
    }

    @Synchronized
    override fun clear() {
        if (rules.isEmpty() && !file.exists()) return
        rules.clear()
        persistToDisk()
    }

    private fun loadFromDisk() {
        if (!file.isFile) return
        val envelope = runCatching {
            codec.decodeFromString<StoredRuleEnvelope>(file.readText())
        }.getOrNull() ?: return
        envelope.rules.forEach { dto ->
            dto.toDomain()?.let { rules[it.id] = it }
        }
    }

    private fun persistToDisk() {
        val parent = file.parentFile
        parent?.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        val payload = StoredRuleEnvelope(rules = rules.values.map { StoredRule.fromDomain(it) })
        tmp.writeText(codec.encodeToString(payload))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class StoredRuleEnvelope(
        val version: Int = 1,
        val rules: List<StoredRule> = emptyList(),
    )

    @Serializable
    private data class StoredMatcher(
        val kind: String,
        val value: String = "",
        val values: List<String> = emptyList(),
        val ignoreCase: Boolean = false,
    )

    @Serializable
    private data class StoredRule(
        val id: String,
        val tool: StoredMatcher = StoredMatcher("any"),
        val capability: StoredMatcher = StoredMatcher("any"),
        val targetApp: StoredMatcher = StoredMatcher("any"),
        val effects: List<String>? = null,
        val origins: List<String>? = null,
        val unattendedOnly: Boolean? = null,
        val argPatterns: Map<String, StoredMatcher> = emptyMap(),
        val risks: List<String>? = null,
        val paymentOnly: Boolean? = null,
        val effect: String,
        val scope: String = ApprovalScope.Persistent.name,
        val hard: Boolean = false,
        val priority: Int = 0,
        val reason: String? = null,
    ) {
        fun toDomain(): PermissionRule? {
            val parsedEffect = PermissionEffect.entries.firstOrNull { it.name == effect } ?: return null
            val parsedScope = ApprovalScope.entries.firstOrNull { it.name == scope } ?: ApprovalScope.Persistent
            val parsedTool = toNameMatcher(tool) ?: return null
            val parsedCapability = toNameMatcher(capability) ?: return null
            val parsedTargetApp = toNameMatcher(targetApp) ?: return null
            val parsedEffects = effects?.mapNotNull { name -> EffectKind.entries.firstOrNull { it.name == name } }?.toSet()
            val parsedOrigins = origins?.mapNotNull { name -> RunOrigin.entries.firstOrNull { it.name == name } }?.toSet()
            val parsedRisks = risks?.mapNotNull { name -> ActionRisk.entries.firstOrNull { it.name == name } }?.toSet()
            val parsedArgs = LinkedHashMap<String, ArgMatcher>()
            for ((key, matcher) in argPatterns) {
                parsedArgs[key] = toArgMatcher(matcher) ?: return null
            }
            return PermissionRule(
                id = id,
                tool = parsedTool,
                capability = parsedCapability,
                targetApp = parsedTargetApp,
                effects = parsedEffects,
                origins = parsedOrigins,
                unattendedOnly = unattendedOnly,
                argPatterns = parsedArgs,
                risks = parsedRisks,
                paymentOnly = paymentOnly,
                effect = parsedEffect,
                scope = parsedScope,
                hard = hard,
                priority = priority,
                reason = reason,
            )
        }

        companion object {
            fun fromDomain(rule: PermissionRule) = StoredRule(
                id = rule.id,
                tool = fromNameMatcher(rule.tool),
                capability = fromNameMatcher(rule.capability),
                targetApp = fromNameMatcher(rule.targetApp),
                effects = rule.effects?.map { it.name },
                origins = rule.origins?.map { it.name },
                unattendedOnly = rule.unattendedOnly,
                argPatterns = rule.argPatterns.mapValues { (_, m) -> fromArgMatcher(m) },
                risks = rule.risks?.map { it.name },
                paymentOnly = rule.paymentOnly,
                effect = rule.effect.name,
                scope = rule.scope.name,
                hard = rule.hard,
                priority = rule.priority,
                reason = rule.reason,
            )

            private fun fromNameMatcher(m: NameMatcher): StoredMatcher = when (m) {
                NameMatcher.Any -> StoredMatcher("any")
                is NameMatcher.Exact -> StoredMatcher("exact", value = m.value)
                is NameMatcher.Glob -> StoredMatcher("glob", value = m.pattern)
                is NameMatcher.RegexMatch -> StoredMatcher("regex", value = m.pattern.pattern)
            }

            private fun toNameMatcher(m: StoredMatcher): NameMatcher? = when (m.kind) {
                "any" -> NameMatcher.Any
                "exact" -> NameMatcher.Exact(m.value)
                "glob" -> NameMatcher.Glob(m.value)
                "regex" -> runCatching { NameMatcher.RegexMatch(Regex(m.value)) }.getOrNull()
                else -> null
            }

            private fun fromArgMatcher(m: ArgMatcher): StoredMatcher = when (m) {
                is ArgMatcher.Exact -> StoredMatcher("exact", value = m.value)
                is ArgMatcher.Prefix -> StoredMatcher("prefix", value = m.prefix)
                is ArgMatcher.Contains -> StoredMatcher("contains", value = m.substring, ignoreCase = m.ignoreCase)
                is ArgMatcher.RegexMatch -> StoredMatcher("regex", value = m.pattern.pattern)
                is ArgMatcher.AnyOf -> StoredMatcher("any_of", values = m.values.toList())
            }

            private fun toArgMatcher(m: StoredMatcher): ArgMatcher? = when (m.kind) {
                "exact" -> ArgMatcher.Exact(m.value)
                "prefix" -> ArgMatcher.Prefix(m.value)
                "contains" -> ArgMatcher.Contains(m.value, m.ignoreCase)
                "regex" -> runCatching { ArgMatcher.RegexMatch(Regex(m.value)) }.getOrNull()
                "any_of" -> ArgMatcher.AnyOf(m.values.toSet())
                else -> null
            }
        }
    }
}
