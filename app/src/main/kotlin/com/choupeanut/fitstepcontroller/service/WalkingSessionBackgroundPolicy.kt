package com.choupeanut.fitstepcontroller.service

import com.choupeanut.fitstepcontroller.domain.WalkingSessionState

/** Keeps the CPU awake only while a paced session can make progress. */
internal object WalkingSessionBackgroundPolicy {
    fun requiresCpuWakeLock(state: WalkingSessionState): Boolean {
        return state == WalkingSessionState.RUNNING
    }
}
