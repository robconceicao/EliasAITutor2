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

    /**
     * Manual respeita o gate de mastery (igual ao backend resolveWeek):
     * escolher a semana à mão não pula conteúdo não liberado pelo quiz.
     */
    @Test
    fun manual_clamps() {
        val s = UserProgramState(currentWeek = 99, weekMode = "manual", masteryClearedWeek = 25)
        assertEquals(26, ProgramRepository.resolveWeekLocally(s).currentWeek)
        val s2 = UserProgramState(currentWeek = 0, weekMode = "manual")
        assertEquals(1, ProgramRepository.resolveWeekLocally(s2).currentWeek)
        // sem mastery, manual não abre semana futura
        val s3 = UserProgramState(currentWeek = 20, weekMode = "manual", masteryClearedWeek = 2)
        assertEquals(3, ProgramRepository.resolveWeekLocally(s3).currentWeek)
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

    @Test
    fun masteryGate_neverOpensPastClearedPlusOne() {
        val s = UserProgramState(
            startDate = "2020-01-01", // calendar far ahead
            weekMode = "auto",
            totalPausedDays = 0,
            masteryClearedWeek = 0,
        )
        assertEquals(1, ProgramRepository.resolveWeekLocally(s).currentWeek)

        val s2 = s.copy(masteryClearedWeek = 2)
        assertEquals(3, ProgramRepository.resolveWeekLocally(s2).currentWeek)
    }

    @Test
    fun masteryGate_heldBackStaysOnCurrent() {
        val s = UserProgramState(
            startDate = "2020-01-01",
            weekMode = "auto",
            masteryClearedWeek = 1,
            heldBack = true,
            currentWeek = 2,
        )
        assertEquals(2, ProgramRepository.resolveWeekLocally(s).currentWeek)
    }

    // ─── Nivelamento: o início do programa não é fixo na Semana 1 ───

    @Test
    fun placement_startWeekAnchorsCalendar() {
        // Nivelado na Semana 9: dia 1 é a Semana 9, não a Semana 1.
        assertEquals(
            9,
            ProgramRepository.computeEffectiveWeek(
                "2026-07-01", LocalDate.of(2026, 7, 1), 0, 9
            )
        )
        assertEquals(
            10,
            ProgramRepository.computeEffectiveWeek(
                "2026-07-01", LocalDate.of(2026, 7, 8), 0, 9
            )
        )
        // Borda: dia 7 ainda é a mesma semana
        assertEquals(
            9,
            ProgramRepository.computeEffectiveWeek(
                "2026-07-01", LocalDate.of(2026, 7, 7), 0, 9
            )
        )
        // Sem nivelamento, comportamento antigo preservado
        assertEquals(
            1,
            ProgramRepository.computeEffectiveWeek(
                "2026-07-01", LocalDate.of(2026, 7, 1), 0, 1
            )
        )
    }

    @Test
    fun placement_neverFallsBelowStartWeek() {
        val s = UserProgramState(
            startDate = "2026-07-01",
            weekMode = "auto",
            startWeek = 15,
            masteryClearedWeek = 14,
        )
        assertEquals(15, ProgramRepository.resolveWeekLocally(s).currentWeek)

        // Manual tentando voltar para antes do nivelamento
        val manual = s.copy(weekMode = "manual", currentWeek = 3)
        assertEquals(15, ProgramRepository.resolveWeekLocally(manual).currentWeek)
    }

    @Test
    fun placement_gateStillAppliesAfterStartWeek() {
        val s = UserProgramState(
            startDate = "2020-01-01", // calendário muito à frente
            weekMode = "auto",
            startWeek = 9,
            masteryClearedWeek = 8,
        )
        val resolved = ProgramRepository.resolveWeekLocally(s)
        assertEquals(9, resolved.currentWeek)
        assertEquals(true, resolved.gateBlockingCalendar)
    }
}
