package com.choupeanut.fitstepcontroller.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthConnectStepWriterTest {
    @Test
    fun appPackageNameMustBePresentToScopeRawReads() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthConnectStepWriter.requireAppPackageName(null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthConnectStepWriter.requireAppPackageName("   ")
        }
        assertThat(HealthConnectStepWriter.requireAppPackageName("com.example.steps"))
            .isEqualTo("com.example.steps")
    }
}
