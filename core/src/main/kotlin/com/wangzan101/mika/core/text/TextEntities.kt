package com.wangzan101.mika.core.text

/**
 * 一段文字里认出的一处实体。[start] 和 [end] 是半开区间，指向传给 [TextEntities.extract] 的原串；
 * [text] 就是那段切片，省得调用方自己再切一次。
 */
data class TextEntity(val kind: String, val start: Int, val end: Int, val text: String)

/**
 * 端上实体识别。真模型随 app 走（见 `:aau-ner`），core 只认这个接口，
 * 不把 Android 或推理运行时带进来：单测给个假实现就够。
 *
 * 实现要能在调用线程上跑几十毫秒（512 字一窗），别在主线程上用。
 */
fun interface TextEntities {
    fun extract(text: String): List<TextEntity>
}
