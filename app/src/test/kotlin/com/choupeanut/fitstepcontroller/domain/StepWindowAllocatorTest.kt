package com.choupeanut.fitstepcontroller.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class StepWindowAllocatorTest {
    @Test
    fun allocatePastWindowCreatesNonOverlappingBackwardsWindows() {
        var cursor: Instant? = null
        val allocator = StepWindowAllocator(
            planner = StepPlanner(),
            loadCursor = { cursor },
            saveCursor = { cursor = it },
        )
        val now = Instant.parse("2026-05-04T08:00:00Z")

        val first = allocator.allocatePastWindow(steps = 1000, now = now)
        val second = allocator.allocatePastWindow(steps = 1000, now = now.plusSeconds(5))

        assertThat(first.end).isEqualTo(now)
        assertThat(second.end).isEqualTo(first.start)
        assertThat(second.start.isBefore(second.end)).isTrue()
        assertThat(first.start.isBefore(first.end)).isTrue()
    }
}
