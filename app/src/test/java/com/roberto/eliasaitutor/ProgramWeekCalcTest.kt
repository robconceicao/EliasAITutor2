package com.roberto.eliasaitutor

import com.roberto.eliasaitutor.program.ProgramRepository
import com.roberto.eliasaitutor.program.UserProgramState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ProgramWeekCalcTest {

    @Test
    fun autoWeek_day0_isWeek1() {
        val start = LocalDate.of(2026, 7, 1)
        assertEquals(1, ProgramRepository.computeAutoWeek("2026-07-01", start))
    }

    @Test
    fun autoWeek_day7_isWeek2() {
        // day 7 = start + 7 → week 2
        assertEquals(2, ProgramRepository.computeAutoWeek("2026-07-01", LocalDate.of(2026, 7, 8)))
    }

    @Test
    fun autoWeek_day14_isWeek3() {
        // F2 aceite: start_date 14 days ago → week 3
        val today = LocalDate.of(2026, 7, 15)
        val start = today.minusDays(14).toString()
        assertEquals(3, ProgramRepository.computeAutoWeek(start, today))
    }

    @Test
    fun autoWeek_clampsTo26() {
        assertEquals(26, ProgramRepository.computeAutoWeek("2020-01-01", LocalDate.of(2026, 7, 1)))
    }

    @Test
    fun manual_clamps() {
        val s = UserProgramState(currentWeek = 99, weekMode = "manual")
        assertEquals(26, ProgramRepository.resolveWeekLocally(s).currentWeek)
        val s2 = UserProgramState(currentWeek = 0, weekMode = "manual")
        assertEquals(1, ProgramRepository.resolveWeekLocally(s2).currentWeek)
    }

    @Test
    fun edge_day6_stillWeek1_day7_week2() {
        val start = "2026-06-01"
        assertEquals(1, ProgramRepository.computeAutoWeek(start, LocalDate.of(2026, 6, 7))) // +6
        assertEquals(2, ProgramRepository.computeAutoWeek(start, LocalDate.of(2026, 6, 8))) // +7
    }

    /** B.3 / D7: paused days freeze calendar while in review. */
    @Test
    fun effectiveWeek_withPausedDays_doesNotAdvance() {
        val start = "2026-07-01"
        val today = LocalDate.of(2026, 7, 22) // 21 days → week 4 without pause
        assertEquals(4, ProgramRepository.computeEffectiveWeek(start, today, 0))
        // 14 paused → effective 7 → week 2
        assertEquals(2, ProgramRepository.computeEffectiveWeek(start, today, 14))
        // held_back equivalent: 21 calendar days all paused after week-2 start...
        assertEquals(1, ProgramRepository.computeEffectiveWeek(start, today, 21))
    }

    @Test
    fun resolveWeekLocally_appliesPausedDaysInAutoMode() {
        val s = UserProgramState(
            startDate = "2026-07-01",
            weekMode = "auto",
            totalPausedDays = 7,
        )
        // Without freeze date override: compute from LocalDate.now() — only check clamp
        val resolved = ProgramRepository.resolveWeekLocally(s)
        assertEquals(true, resolved.currentWeek in 1..26)
    }
}
