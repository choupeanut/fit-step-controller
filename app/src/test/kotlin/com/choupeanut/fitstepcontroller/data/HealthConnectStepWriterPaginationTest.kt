package com.choupeanut.fitstepcontroller.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.request.ReadRecordsRequest
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class HealthConnectStepWriterPaginationTest {
    @Test
    fun readAllStepsWalksEveryPageAndLeavesOriginFilterEmpty() = kotlinx.coroutines.test.runTest {
        val first = stepRecord(Instant.parse("2026-07-14T04:00:00Z"))
        val second = stepRecord(Instant.parse("2026-07-14T04:01:00Z"))
        val observedTokens = mutableListOf<String?>()
        val observedOriginFilters = mutableListOf<Set<androidx.health.connect.client.records.metadata.DataOrigin>>()
        val observedAscending = mutableListOf<Boolean>()
        val client = proxyClient { request ->
            observedTokens += request.pageToken
            observedOriginFilters += request.dataOriginFilter
            observedAscending += request.ascendingOrder
            when (request.pageToken) {
                null -> ReadRecordsResponse(listOf(first), "next-page")
                "next-page" -> ReadRecordsResponse(listOf(second), "")
                else -> error("unexpected page token ${request.pageToken}")
            }
        }

        val records = HealthConnectStepWriter(
            client = client,
            appPackageName = "com.example.test",
        ).readAllSteps(
            start = Instant.parse("2026-07-14T04:00:00Z"),
            end = Instant.parse("2026-07-14T05:00:00Z"),
            pageSize = 50,
        )

        assertThat(records).containsExactly(first, second).inOrder()
        assertThat(observedTokens).containsExactly(null, "next-page").inOrder()
        assertThat(observedOriginFilters).containsExactly(
            emptySet<androidx.health.connect.client.records.metadata.DataOrigin>(),
            emptySet<androidx.health.connect.client.records.metadata.DataOrigin>(),
        )
        assertThat(observedAscending).containsExactly(true, true)
    }

    @Test
    fun defaultBackfillAvailabilityClampsRangeAndCrossingRecordToLocalDayStart() =
        kotlinx.coroutines.test.runTest {
            val zone = ZoneId.of("Asia/Taipei")
            val localDayStart = Instant.parse("2026-07-13T16:00:00Z")
            val now = Instant.parse("2026-07-14T00:30:00Z")
            val crossingRecord = stepRecord(
                start = localDayStart.minusSeconds(10 * 60),
                end = localDayStart.plusSeconds(10 * 60),
            )
            val observedRequests = mutableListOf<ReadRecordsRequest<StepsRecord>>()
            val client = proxyClient { request ->
                observedRequests += request
                ReadRecordsResponse(listOf(crossingRecord), "")
            }

            val availability = HealthConnectStepWriter(
                client = client,
                zoneId = zone,
                appPackageName = "com.example.test",
            ).readBackfillAvailability(now = now)

            checkNotNull(availability)
            assertThat(availability.rangeStart).isEqualTo(localDayStart)
            assertThat(availability.rangeEnd).isEqualTo(now)
            assertThat(availability.occupiedWindows.single().start).isEqualTo(localDayStart)
            assertThat(availability.occupiedWindows.single().end)
                .isEqualTo(localDayStart.plusSeconds(10 * 60))
            assertThat(observedRequests).hasSize(1)
            assertThat(observedRequests.single().timeRangeFilter.startTime)
                .isEqualTo(localDayStart.minusSeconds(24 * 60 * 60))
            assertThat(observedRequests.single().timeRangeFilter.endTime).isEqualTo(now)
        }

    private fun stepRecord(
        start: Instant,
        end: Instant = start.plusSeconds(10),
    ): StepsRecord {
        return StepsRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            count = 100,
            metadata = androidx.health.connect.client.records.metadata.Metadata.manualEntry("test:${start.epochSecond}"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun proxyClient(
        reader: (ReadRecordsRequest<StepsRecord>) -> ReadRecordsResponse<StepsRecord>,
    ): HealthConnectClient {
        return Proxy.newProxyInstance(
            HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java),
        ) { _, method, args ->
            if (method.name == "readRecords") {
                reader(args!![0] as ReadRecordsRequest<StepsRecord>)
            } else {
                error("unexpected HealthConnectClient method ${method.name}")
            }
        } as HealthConnectClient
    }
}
