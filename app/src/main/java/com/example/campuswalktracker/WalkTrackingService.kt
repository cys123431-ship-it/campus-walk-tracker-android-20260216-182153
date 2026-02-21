package com.example.campuswalktracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WalkTrackingService : Service() {

    companion object {
        const val ACTION_START = "com.example.campuswalktracker.action.START_TRACKING"
        const val ACTION_STOP = "com.example.campuswalktracker.action.STOP_TRACKING"
        const val ACTION_CHECK_NOW = "com.example.campuswalktracker.action.CHECK_NOW"
        const val ACTION_UNDO_LAST_RECORD = "com.example.campuswalktracker.action.UNDO_LAST_RECORD"

        const val EXTRA_RECORD_ID = "extra_record_id"

        private const val PREFS_NAME = "walk_tracker"

        private const val TRIP_HOME_TO_UNIVERSITY = "home_to_uni"
        private const val TRIP_UNIVERSITY_TO_HOME = "uni_to_home"

        private const val SOURCE_AUTO = "auto"

        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LNG = "home_lng"
        private const val KEY_UNIVERSITY_LAT = "university_lat"
        private const val KEY_UNIVERSITY_LNG = "university_lng"

        private const val KEY_AUTO_ENABLED = "auto_enabled"
        private const val KEY_LAST_KNOWN_ZONE = "last_known_zone"
        private const val KEY_LAST_AUTO_RECORD_TIME_MS = "last_auto_record_time_ms"
        private const val KEY_LAST_LOCATION_TIME_MS = "last_location_time_ms"

        private const val KEY_LAST_SAMPLE_LAT = "last_sample_lat"
        private const val KEY_LAST_SAMPLE_LNG = "last_sample_lng"
        private const val KEY_LAST_SAMPLE_TIME_MS = "last_sample_time_ms"
        private const val KEY_JOURNEY_MAX_SPEED_MPS = "journey_max_speed_mps"
        private const val KEY_JOURNEY_MOVING_SAMPLE_COUNT = "journey_moving_sample_count"
        private const val KEY_JOURNEY_HIGH_SPEED_SAMPLE_COUNT = "journey_high_speed_sample_count"

        private const val KEY_JOURNEY_ORIGIN_ZONE = "journey_origin_zone"
        private const val KEY_ARRIVAL_CANDIDATE_ZONE = "arrival_candidate_zone"
        private const val KEY_ARRIVAL_CANDIDATE_START_MS = "arrival_candidate_start_ms"
        private const val KEY_ARRIVAL_CANDIDATE_SAMPLE_COUNT = "arrival_candidate_sample_count"

        private const val KEY_LAST_AUTO_RECORD_ID = "last_auto_record_id"
        private const val KEY_LAST_AUTO_RECORD_DATE = "last_auto_record_date"
        private const val KEY_LAST_AUTO_RECORD_TYPE = "last_auto_record_type"
        private const val KEY_LAST_AUTO_RECORD_UNDONE = "last_auto_record_undone"

        private const val ZONE_HOME = TripStateMachine.ZONE_HOME
        private const val ZONE_UNIVERSITY = TripStateMachine.ZONE_UNIVERSITY
        private const val ZONE_UNKNOWN = TripStateMachine.ZONE_UNKNOWN

        private const val ZONE_RADIUS_METERS = 150f
        private const val ARRIVAL_CONFIRM_MS = 60_000L
        private const val ARRIVAL_MIN_SAMPLES = 2

        private const val AUTO_RECORD_COOLDOWN_MINUTES = 5
        private const val AUTO_RECORD_COOLDOWN_MS = AUTO_RECORD_COOLDOWN_MINUTES * 60 * 1000L

        private const val LOCATION_UPDATE_INTERVAL_MS = 30_000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 15_000L

        private const val WALKING_MAX_SPEED_MPS = 3.2f
        private const val MOVING_MIN_SPEED_MPS = 0.5f
        private const val MIN_MOVING_SAMPLE_COUNT = 2
        private const val NON_WALKING_SPEED_SAMPLE_THRESHOLD = 2

        private const val FOREGROUND_CHANNEL_ID = "walk_tracking_foreground"
        private const val RECORD_CHANNEL_ID = "walk_tracking_records"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val RECORD_NOTIFICATION_ID = 1002
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var locationClient: com.google.android.gms.location.FusedLocationProviderClient

    private var locationCallback: LocationCallback? = null

    private val stateMachine = TripStateMachine(
        arrivalConfirmMs = ARRIVAL_CONFIRM_MS,
        arrivalMinSamples = ARRIVAL_MIN_SAMPLES
    )

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        ensureNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }

            ACTION_CHECK_NOW -> requestCurrentLocation { location -> processLocation(location) }
            ACTION_UNDO_LAST_RECORD -> {
                val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
                undoLastAutoRecord(recordId)
            }

            else -> {
                if (prefs.getBoolean(KEY_AUTO_ENABLED, false)) {
                    startTracking()
                } else {
                    stopTracking()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationTracking()
        super.onDestroy()
    }

    private fun startTracking() {
        if (!hasHomeAndUniversity() || !hasLocationPermission()) {
            prefs.edit().putBoolean(KEY_AUTO_ENABLED, false).apply()
            stopTracking()
            return
        }

        prefs.edit().putBoolean(KEY_AUTO_ENABLED, true).apply()

        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        startLocationTracking()
        requestCurrentLocation { location -> processLocation(location) }
    }

    private fun stopTracking() {
        stopLocationTracking()
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (locationCallback != null || !hasLocationPermission()) {
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS
        ).setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    processLocation(location)
                }
            }
        }

        locationCallback = callback

        try {
            locationClient.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            stopTracking()
        }
    }

    private fun stopLocationTracking() {
        val callback = locationCallback ?: return
        locationClient.removeLocationUpdates(callback)
        locationCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(onLocation: (Location) -> Unit) {
        if (!hasLocationPermission()) {
            return
        }

        val tokenSource = CancellationTokenSource()
        try {
            locationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    onLocation(location)
                }
            }
        } catch (_: SecurityException) {
            stopTracking()
        }
    }

    private fun processLocation(location: Location) {
        val now = System.currentTimeMillis()
        val observedZone = detectCurrentZone(location)
        val previousState = loadTripState()

        val step = stateMachine.next(previousState, observedZone, now)
        saveTripState(step.state)
        prefs.edit().putLong(KEY_LAST_LOCATION_TIME_MS, now).apply()

        when (step.event) {
            TripEvent.JOURNEY_STARTED -> {
                resetJourneyTracking()
                updateJourneySpeed(location)
            }

            TripEvent.JOURNEY_CANCELLED -> {
                resetJourneyTracking()
            }

            TripEvent.TRIP_HOME_TO_UNIVERSITY,
            TripEvent.TRIP_UNIVERSITY_TO_HOME -> {
                updateJourneySpeed(location)
                processCompletedTrip(step.event, now)
                resetJourneyTracking()
            }

            TripEvent.NONE -> {
                if (step.state.journeyOriginZone != null) {
                    updateJourneySpeed(location)
                }
            }
        }
    }

    private fun processCompletedTrip(event: TripEvent, now: Long) {
        if (!canRecordAutoTransition()) {
            return
        }

        if (!isLikelyWalkingJourney()) {
            showRecordNotification(getString(R.string.auto_skipped_not_walking), null)
            return
        }

        val tripType = when (event) {
            TripEvent.TRIP_HOME_TO_UNIVERSITY -> TRIP_HOME_TO_UNIVERSITY
            TripEvent.TRIP_UNIVERSITY_TO_HOME -> TRIP_UNIVERSITY_TO_HOME
            else -> return
        }

        incrementCount(tripType, SOURCE_AUTO)
        markAutoTransitionRecorded(now)

        val recordId = now
        saveLastAutoRecord(recordId, todayKey(), tripType)

        val successMessage = if (tripType == TRIP_HOME_TO_UNIVERSITY) {
            getString(R.string.auto_recorded_home_to_uni)
        } else {
            getString(R.string.auto_recorded_uni_to_home)
        }

        showRecordNotification(successMessage, recordId)
    }

    private fun loadTripState(): TripState {
        val stableZone = prefs.getString(KEY_LAST_KNOWN_ZONE, ZONE_UNKNOWN) ?: ZONE_UNKNOWN
        val origin = prefs.getString(KEY_JOURNEY_ORIGIN_ZONE, null)
        val candidate = prefs.getString(KEY_ARRIVAL_CANDIDATE_ZONE, null)
        val candidateSince = prefs.getLong(KEY_ARRIVAL_CANDIDATE_START_MS, 0L)
        val candidateSamples = prefs.getInt(KEY_ARRIVAL_CANDIDATE_SAMPLE_COUNT, 0)

        return TripState(
            stableZone = stableZone,
            journeyOriginZone = origin,
            arrivalCandidateZone = candidate,
            arrivalCandidateSinceMs = candidateSince,
            arrivalCandidateSamples = candidateSamples
        )
    }

    private fun saveTripState(state: TripState) {
        prefs.edit()
            .putString(KEY_LAST_KNOWN_ZONE, state.stableZone)
            .putString(KEY_JOURNEY_ORIGIN_ZONE, state.journeyOriginZone)
            .putString(KEY_ARRIVAL_CANDIDATE_ZONE, state.arrivalCandidateZone)
            .putLong(KEY_ARRIVAL_CANDIDATE_START_MS, state.arrivalCandidateSinceMs)
            .putInt(KEY_ARRIVAL_CANDIDATE_SAMPLE_COUNT, state.arrivalCandidateSamples)
            .apply()
    }

    private fun canRecordAutoTransition(): Boolean {
        val now = System.currentTimeMillis()
        val lastRecorded = prefs.getLong(KEY_LAST_AUTO_RECORD_TIME_MS, 0L)
        return now - lastRecorded >= AUTO_RECORD_COOLDOWN_MS
    }

    private fun markAutoTransitionRecorded(now: Long) {
        prefs.edit().putLong(KEY_LAST_AUTO_RECORD_TIME_MS, now).apply()
    }

    private fun isLikelyWalkingJourney(): Boolean {
        val movingSampleCount = prefs.getInt(KEY_JOURNEY_MOVING_SAMPLE_COUNT, 0)
        val highSpeedSampleCount = prefs.getInt(KEY_JOURNEY_HIGH_SPEED_SAMPLE_COUNT, 0)

        return movingSampleCount >= MIN_MOVING_SAMPLE_COUNT &&
            highSpeedSampleCount < NON_WALKING_SPEED_SAMPLE_THRESHOLD
    }

    private fun updateJourneySpeed(location: Location) {
        val sampleSpeed = estimateCurrentSpeed(location)
        if (sampleSpeed != null && sampleSpeed.isFinite() && sampleSpeed >= 0f) {
            val editor = prefs.edit()

            val currentMax = prefs.getFloat(KEY_JOURNEY_MAX_SPEED_MPS, 0f)
            if (sampleSpeed > currentMax) {
                editor.putFloat(KEY_JOURNEY_MAX_SPEED_MPS, sampleSpeed)
            }

            if (sampleSpeed >= MOVING_MIN_SPEED_MPS) {
                val movingCount = prefs.getInt(KEY_JOURNEY_MOVING_SAMPLE_COUNT, 0) + 1
                editor.putInt(KEY_JOURNEY_MOVING_SAMPLE_COUNT, movingCount)
            }

            if (sampleSpeed > WALKING_MAX_SPEED_MPS) {
                val highSpeedCount = prefs.getInt(KEY_JOURNEY_HIGH_SPEED_SAMPLE_COUNT, 0) + 1
                editor.putInt(KEY_JOURNEY_HIGH_SPEED_SAMPLE_COUNT, highSpeedCount)
            }

            editor.apply()
        }

        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_LAST_SAMPLE_LAT, location.latitude.toString())
            .putString(KEY_LAST_SAMPLE_LNG, location.longitude.toString())
            .putLong(KEY_LAST_SAMPLE_TIME_MS, now)
            .apply()
    }

    private fun estimateCurrentSpeed(location: Location): Float? {
        if (location.hasSpeed()) {
            val speed = location.speed
            if (speed.isFinite() && speed >= 0f) {
                return speed
            }
        }

        val previousPoint = getSavedPoint(KEY_LAST_SAMPLE_LAT, KEY_LAST_SAMPLE_LNG) ?: return null
        val previousTime = prefs.getLong(KEY_LAST_SAMPLE_TIME_MS, 0L)
        if (previousTime <= 0L) {
            return null
        }

        val now = System.currentTimeMillis()
        val deltaMs = now - previousTime
        if (deltaMs <= 0L) {
            return null
        }

        val result = FloatArray(1)
        Location.distanceBetween(
            previousPoint.first,
            previousPoint.second,
            location.latitude,
            location.longitude,
            result
        )

        val seconds = deltaMs / 1000f
        if (seconds <= 0f) {
            return null
        }

        val speed = result[0] / seconds
        return if (speed.isFinite() && speed >= 0f) speed else null
    }

    private fun resetJourneyTracking() {
        prefs.edit()
            .remove(KEY_LAST_SAMPLE_LAT)
            .remove(KEY_LAST_SAMPLE_LNG)
            .remove(KEY_LAST_SAMPLE_TIME_MS)
            .putFloat(KEY_JOURNEY_MAX_SPEED_MPS, 0f)
            .putInt(KEY_JOURNEY_MOVING_SAMPLE_COUNT, 0)
            .putInt(KEY_JOURNEY_HIGH_SPEED_SAMPLE_COUNT, 0)
            .apply()
    }

    private fun detectCurrentZone(location: Location): String {
        val home = getSavedPoint(KEY_HOME_LAT, KEY_HOME_LNG)
        val university = getSavedPoint(KEY_UNIVERSITY_LAT, KEY_UNIVERSITY_LNG)

        val homeDistance = home?.let { distanceMeters(location, it.first, it.second) } ?: Float.MAX_VALUE
        val universityDistance =
            university?.let { distanceMeters(location, it.first, it.second) } ?: Float.MAX_VALUE

        val nearestDistance = minOf(homeDistance, universityDistance)
        if (nearestDistance > ZONE_RADIUS_METERS) {
            return ZONE_UNKNOWN
        }

        return if (homeDistance <= universityDistance) ZONE_HOME else ZONE_UNIVERSITY
    }

    private fun distanceMeters(location: Location, latitude: Double, longitude: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            latitude,
            longitude,
            result
        )
        return result[0]
    }

    private fun hasHomeAndUniversity(): Boolean {
        return getSavedPoint(KEY_HOME_LAT, KEY_HOME_LNG) != null &&
            getSavedPoint(KEY_UNIVERSITY_LAT, KEY_UNIVERSITY_LNG) != null
    }

    private fun getSavedPoint(latKey: String, lngKey: String): Pair<Double, Double>? {
        val latitude = prefs.getString(latKey, null)?.toDoubleOrNull()
        val longitude = prefs.getString(lngKey, null)?.toDoubleOrNull()
        return if (latitude != null && longitude != null) Pair(latitude, longitude) else null
    }

    private fun incrementCount(type: String, source: String) {
        val today = todayKey()
        val todayTypeKey = "${today}_$type"
        val todaySourceKey = "${today}_${type}_$source"

        val totalTypeKey = "total_$type"
        val totalSourceKey = "total_${type}_$source"

        prefs.edit()
            .putInt(todayTypeKey, prefs.getInt(todayTypeKey, 0) + 1)
            .putInt(todaySourceKey, prefs.getInt(todaySourceKey, 0) + 1)
            .putInt(totalTypeKey, prefs.getInt(totalTypeKey, 0) + 1)
            .putInt(totalSourceKey, prefs.getInt(totalSourceKey, 0) + 1)
            .apply()
    }

    private fun saveLastAutoRecord(recordId: Long, date: String, type: String) {
        prefs.edit()
            .putLong(KEY_LAST_AUTO_RECORD_ID, recordId)
            .putString(KEY_LAST_AUTO_RECORD_DATE, date)
            .putString(KEY_LAST_AUTO_RECORD_TYPE, type)
            .putBoolean(KEY_LAST_AUTO_RECORD_UNDONE, false)
            .apply()
    }

    private fun undoLastAutoRecord(recordId: Long) {
        if (recordId <= 0L) {
            return
        }

        val lastId = prefs.getLong(KEY_LAST_AUTO_RECORD_ID, -1L)
        val alreadyUndone = prefs.getBoolean(KEY_LAST_AUTO_RECORD_UNDONE, false)
        if (lastId != recordId || alreadyUndone) {
            return
        }

        val date = prefs.getString(KEY_LAST_AUTO_RECORD_DATE, null) ?: return
        val type = prefs.getString(KEY_LAST_AUTO_RECORD_TYPE, null) ?: return

        if (!decrementAutoRecord(date, type)) {
            return
        }

        prefs.edit().putBoolean(KEY_LAST_AUTO_RECORD_UNDONE, true).apply()
        showRecordNotification(getString(R.string.auto_record_undo_done), null)
    }

    private fun decrementAutoRecord(date: String, type: String): Boolean {
        val todayTypeKey = "${date}_$type"
        val todaySourceKey = "${date}_${type}_$SOURCE_AUTO"
        val totalTypeKey = "total_$type"
        val totalSourceKey = "total_${type}_$SOURCE_AUTO"

        val editor = prefs.edit()
        var changed = false

        changed = decrementIfPositive(todayTypeKey, editor) || changed
        changed = decrementIfPositive(todaySourceKey, editor) || changed
        changed = decrementIfPositive(totalTypeKey, editor) || changed
        changed = decrementIfPositive(totalSourceKey, editor) || changed

        if (changed) {
            editor.apply()
        }

        return changed
    }

    private fun decrementIfPositive(key: String, editor: SharedPreferences.Editor): Boolean {
        val current = prefs.getInt(key, 0)
        if (current <= 0) {
            return false
        }

        editor.putInt(key, current - 1)
        return true
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WalkTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.tracking_notification_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun showRecordNotification(message: String, recordId: Long?) {
        val builder = NotificationCompat.Builder(this, RECORD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.auto_record_notification_title))
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (recordId != null) {
            val undoIntent = Intent(this, WalkTrackingService::class.java).apply {
                action = ACTION_UNDO_LAST_RECORD
                putExtra(EXTRA_RECORD_ID, recordId)
            }
            val undoPendingIntent = PendingIntent.getService(
                this,
                12,
                undoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                android.R.drawable.ic_menu_revert,
                getString(R.string.auto_record_undo_action),
                undoPendingIntent
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(RECORD_NOTIFICATION_ID, builder.build())
        }
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val trackingChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            getString(R.string.tracking_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        val recordChannel = NotificationChannel(
            RECORD_CHANNEL_ID,
            getString(R.string.record_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        manager.createNotificationChannel(trackingChannel)
        manager.createNotificationChannel(recordChannel)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun todayKey(): String {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
}
