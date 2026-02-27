package com.example.campuswalktracker

enum class AutoTripType {
    HOME_TO_UNIVERSITY,
    UNIVERSITY_TO_HOME
}

fun resolveAutoTripType(event: TripEvent, canRecordTransition: Boolean): AutoTripType? {
    if (!canRecordTransition) {
        return null
    }

    return when (event) {
        TripEvent.TRIP_HOME_TO_UNIVERSITY -> AutoTripType.HOME_TO_UNIVERSITY
        TripEvent.TRIP_UNIVERSITY_TO_HOME -> AutoTripType.UNIVERSITY_TO_HOME
        else -> null
    }
}