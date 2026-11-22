package com.wangzan101.mika.cap.phone

import android.content.Context
import android.content.res.AssetManager
import com.agent.use.ner.AauEntityExtractor
import com.wangzan101.mika.core.text.TextEntities
import com.wangzan101.mika.core.text.TextEntity

/**
 * 端上实体识别，模型随 APK 走（`:aau-ner`，1.3MB，纯 CPU，不联网）。
 *
 * 第一次用到才加载：构造要读整份模型并建解释器，几十毫秒，不放在启动路径上。
 * 进程内共用一份，[AauEntityExtractor.extract] 本身是同步的，多任务同时用不会踩。
 * 资产没随包（模型不入库，见 `aau-ner/README.md`）或 ABI 没有 LiteRT 运行库时返回 null，
 * 工具就不会发给模型，而不是等到调用时才炸。
 */
internal object LocalEntities {
    private const val MODEL = "aau_ner_v1.tflite"

    @Volatile private var assets: AssetManager? = null

    @Volatile private var extractor: AauEntityExtractor? = null

    fun create(context: Context): TextEntities? {
        if (!AauEntityExtractor.supported) return null
        val manager = context.applicationContext.assets
        if (runCatching { manager.open(MODEL).close() }.isFailure) return null
        assets = manager
        return TextEntities(::extract)
    }

    private fun extract(text: String): List<TextEntity> {
        val model = extractor ?: synchronized(this) {
            extractor ?: AauEntityExtractor(checkNotNull(assets) { "LocalEntities.create() was not called" })
                .also { extractor = it }
        }
        return model.extract(text).map { TextEntity(snake(it.type.name), it.start, it.end, it.text) }
    }

    /** `AdministrativeDivision` → `administrative_division`；工具结果里的 kind 用这个写法。 */
    private fun snake(name: String) = name.replace(CAMEL, "$1_$2").lowercase()

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
}
