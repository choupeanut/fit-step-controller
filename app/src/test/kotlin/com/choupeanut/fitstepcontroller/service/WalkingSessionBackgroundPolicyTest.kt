package com.choupeanut.fitstepcontroller.service

import com.choupeanut.fitstepcontroller.domain.WalkingSessionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WalkingSessionBackgroundPolicyTest {
    @Test
    fun cpuWakeLockIsRequiredOnlyWhileSessionIsRunning() {
        assertThat(WalkingSessionBackgroundPolicy.requiresCpuWakeLock(WalkingSessionState.RUNNING))
            .isTrue()
        assertThat(WalkingSessionState.values()
            .filter { it != WalkingSessionState.RUNNING }
            .all { !WalkingSessionBackgroundPolicy.requiresCpuWakeLock(it) })
            .isTrue()
    }
}
