package com.example.campuswalktracker

import org.junit.Assert.assertEquals
import org.junit.Test

class TripStateMachineTest {

    private val machine = TripStateMachine(
        arrivalConfirmMs = 60_000L,
        arrivalMinSamples = 2
    )

    @Test
    fun `records home to university only after leaving home and confirmed arrival`() {
        var state = TripState(stableZone = TripStateMachine.ZONE_HOME)

        val step1 = machine.next(state, TripStateMachine.ZONE_HOME, nowMs = 0L)
        assertEquals(TripEvent.NONE, step1.event)
        state = step1.state

        val step2 = machine.next(state, TripStateMachine.ZONE_UNKNOWN, nowMs = 30_000L)
        assertEquals(TripEvent.JOURNEY_STARTED, step2.event)
        state = step2.state

        val step3 = machine.next(state, TripStateMachine.ZONE_UNIVERSITY, nowMs = 60_000L)
        assertEquals(TripEvent.NONE, step3.event)
        state = step3.state

        val step4 = machine.next(state, TripStateMachine.ZONE_UNIVERSITY, nowMs = 120_000L)
        assertEquals(TripEvent.TRIP_HOME_TO_UNIVERSITY, step4.event)
    }

    @Test
    fun `movement inside university zone never records a trip`() {
        var state = TripState(stableZone = TripStateMachine.ZONE_UNIVERSITY)

        repeat(5) { idx ->
            val step = machine.next(
                state = state,
                observedZone = TripStateMachine.ZONE_UNIVERSITY,
                nowMs = idx * 30_000L
            )
            assertEquals(TripEvent.NONE, step.event)
            state = step.state
        }
    }

    @Test
    fun `leaving and returning to origin cancels journey`() {
        var state = TripState(stableZone = TripStateMachine.ZONE_HOME)

        state = machine.next(state, TripStateMachine.ZONE_UNKNOWN, 10_000L).state
        val backHome = machine.next(state, TripStateMachine.ZONE_HOME, 20_000L)

        assertEquals(TripEvent.JOURNEY_CANCELLED, backHome.event)
        assertEquals(TripStateMachine.ZONE_HOME, backHome.state.stableZone)
        assertEquals(null, backHome.state.journeyOriginZone)
    }
}
