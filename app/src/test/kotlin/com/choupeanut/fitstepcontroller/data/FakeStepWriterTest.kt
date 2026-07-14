package com.choupeanut.fitstepcontroller.data

import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class FakeStepWriterTest {
    @Test
    fun readTotalAggregatesWrittenIntervals() = runTest {
        val writer = FakeStepWriter()
        val start = Instant.parse("2026-05-04T08:00:00Z")

        writer.write(StepWriteInterval(start, start.plusSeconds(60), 120))
        writer.write(StepWriteInterval(start.plusSeconds(60), start.plusSeconds(120), 130))

        assertThat(writer.readTotal(start, start.plusSeconds(120))).isEqualTo(250)
    }

    @Test
    fun writeAndVerifyReadsBackTheExactClientRecordAndKeepsAggregateDiagnostic() = runTest {
        val writer = FakeStepWriter()
        val start = Instant.parse("2026-05-04T08:00:00Z")
        val request = StepWriteRequest(
            interval = StepWriteInterval(start, start.plusSeconds(1_200), 3_000),
            clientRecordId = "direct-3000",
        )

        val result = writer.writeAndVerify(request)

        assertThat(result.clientRecordId).isEqualTo("direct-3000")
        assertThat(result.exactRecordCount).isEqualTo(3_000L)
        assertThat(result.aggregateSteps).isEqualTo(3_000L)
        assertThat(result.verified).isTrue()
    }

    @Test
    fun retryingSameClientRecordDoesNotDoubleCount() = runTest {
        val writer = FakeStepWriter()
        val start = Instant.parse("2026-05-04T08:00:00Z")
        val request = StepWriteRequest(
            interval = StepWriteInterval(start, start.plusSeconds(60), 120),
            clientRecordId = "chunk-1",
        )

        writer.writeAndVerify(request)
        val retry = writer.writeAndVerify(request)

        assertThat(retry.wasAlreadyPresent).isTrue()
        assertThat(writer.readTotal(start, start.plusSeconds(60))).isEqualTo(120L)
    }
}
