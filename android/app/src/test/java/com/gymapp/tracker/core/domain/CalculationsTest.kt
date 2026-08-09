package com.gymapp.tracker.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `backend/tests/calculations.test.ts`.
 *
 * The point of duplicating these cases is to catch a drift between the two
 * implementations: if the Kotlin port ever disagrees with the backend, the same
 * workout would show different numbers depending on where it was computed.
 */
class CalculationsTest {

    // --- Volumen ------------------------------------------------------------

    @Test
    fun `berechnet Gewicht mal Wiederholungen`() {
        assertEquals(1000.0, setVolume(SetValues(weightKg = 100.0, reps = 10)), 0.001)
    }

    @Test
    fun `ignoriert Saetze ohne Gewicht oder Wiederholungen`() {
        assertEquals(0.0, setVolume(SetValues(weightKg = 100.0, reps = null)), 0.001)
        assertEquals(0.0, setVolume(SetValues(weightKg = null, reps = 10)), 0.001)
        assertEquals(0.0, setVolume(SetValues(durationSec = 1200)), 0.001)
    }

    @Test
    fun `summiert mehrere Saetze korrekt`() {
        // 100×10 + 110×8 + 110×7 = 1000 + 880 + 770 = 2650 kg
        val summary = summarizeSets(
            listOf(
                SetValues(weightKg = 100.0, reps = 10),
                SetValues(weightKg = 110.0, reps = 8),
                SetValues(weightKg = 110.0, reps = 7),
            ),
        )
        assertEquals(2650.0, summary.volumeKg, 0.001)
        assertEquals(3, summary.sets)
        assertEquals(25, summary.reps)
        assertEquals(110.0, summary.maxWeightKg!!, 0.001)
    }

    @Test
    fun `kann Aufwaermsaetze ausschliessen`() {
        val sets = listOf(
            SetValues(weightKg = 60.0, reps = 10, isWarmup = true),
            SetValues(weightKg = 100.0, reps = 10),
        )
        assertEquals(1600.0, summarizeSets(sets).volumeKg, 0.001)
        assertEquals(1000.0, summarizeSets(sets, includeWarmups = false).volumeKg, 0.001)
    }

    // --- 1RM ----------------------------------------------------------------

    @Test
    fun `gibt bei einer Wiederholung das Gewicht selbst zurueck`() {
        assertEquals(140.0, estimateOneRepMax(140.0, 1), 0.001)
    }

    @Test
    fun `rechnet nach der Epley-Formel`() {
        assertEquals(133.33, estimateOneRepMax(100.0, 10), 0.01)
        assertEquals(152.0, estimateOneRepMax(120.0, 8), 0.01)
    }

    @Test
    fun `deckelt die Wiederholungen bei 12 und meldet das`() {
        assertEquals(estimateOneRepMax(100.0, 12), estimateOneRepMax(100.0, 30), 0.001)
        assertTrue(isE1rmExtrapolated(30))
        assertFalse(isE1rmExtrapolated(8))
    }

    @Test
    fun `liefert 0 fuer unsinnige Eingaben`() {
        assertEquals(0.0, estimateOneRepMax(0.0, 10), 0.001)
        assertEquals(0.0, estimateOneRepMax(100.0, 0), 0.001)
        assertEquals(0.0, estimateOneRepMax(-50.0, 5), 0.001)
    }

    @Test
    fun `findet den besten Satz`() {
        val result = bestE1rm(
            listOf(
                SetValues(weightKg = 100.0, reps = 10), // 133.3
                SetValues(weightKg = 130.0, reps = 3), // 143.0
                SetValues(weightKg = 110.0, reps = 8), // 139.3
            ),
        )
        assertEquals(143.0, result.value, 0.5)
        assertEquals(130.0, result.set!!.weightKg!!, 0.001)
    }

    // --- Statistik ----------------------------------------------------------

    @Test
    fun `mean und median`() {
        assertEquals(110.0, mean(listOf(100.0, 110.0, 120.0)), 0.001)
        assertEquals(110.0, median(listOf(100.0, 110.0, 300.0)), 0.001)
        assertEquals(25.0, median(listOf(10.0, 20.0, 30.0, 40.0)), 0.001)
        assertEquals(0.0, median(emptyList()), 0.001)
    }

    @Test
    fun `gleitender Durchschnitt`() {
        assertEquals(listOf(10.0, 15.0, 25.0), movingAverage(listOf(10.0, 20.0, 30.0), 2))
    }

    @Test
    fun `prozentuale Veraenderung`() {
        assertEquals(10.0, percentChange(100.0, 110.0)!!, 0.001)
        assertEquals(-9.1, percentChange(110.0, 100.0)!!, 0.001)
        assertNull(percentChange(0.0, 100.0))
        assertNull(percentChange(null, 100.0))
    }

    @Test
    fun `robuster Trend nutzt den Median`() {
        val trend = robustTrend(listOf(100.0, 100.0, 100.0), listOf(110.0, 110.0, 110.0))
        assertEquals(10.0, trend.changePercent!!, 0.001)
        assertTrue(trend.reliable)
    }

    @Test
    fun `laesst einen Ausreisser den Trend nicht verfaelschen`() {
        // Ein einzelner Rekordsatz von 200 kg darf nicht als +50 % gelten.
        val trend = robustTrend(
            listOf(100.0, 100.0, 100.0, 100.0),
            listOf(100.0, 100.0, 100.0, 200.0),
        )
        assertEquals(0.0, trend.changePercent!!, 0.001)
    }

    @Test
    fun `meldet zu wenig Daten als unzuverlaessig`() {
        val trend = robustTrend(listOf(100.0), listOf(120.0))
        assertFalse(trend.reliable)
        assertNull(trend.changePercent)
    }

    // --- Gruppierung ---------------------------------------------------------

    @Test
    fun `fasst identische Saetze zusammen`() {
        val groups = groupSets(List(3) { SetValues(weightKg = 100.0, reps = 10) })
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
    }

    @Test
    fun `trennt unterschiedliche Saetze`() {
        val groups = groupSets(
            listOf(
                SetValues(weightKg = 100.0, reps = 10),
                SetValues(weightKg = 110.0, reps = 8),
                SetValues(weightKg = 110.0, reps = 8),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(2, groups[1].count)
    }
}
