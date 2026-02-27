package com.example.campuswalktracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRecordPolicyTest {

    @Test
    fun `records home to university trip when transition completed and cooldown passed`() {
        val tripType = resolveAutoTripType(
            event = TripEvent.TRIP_HOME_TO_UNIVERSITY,
            canRecordTransition = true
        )

        assertEquals(AutoTripType.HOME_TO_UNIVERSITY, tripType)
    }

    @Test
    fun `records university to home trip when transition completed and cooldown passed`() {
        val tripType = resolveAutoTripType(
            event = TripEvent.TRIP_UNIVERSITY_TO_HOME,
            canRecordTransition = true
        )

        assertEquals(AutoTripType.UNIVERSITY_TO_HOME, tripType)
    }

    @Test
    fun `does not record when cooldown blocks transition`() {
        val tripType = resolveAutoTripType(
            event = TripEvent.TRIP_HOME_TO_UNIVERSITY,
            canRecordTransition = false
        )

        assertNull(tripType)
    }

    @Test
    fun `does not record for non-trip events`() {
        val tripType = resolveAutoTripType(
            event = TripEvent.NONE,
            canRecordTransition = true
        )

        assertNull(tripType)
    }
}