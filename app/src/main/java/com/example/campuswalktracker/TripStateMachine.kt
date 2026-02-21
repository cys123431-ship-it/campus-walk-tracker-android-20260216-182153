package com.example.campuswalktracker

data class TripState(
    val stableZone: String = TripStateMachine.ZONE_UNKNOWN,
    val journeyOriginZone: String? = null,
    val arrivalCandidateZone: String? = null,
    val arrivalCandidateSinceMs: Long = 0L,
    val arrivalCandidateSamples: Int = 0
)

enum class TripEvent {
    NONE,
    JOURNEY_STARTED,
    JOURNEY_CANCELLED,
    TRIP_HOME_TO_UNIVERSITY,
    TRIP_UNIVERSITY_TO_HOME
}

data class TripStepResult(
    val state: TripState,
    val event: TripEvent
)

class TripStateMachine(
    private val arrivalConfirmMs: Long,
    private val arrivalMinSamples: Int
) {
    companion object {
        const val ZONE_HOME = "home"
        const val ZONE_UNIVERSITY = "university"
        const val ZONE_UNKNOWN = "unknown"
    }

    fun next(state: TripState, observedZone: String, nowMs: Long): TripStepResult {
        var nextState = state

        if (state.journeyOriginZone == null) {
            if (isKnown(state.stableZone) && observedZone == ZONE_UNKNOWN) {
                nextState = state.copy(
                    journeyOriginZone = state.stableZone,
                    arrivalCandidateZone = null,
                    arrivalCandidateSinceMs = 0L,
                    arrivalCandidateSamples = 0
                )
                return TripStepResult(nextState, TripEvent.JOURNEY_STARTED)
            }

            if (isKnown(observedZone)) {
                nextState = state.copy(stableZone = observedZone)
            }
            return TripStepResult(nextState, TripEvent.NONE)
        }

        val origin = state.journeyOriginZone
        val destination = oppositeOf(origin)

        if (observedZone == origin) {
            nextState = TripState(stableZone = origin)
            return TripStepResult(nextState, TripEvent.JOURNEY_CANCELLED)
        }

        if (observedZone != destination) {
            nextState = state.copy(
                arrivalCandidateZone = null,
                arrivalCandidateSinceMs = 0L,
                arrivalCandidateSamples = 0
            )
            return TripStepResult(nextState, TripEvent.NONE)
        }

        val candidateSince = if (state.arrivalCandidateZone == destination) {
            state.arrivalCandidateSinceMs
        } else {
            nowMs
        }

        val candidateSamples = if (state.arrivalCandidateZone == destination) {
            state.arrivalCandidateSamples + 1
        } else {
            1
        }

        nextState = state.copy(
            arrivalCandidateZone = destination,
            arrivalCandidateSinceMs = candidateSince,
            arrivalCandidateSamples = candidateSamples
        )

        val confirmedDuration = nowMs - candidateSince
        val confirmed = confirmedDuration >= arrivalConfirmMs && candidateSamples >= arrivalMinSamples
        if (!confirmed) {
            return TripStepResult(nextState, TripEvent.NONE)
        }

        val completed = TripState(stableZone = destination)
        val event = if (origin == ZONE_HOME) {
            TripEvent.TRIP_HOME_TO_UNIVERSITY
        } else {
            TripEvent.TRIP_UNIVERSITY_TO_HOME
        }

        return TripStepResult(completed, event)
    }

    private fun isKnown(zone: String): Boolean {
        return zone == ZONE_HOME || zone == ZONE_UNIVERSITY
    }

    private fun oppositeOf(zone: String?): String {
        return if (zone == ZONE_HOME) ZONE_UNIVERSITY else ZONE_HOME
    }
}
