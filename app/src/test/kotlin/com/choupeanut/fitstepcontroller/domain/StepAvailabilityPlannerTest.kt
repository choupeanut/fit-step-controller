package com.choupeanut.fitstepcontroller.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class StepAvailabilityPlannerTest {
    private val start = Instant.parse("2026-07-14T12:00:00Z")
    private val end = Instant.parse("2026-07-14T13:00:00Z")

    @Test
    fun noRecordsMakesWholeRangeAvailable() {
        val result = StepAvailabilityPlanner.analyze(start, end, emptyList())

        assertThat(result.availableWindows).containsExactly(
            AvailableStepWindow(start, end, 28_571),
        )
        assertThat(result.maxSteps).isEqualTo(28_571L)
        assertThat(result.totalAvailableDuration).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun overlappingAndTouchingRecordsFromMultipleSourcesAreMerged() {
        val result = StepAvailabilityPlanner.analyze(
            start,
            end,
            listOf(
                StepRecordWindow(start.plusSeconds(20 * 60), start.plusSeconds(30 * 60)),
                StepRecordWindow(start.plusSeconds(25 * 60), start.plusSeconds(40 * 60)),
                StepRecordWindow(start.plusSeconds(40 * 60), start.plusSeconds(45 * 60)),
            ),
        )

        assertThat(result.occupiedWindows).containsExactly(
            StepRecordWindow(start.plusSeconds(20 * 60), start.plusSeconds(45 * 60)),
        )
        assertThat(result.availableWindows).containsExactly(
            AvailableStepWindow(start, start.plusSeconds(20 * 60), 9_523),
            AvailableStepWindow(start.plusSeconds(45 * 60), end, 7_142),
        ).inOrder()
    }

    @Test
    fun recordsAreClippedToRangeAndTinyGapsAreIgnored() {
        val result = StepAvailabilityPlanner.analyze(
            start,
            end,
            listOf(
                StepRecordWindow(start.minusSeconds(2 * 60 * 60), start.plusSeconds(10)),
                StepRecordWindow(start.plusSeconds(10), start.plusSeconds(10).plusMillis(999)),
                StepRecordWindow(start.plusSeconds(10).plusMillis(999), start.plusSeconds(20)),
                StepRecordWindow(end.minusSeconds(10), end.plusSeconds(60 * 60)),
            ),
        )

        assertThat(result.availableWindows).containsExactly(
            AvailableStepWindow(start.plusSeconds(20), end.minusSeconds(10), 28_333),
        )
    }

    @Test
    fun allocateUsesOldestWindowsAndShortensFinalWindow() {
        val result = StepAvailabilityPlanner.analyze(
            start,
            end,
            listOf(StepRecordWindow(start.plusSeconds(10 * 60), start.plusSeconds(20 * 60))),
        )
        val allocations = StepAvailabilityPlanner.allocate(result, 1_000)

        assertThat(allocations).hasSize(1)
        assertThat(allocations.single().index).isEqualTo(0L)
        assertThat(allocations.single().interval.start).isEqualTo(start)
        assertThat(allocations.single().interval.count).isEqualTo(1_000L)
        assertThat(allocations.single().interval.end)
            .isEqualTo(start.plusMillis(126_000))
    }

    @Test
    fun cannotAllocateBeyondCurrentCapacity() {
        val result = StepAvailabilityPlanner.analyze(start, end, emptyList())

        assertThrows(IllegalArgumentException::class.java) {
            StepAvailabilityPlanner.allocate(result, result.maxSteps + 1)
        }
    }

    @Test
    fun todayMidnightRangeUsesLocalTimeAndReturnsNullAtMidnight() {
        val zone = ZoneId.of("Asia/Taipei")
        val atMidnight = Instant.parse("2026-07-13T16:00:00Z")
        val afterMidnight = Instant.parse("2026-07-14T04:30:00Z")

        assertThat(StepAvailabilityPlanner.todayMidnightRange(atMidnight, zone)).isNull()
        val range = StepAvailabilityPlanner.todayMidnightRange(afterMidnight, zone)
        assertThat(range).isEqualTo(
            atMidnight to afterMidnight,
        )
    }
}
