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
}
