package io.github.mangi.eta.agent.accessibility

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentAccessibilityConnectionTest {
    private fun connect(service: AgentAccessibilityService) {
        AgentAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }.invoke(service)
    }

    @Test
    fun serviceCallbacksAdvanceConnectionAndOldInstanceCannotClearNewOne() {
        val first = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val second = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            val a = first.get()
            val b = second.get()
            val start = AgentAccessibilityService.connection.value.generation
            connect(a)
            val a1 = AgentAccessibilityService.connection.value
            assertSame(a, a1.service)
            assertTrue(a1.generation > start)
            a.onUnbind(null)
            val disconnected = AgentAccessibilityService.connection.value
            assertNull(disconnected.service)
            assertTrue(disconnected.generation > a1.generation)
            connect(a)
            val a2 = AgentAccessibilityService.connection.value
            assertSame(a, a2.service)
            assertTrue(a2.generation > disconnected.generation)
            connect(b)
            val current = AgentAccessibilityService.connection.value
            a.onUnbind(null)
            assertEquals(current, AgentAccessibilityService.connection.value)
            assertSame(b, AgentAccessibilityService.current())
        } finally {
            first.destroy()
            second.destroy()
        }
    }
}
