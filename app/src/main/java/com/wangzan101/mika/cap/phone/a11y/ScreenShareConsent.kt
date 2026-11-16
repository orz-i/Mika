package com.wangzan101.mika.cap.phone.a11y

import android.view.Gravity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.AppAccessStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassMaterials
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.liquidGlass
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val ConsentGlassMaterial = GlassMaterials.Dialog.copy(
    shadowElevation = 8.dp,
    shadowAlphaLight = 0.22f,
    shadowAlphaDark = 0.44f,
)

/** 用户主动分享的授权浮层;不会先读取应用内容。 */
internal suspend fun requestScreenShare(service: ScreenAccessibility, store: AppAccessStore, pkg: String, label: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val protection = store.protectUi()
        var window: OverlayWindow? = null
        fun finish(choice: Int) {
            if (!continuation.isActive) return
            val allowed = runCatching { applyShareChoice(choice, pkg, store) }.getOrDefault(false)
            window?.close()
            protection.close()
            continuation.resume(allowed)
        }
        window = service.overlays().open(OverlayPlacement(Gravity.CENTER), onOutside = { finish(0) }) {
            MikaTheme(reduceMotion = animationsOff(service)) {
                val c = MikaTheme.colors
                Box(Modifier.padding(10.dp)) {
                    Column(
                        Modifier
                            .widthIn(min = 280.dp, max = 340.dp)
                            .liquidGlass(ConsentGlassMaterial, backdrop = null, shape = MikaShapes.Dialog)
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.appaccess_share_title, label), style = MikaType.Title, color = c.ink)
                        Text(stringResource(R.string.appaccess_share_body), style = MikaType.Body, color = c.ink2)
                        MikaButton(
                            stringResource(R.string.appaccess_share_allow),
                            { finish(1) },
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            kind = ButtonKind.Primary,
                            size = ButtonSize.Medium,
                        )
                        MikaButton(
                            stringResource(R.string.appaccess_share_unblock),
                            { finish(2) },
                            Modifier.fillMaxWidth(),
                            kind = ButtonKind.Secondary,
                            size = ButtonSize.Medium,
                        )
                        MikaButton(
                            stringResource(R.string.appaccess_share_cancel),
                            { finish(0) },
                            Modifier.fillMaxWidth(),
                            kind = ButtonKind.Tonal,
                            size = ButtonSize.Medium,
                        )
                    }
                }
            }
        }
        if (window == null) finish(0)
        continuation.invokeOnCancellation { window?.close(); protection.close() }
    }
