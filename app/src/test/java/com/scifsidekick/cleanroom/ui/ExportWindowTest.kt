package com.scifsidekick.cleanroom.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ExportWindowTest {
    private fun format() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    @Test fun `valid same-day range parses to start and end of the requested minutes`() {
        val zone = TimeZone.getDefault()
        val range = parseExportWindow(format(), "2026-09-01", "08:30", "2026-09-01", "17:45")
        checkNotNull(range)
        val start = Calendar.getInstance(zone).apply { timeInMillis = range.first }
        val end = Calendar.getInstance(zone).apply { timeInMillis = range.second }
        assertEquals(8, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, start.get(Calendar.MINUTE))
        assertEquals(17, end.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, end.get(Calendar.MINUTE))
        assertEquals(java.util.Calendar.SEPTEMBER, start.get(Calendar.MONTH))
    }

    @Test fun `end before start is rejected`() {
        assertNull(parseExportWindow(format(), "2026-09-05", "00:00", "2026-09-01", "00:00"))
    }

    @Test fun `malformed date is rejected`() {
        assertNull(parseExportWindow(format(), "09-01-2026", "00:00", "2026-09-01", "23:59"))
        assertNull(parseExportWindow(format(), "2026-13-40", "00:00", "2026-09-01", "23:59"))
    }

    @Test fun `malformed time is rejected`() {
        assertNull(parseExportWindow(format(), "2026-09-01", "24:00", "2026-09-01", "23:59"))
        assertNull(parseExportWindow(format(), "2026-09-01", "9:30", "2026-09-01", "23:59"))
        assertNull(parseExportWindow(format(), "2026-09-01", "09:30", "2026-09-01", "not-a-time"))
    }

    @Test fun `same date and time is a valid single-instant window`() {
        val range = parseExportWindow(format(), "2026-09-01", "12:00", "2026-09-01", "12:00")
        checkNotNull(range)
        assertEquals(range.first, range.second)
    }
}
