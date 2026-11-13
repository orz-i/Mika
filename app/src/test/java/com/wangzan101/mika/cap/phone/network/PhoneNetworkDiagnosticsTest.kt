package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.content.ContextWrapper
import com.agent.use.client.network.AgentNetworkManager
import com.agent.use.common.network.CaptureMode
import com.agent.use.common.network.HttpRequestEvent
import com.agent.use.common.network.INetworkCapture
import com.agent.use.common.network.INetworkEventListener
import com.agent.use.common.network.NetworkCaptureConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNetworkDiagnosticsTest {

    private fun dummyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun testVpnTlsSniBodyLookupReturnsExplicitTlsEncryptedErrorInsteadOfFake200() = runTest {
        var listenerRef: INetworkEventListener? = null
        val vpnMock = object : INetworkCapture.Stub() {
            var active = false
            override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                listenerRef = listener
                active = true
                return true
            }
            override fun stopCapture() { active = false }
            override fun getResponseBody(requestId: String?): ByteArray? = null
            override fun getActiveMode(): Int = if (active) CaptureMode.ACTIVE_MODE_VPN_SERVICE else CaptureMode.ACTIVE_MODE_NONE
        }

        val manager = PhoneNetworkManager(
            context = dummyContext(),
            shellProvider = { null },
            agentManagerFactory = { ctx, _ ->
                AgentNetworkManager(
                    context = ctx,
                    shell = null,
                    vpnEngineProvider = { vpnMock },
                    rootChecker = { false },
                    kernelChecker = { false }
                )
            },
            coroutineContext = coroutineContext
        )

        assertTrue(manager.attachToPackage("com.example.shop", CaptureMode.AUTO))
        val status = manager.captureStatus()
        assertTrue(status.capturing)
        assertEquals("VPN_SERVICE", status.activeMode)

        listenerRef!!.onRequestWillBeSent(
            HttpRequestEvent(
                requestId = "vpn_tls_1234",
                timestamp = System.currentTimeMillis(),
                url = "https://api.example.com/",
                method = "CONNECT",
                isTls = true
            )
        )
        delay(50)

        val bodyResp = manager.getResponseBody(requestId = "vpn_tls_1234", urlPattern = null, asString = true)
        assertNotNull(bodyResp)
        assertEquals("tls_encrypted_in_vpn_mode_use_root_ebpf", bodyResp?.error)
        assertEquals(0, bodyResp?.statusCode)
        manager.close()
    }

    @Test
    fun testCaptureStatusReportsFailureReasonWhenAttachFails() = runTest {
        val manager = PhoneNetworkManager(
            context = dummyContext(),
            shellProvider = { null },
            agentManagerFactory = { ctx, _ ->
                AgentNetworkManager(
                    context = ctx,
                    shell = null,
                    vpnEngineProvider = {
                        object : INetworkCapture.Stub() {
                            override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                                listener.onErrorOccurred("", "vpn_permission_not_granted")
                                return false
                            }
                            override fun stopCapture() {}
                            override fun getResponseBody(requestId: String?): ByteArray? = null
                            override fun getActiveMode(): Int = CaptureMode.ACTIVE_MODE_NONE
                        }
                    },
                    rootChecker = { false },
                    kernelChecker = { false }
                )
            },
            coroutineContext = coroutineContext
        )

        assertFalse(manager.attachToPackage("com.example.shop", CaptureMode.VPN_SERVICE))
        val status = manager.captureStatus()
        assertFalse(status.capturing)
        assertEquals("NONE", status.activeMode)
        assertEquals("vpn_permission_not_granted", status.error)
        manager.close()
    }
}
