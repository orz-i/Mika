package com.wangzan101.mika.settings

import com.wangzan101.mika.core.custody.JumpConfirmProfile
import com.wangzan101.mika.core.custody.JumpConfirmProfileMatcher
import com.wangzan101.mika.core.custody.JumpConfirmProfilePolicy
import com.wangzan101.mika.core.custody.JumpConfirmProfileState
import com.wangzan101.mika.core.custody.JumpProfileSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 方案 2 兜底映射 Profile 的持久化仓库（存储于 `filesDir/jump-confirm-profiles.json`）：
 * 记录「(发起应用, 弹窗展示文案) -> 实际目标包名」映射，并向设置页暴露实时 [state]。
 */
class JumpConfirmProfileStore(
    private val file: File,
) : JumpConfirmProfilePolicy {
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    override fun current(): JumpConfirmProfileState = mutable.value

    @Synchronized
    fun setAutoLearn(enabled: Boolean) {
        update { cur -> cur.copy(autoLearnEnabled = enabled) }
    }

    @Synchronized
    override fun recordLearned(
        initiatorPackage: String,
        targetLabel: String,
        targetPackage: String,
        nowMs: Long,
    ) {
        val cur = current()
        if (!cur.available || !cur.autoLearnEnabled) return
        val cleanInitiator = initiatorPackage.trim()
        val cleanLabel = targetLabel.trim()
        val cleanTarget = targetPackage.trim()
        if (cleanInitiator.isEmpty() || cleanLabel.isEmpty() || cleanTarget.isEmpty()) return
        if (cleanInitiator.equals(cleanTarget, ignoreCase = true)) return
        val normLabel = JumpConfirmProfileMatcher.normalizeLabel(cleanLabel)
        if (normLabel.isEmpty()) return
        val id = JumpConfirmProfileMatcher.profileId(cleanInitiator, normLabel)

        val existing = cur.profiles.firstOrNull { it.id == id }
        if (existing != null) {
            // 用户主动停用或手动指定的条目不被自动学习静默改写或重新启用
            if (!existing.enabled || existing.source == JumpProfileSource.MANUAL) return
            val updated = existing.copy(
                targetLabel = cleanLabel,
                targetPackage = cleanTarget,
                updatedAtMs = nowMs,
            )
            persist(cur.copy(profiles = sortProfiles(cur.profiles.filter { it.id != id } + updated)))
            return
        }

        val added = JumpConfirmProfile(
            id = id,
            initiatorPackage = cleanInitiator,
            targetLabel = cleanLabel,
            targetPackage = cleanTarget,
            enabled = true,
            source = JumpProfileSource.LEARNED,
            updatedAtMs = nowMs,
        )
        persist(cur.copy(profiles = sortProfiles(cur.profiles + added)))
    }

    @Synchronized
    fun upsertManual(
        initiatorPackage: String,
        targetLabel: String,
        targetPackage: String,
        enabled: Boolean = true,
        replacingId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cleanInitiator = initiatorPackage.trim()
        val cleanLabel = targetLabel.trim()
        val cleanTarget = targetPackage.trim()
        if (cleanInitiator.isEmpty() || cleanLabel.isEmpty() || cleanTarget.isEmpty()) return false
        if (cleanInitiator.equals(cleanTarget, ignoreCase = true)) return false
        val normLabel = JumpConfirmProfileMatcher.normalizeLabel(cleanLabel)
        if (normLabel.isEmpty()) return false
        val id = JumpConfirmProfileMatcher.profileId(cleanInitiator, normLabel)
        val entry = JumpConfirmProfile(
            id = id,
            initiatorPackage = cleanInitiator,
            targetLabel = cleanLabel,
            targetPackage = cleanTarget,
            enabled = enabled,
            source = JumpProfileSource.MANUAL,
            updatedAtMs = nowMs,
        )
        return update { cur ->
            val filtered = cur.profiles.filter { it.id != id && it.id != replacingId }
            cur.copy(profiles = sortProfiles(filtered + entry))
        }
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        update { cur ->
            cur.copy(profiles = cur.profiles.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    @Synchronized
    fun remove(id: String) {
        update { cur ->
            cur.copy(profiles = cur.profiles.filterNot { it.id == id })
        }
    }

    @Synchronized
    fun clearLearned() {
        update { cur ->
            cur.copy(profiles = cur.profiles.filterNot { it.source == JumpProfileSource.LEARNED })
        }
    }

    @Synchronized
    fun clearAll() {
        update { cur ->
            cur.copy(profiles = emptyList())
        }
    }

    @Synchronized
    fun reset() = persist(JumpConfirmProfileState())

    private fun sortProfiles(list: List<JumpConfirmProfile>): List<JumpConfirmProfile> =
        list.sortedWith(
            compareByDescending<JumpConfirmProfile> { it.enabled }
                .thenByDescending { it.updatedAtMs }
                .thenBy { it.targetLabel },
        )

    private fun update(change: (JumpConfirmProfileState) -> JumpConfirmProfileState): Boolean {
        val cur = current()
        if (!cur.available) return false
        persist(change(cur))
        return true
    }

    private fun persist(next: JumpConfirmProfileState) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("autoLearnEnabled", next.autoLearnEnabled)
            put(
                "profiles",
                JsonArray(
                    next.profiles.map { p ->
                        buildJsonObject {
                            put("id", p.id)
                            put("initiatorPackage", p.initiatorPackage)
                            put("targetLabel", p.targetLabel)
                            put("targetPackage", p.targetPackage)
                            put("enabled", p.enabled)
                            put("source", p.source.name)
                            put("updatedAtMs", p.updatedAtMs)
                        }
                    },
                ),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        mutable.value = next.copy(available = true)
    }

    private fun load(): JumpConfirmProfileState {
        if (!file.exists()) return JumpConfirmProfileState()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == 1)
            val autoLearn = o["autoLearnEnabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val list = o.getValue("profiles").jsonArray.mapNotNull { el ->
                parseProfile(el.jsonObject)
            }
            JumpConfirmProfileState(profiles = sortProfiles(list), autoLearnEnabled = autoLearn, available = true)
        }.getOrElse { JumpConfirmProfileState(available = false) }
    }

    private fun parseProfile(o: JsonObject): JumpConfirmProfile? {
        val initiator = o["initiatorPackage"]?.jsonPrimitive?.content?.trim().orEmpty()
        val label = o["targetLabel"]?.jsonPrimitive?.content?.trim().orEmpty()
        val target = o["targetPackage"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (initiator.isEmpty() || label.isEmpty() || target.isEmpty()) return null
        val id = o["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: JumpConfirmProfileMatcher.profileId(initiator, label)
        val enabled = o["enabled"]?.jsonPrimitive?.boolean ?: true
        val source = o["source"]?.jsonPrimitive?.content?.let { raw ->
            JumpProfileSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: JumpProfileSource.LEARNED
        val updatedAtMs = o["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L
        return JumpConfirmProfile(
            id = id,
            initiatorPackage = initiator,
            targetLabel = label,
            targetPackage = target,
            enabled = enabled,
            source = source,
            updatedAtMs = updatedAtMs,
        )
    }
}
