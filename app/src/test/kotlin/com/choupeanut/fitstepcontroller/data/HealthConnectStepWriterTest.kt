package com.choupeanut.fitstepcontroller.data

import com.google.common.truth.Truth.assertThat
import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class HealthConnectStepWriterTest {
    @Test
    fun canonicalizesIntervalsToHealthConnectMillisecondPrecision() {
        val interval = StepWriteInterval(
            start = Instant.parse("2026-07-14T12:00:00.123456789Z"),
            end = Instant.parse("2026-07-14T12:20:00.987654321Z"),
            count = 3_000,
        )

        val normalized = HealthConnectStepWriter.normalizeInterval(interval)

        assertThat(normalized.start).isEqualTo(Instant.parse("2026-07-14T12:00:00.123Z"))
        assertThat(normalized.end).isEqualTo(Instant.parse("2026-07-14T12:20:00.987Z"))
    }

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

    @Test
    fun aggregateDiagnosticFailureIsIgnoredAfterExactVerification() = runTest {
        val aggregate = HealthConnectStepWriter.readAggregateOrNull {
            error("aggregate IPC unavailable")
        }

        assertThat(aggregate).isNull()
        val verified = VerifiedStepWrite(
            request = StepWriteRequest(
                interval = com.choupeanut.fitstepcontroller.domain.StepWriteInterval(
                    start = java.time.Instant.parse("2026-05-04T08:00:00Z"),
                    end = java.time.Instant.parse("2026-05-04T08:01:00Z"),
                    count = 100,
                ),
                clientRecordId = "test:aggregate-failure",
            ),
            recordsWritten = 1,
            exactRecordCount = 100,
            aggregateSteps = aggregate,
            platformRecordId = "record-1",
            wasAlreadyPresent = false,
        )
        assertThat(verified.verified).isTrue()
    }
}
