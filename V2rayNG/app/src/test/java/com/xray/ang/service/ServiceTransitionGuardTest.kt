package com.xray.ang.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTransitionGuardTest {
    @Test
    fun requestStopWhileStartingDoesNotBounceBackToRunning() {
        val guard = ServiceTransitionGuard("test")

        assertTrue(guard.beginStart())

        guard.requestStop()
        guard.finishStart()

        assertEquals(ServiceTransitionGuard.Phase.STOPPING, guard.currentPhase())
        assertTrue(guard.isStopRequested())
    }
}
