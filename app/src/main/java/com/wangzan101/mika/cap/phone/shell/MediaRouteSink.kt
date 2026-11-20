package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** 让匹配 UID 的媒体走不外放的系统虚拟通道。只维持接收端，不读样本、不落盘，不碰麦克风。 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
internal class MediaRouteSink(private val context: Context) {
    private val api by lazy { Api() }

    fun open(uid: Int): AutoCloseable? {
        if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") != PackageManager.PERMISSION_GRANTED) return null
        return try {
            api.open(context, uid)
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            Log.w(TAG, "media route unavailable: ${cause.javaClass.simpleName}")
            null
        }
    }

    private class Api {
        private val rule = type("AudioMixingRule")
        private val ruleBuilder = type("AudioMixingRule\$Builder")
        private val mix = type("AudioMix")
        private val mixBuilder = type("AudioMix\$Builder")
        private val policy = type("AudioPolicy")
        private val policyBuilder = type("AudioPolicy\$Builder")
        private val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policy).apply {
            isAccessible = true
        }
        private val unregister = AudioManager::class.java.getMethod("unregisterAudioPolicy", policy)

        fun open(context: Context, uid: Int): AutoCloseable {
            val rules = ruleBuilder.getConstructor().newInstance()
            ruleBuilder.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(rules, rule.getField("MIX_ROLE_PLAYERS").getInt(null))
            val addRule = ruleBuilder.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            addRule.invoke(rules, rule.getField("RULE_MATCH_UID").getInt(null), uid)
            val matchUsage = rule.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            for (usage in listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) {
                addRule.invoke(rules, matchUsage, AudioAttributes.Builder().setUsage(usage).build())
            }
            val selector = ruleBuilder.getMethod("build").invoke(rules)
            val format = AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val mixConfig = mixBuilder.getConstructor(rule).newInstance(selector)
            mixBuilder.getMethod("setFormat", AudioFormat::class.java).invoke(mixConfig, format)
            mixBuilder.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixConfig, mix.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
            val route = mixBuilder.getMethod("build").invoke(mixConfig)
            val builder = policyBuilder.getConstructor(Context::class.java).newInstance(context)
            policyBuilder.getMethod("addMix", mix).invoke(builder, route)
            val config = policyBuilder.getMethod("build").invoke(builder)
            val manager = context.getSystemService(AudioManager::class.java)
            val createSink = policy.getMethod("createAudioRecordSink", mix)
            check(register.invoke(null, config) == 0) { "audio policy registration refused" }
            var input: AudioRecord? = null
            try {
                val sink = createSink.invoke(config, route) as? AudioRecord ?: error("no remote submix")
                input = sink
                check(sink.state == AudioRecord.STATE_INITIALIZED)
                sink.startRecording() // 只注册 AudioPolicy 不会切换现有输出；接收端必须真的打开。
                check(sink.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            } catch (e: Exception) {
                runCatching { input?.release() }.onFailure(e::addSuppressed)
                runCatching { unregister.invoke(manager, config) }.onFailure(e::addSuppressed)
                throw e
            }
            return object : AutoCloseable {
                override fun close() {
                    val recording = input
                    input = null
                    try {
                        recording?.release()
                    } finally {
                        unregister.invoke(manager, config)
                    }
                }
            }
        }

        private fun type(name: String) = Class.forName("android.media.audiopolicy.$name")
    }

    companion object {
        private const val TAG = "MikaMediaRoute"
    }
}
