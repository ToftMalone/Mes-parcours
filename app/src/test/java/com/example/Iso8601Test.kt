package com.example

import com.example.util.parseIso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading of the timestamps of a GPX file.
 *
 * The previous implementation only recognized the UTC form suffixed `Z`. Files
 * produced by most watches and sports apps carry a time zone
 * offset: they all failed, every point took the import time,
 * and the chronology of the imported track was lost.
 */
class Iso8601Test {

    /** 2024-01-01T10:00:00Z, the reference of all the cases below. */
    private val reference = 1_704_103_200_000L

    @Test
    fun `the UTC form suffixed Z is read`() {
        assertEquals(reference, parseIso8601("2024-01-01T10:00:00Z"))
    }

    @Test
    fun `milliseconds are taken into account`() {
        assertEquals(reference + 123L, parseIso8601("2024-01-01T10:00:00.123Z"))
    }

    @Test
    fun `a time zone offset with a colon is applied`() {
        // 12:00 in UTC+02:00 is 10:00 UTC.
        assertEquals(reference, parseIso8601("2024-01-01T12:00:00+02:00"))
    }

    @Test
    fun `a time zone offset without a colon is applied`() {
        assertEquals(reference, parseIso8601("2024-01-01T12:00:00+0200"))
    }

    @Test
    fun `a time zone offset in hours only is applied`() {
        assertEquals(reference, parseIso8601("2024-01-01T12:00:00+02"))
    }

    @Test
    fun `a negative offset is applied the other way`() {
        // 05:00 in UTC-05:00 is 10:00 UTC.
        assertEquals(reference, parseIso8601("2024-01-01T05:00:00-05:00"))
    }

    @Test
    fun `a half-hour offset is applied`() {
        // 15:30 in UTC+05:30 (India) is 10:00 UTC.
        assertEquals(reference, parseIso8601("2024-01-01T15:30:00+05:30"))
    }

    @Test
    fun `milliseconds and offset combine`() {
        assertEquals(reference + 500L, parseIso8601("2024-01-01T12:00:00.500+02:00"))
    }

    @Test
    fun `the absence of a suffix means UTC as GPX requires`() {
        assertEquals(reference, parseIso8601("2024-01-01T10:00:00"))
    }

    @Test
    fun `spaces around the value are tolerated`() {
        assertEquals(reference, parseIso8601("  2024-01-01T10:00:00Z\n"))
    }

    @Test
    fun `a fraction longer than three digits is truncated to milliseconds`() {
        assertEquals(reference + 123L, parseIso8601("2024-01-01T10:00:00.123456Z"))
    }

    @Test
    fun `a shorter fraction is padded`() {
        // “.5” is five hundred milliseconds, not five.
        assertEquals(reference + 500L, parseIso8601("2024-01-01T10:00:00.5Z"))
    }

    @Test
    fun `an empty string is rejected`() {
        assertNull(parseIso8601(""))
    }

    @Test
    fun `a date without a time is rejected`() {
        assertNull(parseIso8601("2024-01-01"))
    }

    @Test
    fun `an unreadable suffix is rejected rather than shifted at random`() {
        assertNull(parseIso8601("2024-01-01T10:00:00+bidon"))
    }

    @Test
    fun `a nonexistent month is rejected`() {
        // Without isLenient = false, SimpleDateFormat converted the thirteenth month
        // into January of the following year instead of rejecting the value.
        assertNull(parseIso8601("2024-13-01T10:00:00Z"))
    }
}
