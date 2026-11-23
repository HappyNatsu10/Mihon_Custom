package eu.kanade.tachiyomi.ui.updates

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.updates.model.UpdatesDateFilter
import kotlin.time.Instant

class UpdatesDateCutoffTest {

    private val timeZone = TimeZone.UTC
    private val now = Instant.parse("2026-11-23T09:00:00Z")

    @Test
    fun `ALL with full history disabled guarantees 3 calendar months cutoff`() {
        val cutoff = computeUpdatesAfterCutoff(
            filterDateRange = UpdatesDateFilter.ALL,
            keepFullUpdatesHistory = false,
            now = now,
            timeZone = timeZone,
        )

        assertEquals(now.minus(3, DateTimeUnit.MONTH, timeZone), cutoff)
        assertEquals(Instant.parse("2026-08-23T09:00:00Z"), cutoff)
    }

    @Test
    fun `ALL with full history enabled uses epoch zero`() {
        val cutoff = computeUpdatesAfterCutoff(
            filterDateRange = UpdatesDateFilter.ALL,
            keepFullUpdatesHistory = true,
            now = now,
            timeZone = timeZone,
        )

        assertEquals(Instant.fromEpochMilliseconds(0), cutoff)
    }

    @Test
    fun `LAST_3_MONTHS uses exact 3 calendar months regardless of full history toggle`() {
        val cutoffWithFull = computeUpdatesAfterCutoff(
            filterDateRange = UpdatesDateFilter.LAST_3_MONTHS,
            keepFullUpdatesHistory = true,
            now = now,
            timeZone = timeZone,
        )
        val cutoffWithoutFull = computeUpdatesAfterCutoff(
            filterDateRange = UpdatesDateFilter.LAST_3_MONTHS,
            keepFullUpdatesHistory = false,
            now = now,
            timeZone = timeZone,
        )

        val expected = Instant.parse("2026-08-23T09:00:00Z")
        assertEquals(expected, cutoffWithFull)
        assertEquals(expected, cutoffWithoutFull)
    }

    @Test
    fun `day-based filters subtract exact day count`() {
        assertEquals(
            Instant.parse("2026-11-22T09:00:00Z"),
            computeUpdatesAfterCutoff(UpdatesDateFilter.TODAY, true, now, timeZone),
        )
        assertEquals(
            Instant.parse("2026-11-20T09:00:00Z"),
            computeUpdatesAfterCutoff(UpdatesDateFilter.LAST_3_DAYS, true, now, timeZone),
        )
        assertEquals(
            Instant.parse("2026-11-16T09:00:00Z"),
            computeUpdatesAfterCutoff(UpdatesDateFilter.LAST_7_DAYS, true, now, timeZone),
        )
        assertEquals(
            Instant.parse("2026-10-24T09:00:00Z"),
            computeUpdatesAfterCutoff(UpdatesDateFilter.LAST_30_DAYS, true, now, timeZone),
        )
    }
}
