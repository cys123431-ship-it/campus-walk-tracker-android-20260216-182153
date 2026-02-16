package com.example.campuswalktracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "walk_tracker"

        private const val TRIP_HOME_TO_UNIVERSITY = "home_to_uni"
        private const val TRIP_UNIVERSITY_TO_HOME = "uni_to_home"

        private const val SOURCE_MANUAL = "manual"
        private const val SOURCE_AUTO = "auto"

        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LNG = "home_lng"
        private const val KEY_UNIVERSITY_LAT = "university_lat"
        private const val KEY_UNIVERSITY_LNG = "university_lng"

        private const val KEY_AUTO_ENABLED = "auto_enabled"
        private const val KEY_LAST_KNOWN_ZONE = "last_known_zone"
        private const val KEY_LAST_OBSERVED_ZONE = "last_observed_zone"
        private const val KEY_LAST_AUTO_RECORD_TIME_MS = "last_auto_record_time_ms"
        private const val KEY_LAST_LOCATION_TIME_MS = "last_location_time_ms"

        private const val KEY_LAST_SAMPLE_LAT = "last_sample_lat"
        private const val KEY_LAST_SAMPLE_LNG = "last_sample_lng"
        private const val KEY_LAST_SAMPLE_TIME_MS = "last_sample_time_ms"
        private const val KEY_JOURNEY_MAX_SPEED_MPS = "journey_max_speed_mps"
        private const val KEY_JOURNEY_MOVING_SAMPLE_COUNT = "journey_moving_sample_count"
        private const val KEY_JOURNEY_HIGH_SPEED_SAMPLE_COUNT = "journey_high_speed_sample_count"

        private const val ZONE_HOME = "home"
        private const val ZONE_UNIVERSITY = "university"
        private const val ZONE_UNKNOWN = "unknown"

        private const val ZONE_RADIUS_METERS = 200f
        private const val AUTO_RECORD_COOLDOWN_MINUTES = 30
        private const val AUTO_RECORD_COOLDOWN_MS = AUTO_RECORD_COOLDOWN_MINUTES * 60 * 1000L

        private const val LOCATION_UPDATE_INTERVAL_MS = 60_000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 30_000L

        private const val WALKING_MAX_SPEED_MPS = 3.2f
        private const val MOVING_MIN_SPEED_MPS = 0.5f
        private const val MIN_MOVING_SAMPLE_COUNT = 2
        private const val NON_WALKING_SPEED_SAMPLE_THRESHOLD = 2

        private val DAILY_RECORD_KEY_REGEX =
            Regex("""\d{4}-\d{2}-\d{2}_(home_to_uni|uni_to_home)(_(manual|auto))?""")
        private val TOTAL_RECORD_KEY_REGEX =
            Regex("""total_(home_to_uni|uni_to_home)(_(manual|auto))?""")
    }

    private enum class PermissionRequestReason {
        AUTO_TRACKING,
        LOCATION_ACTION
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var todaySummaryText: TextView
    private lateinit var totalSummaryText: TextView
    private lateinit var autoStatusText: TextView
    private lateinit var homeLocationText: TextView
    private lateinit var universityLocationText: TextView
    private lateinit var autoTrackingSwitch: SwitchCompat

    private lateinit var locationClient: com.google.android.gms.location.FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingPermissionReason: PermissionRequestReason? = null
    private var pendingBackgroundPermissionAction: (() -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingPermissionAction
        val reason = pendingPermissionReason
        pendingPermissionAction = null
        pendingPermissionReason = null

        if (granted) {
            if (reason == PermissionRequestReason.AUTO_TRACKING) {
                ensureBackgroundLocationPermissionIfNeeded {
                    action?.invoke()
                }
            } else {
                action?.invoke()
            }
        } else {
            if (reason == PermissionRequestReason.AUTO_TRACKING) {
                disableAutoTracking()
            }
            showToast(getString(R.string.location_permission_required))
            refreshAutoStatus()
        }
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingBackgroundPermissionAction
        pendingBackgroundPermissionAction = null

        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            action?.invoke()
        } else {
            showToast(getString(R.string.background_location_permission_limited))
            action?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        locationClient = LocationServices.getFusedLocationProviderClient(this)

        todaySummaryText = findViewById(R.id.todaySummaryText)
        totalSummaryText = findViewById(R.id.totalSummaryText)
        autoStatusText = findViewById(R.id.autoStatusText)
        homeLocationText = findViewById(R.id.homeLocationText)
        universityLocationText = findViewById(R.id.universityLocationText)
        autoTrackingSwitch = findViewById(R.id.autoTrackingSwitch)

        findViewById<Button>(R.id.homeToUniButton).setOnClickListener {
            incrementCount(TRIP_HOME_TO_UNIVERSITY, SOURCE_MANUAL)
        }

        findViewById<Button>(R.id.uniToHomeButton).setOnClickListener {
            incrementCount(TRIP_UNIVERSITY_TO_HOME, SOURCE_MANUAL)
        }

        findViewById<Button>(R.id.resetTodayButton).setOnClickListener {
            resetToday()
        }

        findViewById<Button>(R.id.resetAllButton).setOnClickListener {
            confirmResetAllRecords()
        }

        findViewById<Button>(R.id.setHomeButton).setOnClickListener {
            withLocationPermission(PermissionRequestReason.LOCATION_ACTION) {
                requestCurrentLocation { location ->
                    savePoint(KEY_HOME_LAT, KEY_HOME_LNG, location)
                    refreshLocationLabels()
                    refreshAutoStatus(detectCurrentZone(location))
                    showToast(getString(R.string.saved_home_location))
                }
            }
        }

        findViewById<Button>(R.id.setUniversityButton).setOnClickListener {
            withLocationPermission(PermissionRequestReason.LOCATION_ACTION) {
                requestCurrentLocation { location ->
                    savePoint(KEY_UNIVERSITY_LAT, KEY_UNIVERSITY_LNG, location)
                    refreshLocationLabels()
                    refreshAutoStatus(detectCurrentZone(location))
                    showToast(getString(R.string.saved_university_location))
                }
            }
        }

        findViewById<Button>(R.id.detectNowButton).setOnClickListener {
            withLocationPermission(PermissionRequestReason.LOCATION_ACTION) {
                requestCurrentLocation { location ->
                    handleLocation(location, manualCheck = true)
                }
            }
        }

        autoTrackingSwitch.isChecked = prefs.getBoolean(KEY_AUTO_ENABLED, false)
        autoTrackingSwitch.setOnCheckedChangeListener { _, isChecked ->
            onAutoTrackingToggled(isChecked)
        }

        refreshSummary()
        refreshLocationLabels()
        refreshAutoStatus()
    }

    override fun onResume() {
        super.onResume()

        if (prefs.getBoolean(KEY_AUTO_ENABLED, false)) {
            if (!hasHomeAndUniversity()) {
                disableAutoTracking()
                refreshAutoStatus()
                return
            }

            withLocationPermission(PermissionRequestReason.AUTO_TRACKING) {
                startLocationTracking()
                requestCurrentLocation { location ->
                    handleLocation(location, manualCheck = false)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        stopLocationTracking()
        super.onDestroy()
    }

    private fun onAutoTrackingToggled(isChecked: Boolean) {
        if (isChecked) {
            if (!hasHomeAndUniversity()) {
                showToast(getString(R.string.need_home_and_university_first))
                autoTrackingSwitch.isChecked = false
                return
            }

            prefs.edit().putBoolean(KEY_AUTO_ENABLED, true).apply()

            withLocationPermission(PermissionRequestReason.AUTO_TRACKING) {
                startLocationTracking()
                requestCurrentLocation { location ->
                    handleLocation(location, manualCheck = false)
                }
            }
        } else {
            disableAutoTracking()
        }

        refreshAutoStatus()
    }

    private fun disableAutoTracking() {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, false).apply()
        if (::autoTrackingSwitch.isInitialized && autoTrackingSwitch.isChecked) {
            autoTrackingSwitch.isChecked = false
        }
        stopLocationTracking()
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
                    handleLocation(location, manualCheck = false)
                }
            }
        }

        locationCallback = callback

        try {
            locationClient.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            locationCallback = null
            disableAutoTracking()
            showToast(getString(R.string.location_permission_required))
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
            showToast(getString(R.string.location_permission_required))
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
                } else {
                    showToast(getString(R.string.location_not_available))
                }
            }.addOnFailureListener {
                showToast(getString(R.string.location_error))
            }
        } catch (_: SecurityException) {
            showToast(getString(R.string.location_permission_required))
        }
    }

    private fun handleLocation(location: Location, manualCheck: Boolean) {
        val previousKnownZone = prefs.getString(KEY_LAST_KNOWN_ZONE, ZONE_UNKNOWN) ?: ZONE_UNKNOWN
        val currentZone = detectCurrentZone(location)
        val autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false)

        updateJourneySpeed(location)
        updateObservedZone(currentZone)

        if (autoEnabled && previousKnownZone != currentZone) {
            when {
                previousKnownZone == ZONE_HOME && currentZone == ZONE_UNIVERSITY -> {
                    processAutoTrip(TRIP_HOME_TO_UNIVERSITY, R.string.auto_recorded_home_to_uni)
                }

                previousKnownZone == ZONE_UNIVERSITY && currentZone == ZONE_HOME -> {
                    processAutoTrip(TRIP_UNIVERSITY_TO_HOME, R.string.auto_recorded_uni_to_home)
                }
            }
        }

        if (currentZone != ZONE_UNKNOWN) {
            prefs.edit().putString(KEY_LAST_KNOWN_ZONE, currentZone).apply()
        }

        prefs.edit().putLong(KEY_LAST_LOCATION_TIME_MS, System.currentTimeMillis()).apply()
        refreshAutoStatus(currentZone)

        if (manualCheck) {
            showToast(getString(R.string.current_location_checked))
        }
    }

    private fun processAutoTrip(type: String, successMessageRes: Int) {
        if (!canRecordAutoTransition()) {
            return
        }

        if (!isLikelyWalkingJourney()) {
            showToast(getString(R.string.auto_skipped_not_walking))
            resetJourneyTracking()
            return
        }

        incrementCount(type, SOURCE_AUTO)
        markAutoTransitionRecorded()
        showToast(getString(successMessageRes))
        resetJourneyTracking()
    }

    private fun updateObservedZone(currentZone: String) {
        val lastObservedZone = prefs.getString(KEY_LAST_OBSERVED_ZONE, ZONE_UNKNOWN) ?: ZONE_UNKNOWN

        if (lastObservedZone != currentZone) {
            if (lastObservedZone != ZONE_UNKNOWN && currentZone == ZONE_UNKNOWN) {
                resetJourneyTracking()
            }
            prefs.edit().putString(KEY_LAST_OBSERVED_ZONE, currentZone).apply()
        }
    }

    private fun canRecordAutoTransition(): Boolean {
        val now = System.currentTimeMillis()
        val lastRecorded = prefs.getLong(KEY_LAST_AUTO_RECORD_TIME_MS, 0L)
        return now - lastRecorded >= AUTO_RECORD_COOLDOWN_MS
    }

    private fun markAutoTransitionRecorded() {
        prefs.edit().putLong(KEY_LAST_AUTO_RECORD_TIME_MS, System.currentTimeMillis()).apply()
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

    private fun savePoint(latKey: String, lngKey: String, location: Location) {
        prefs.edit()
            .putString(latKey, location.latitude.toString())
            .putString(lngKey, location.longitude.toString())
            .apply()
    }

    private fun getSavedPoint(latKey: String, lngKey: String): Pair<Double, Double>? {
        val latitude = prefs.getString(latKey, null)?.toDoubleOrNull()
        val longitude = prefs.getString(lngKey, null)?.toDoubleOrNull()
        return if (latitude != null && longitude != null) Pair(latitude, longitude) else null
    }

    private fun refreshLocationLabels() {
        val homePoint = getSavedPoint(KEY_HOME_LAT, KEY_HOME_LNG)
        val universityPoint = getSavedPoint(KEY_UNIVERSITY_LAT, KEY_UNIVERSITY_LNG)

        homeLocationText.text = getString(
            R.string.home_location_format,
            pointToText(homePoint)
        )

        universityLocationText.text = getString(
            R.string.university_location_format,
            pointToText(universityPoint)
        )
    }

    private fun pointToText(point: Pair<Double, Double>?): String {
        return if (point == null) {
            getString(R.string.location_not_set)
        } else {
            String.format(Locale.US, "%.6f, %.6f", point.first, point.second)
        }
    }

    private fun refreshAutoStatus(detectedZone: String? = null) {
        if (!::prefs.isInitialized || !::autoStatusText.isInitialized) {
            return
        }

        val autoStateText = if (prefs.getBoolean(KEY_AUTO_ENABLED, false)) {
            getString(R.string.auto_on)
        } else {
            getString(R.string.auto_off)
        }

        val zone = detectedZone ?: prefs.getString(KEY_LAST_KNOWN_ZONE, ZONE_UNKNOWN).orEmpty()
        val zoneText = when (zone) {
            ZONE_HOME -> getString(R.string.zone_home)
            ZONE_UNIVERSITY -> getString(R.string.zone_university)
            else -> getString(R.string.zone_unknown)
        }

        autoStatusText.text = getString(
            R.string.auto_status_format,
            autoStateText,
            zoneText,
            ZONE_RADIUS_METERS.toInt(),
            AUTO_RECORD_COOLDOWN_MINUTES
        )
    }

    private fun incrementCount(type: String, source: String) {
        val today = todayKey()
        val todayTypeKey = "${today}_$type"
        val todaySourceKey = "${today}_${type}_$source"

        val totalTypeKey = "total_$type"
        val totalSourceKey = "total_${type}_$source"

        val nextTodayTypeCount = prefs.getInt(todayTypeKey, 0) + 1
        val nextTodaySourceCount = prefs.getInt(todaySourceKey, 0) + 1
        val nextTotalTypeCount = prefs.getInt(totalTypeKey, 0) + 1
        val nextTotalSourceCount = prefs.getInt(totalSourceKey, 0) + 1

        prefs.edit()
            .putInt(todayTypeKey, nextTodayTypeCount)
            .putInt(todaySourceKey, nextTodaySourceCount)
            .putInt(totalTypeKey, nextTotalTypeCount)
            .putInt(totalSourceKey, nextTotalSourceCount)
            .apply()

        refreshSummary()
    }

    private fun resetToday() {
        val today = todayKey()
        prefs.edit()
            .remove("${today}_$TRIP_HOME_TO_UNIVERSITY")
            .remove("${today}_${TRIP_HOME_TO_UNIVERSITY}_$SOURCE_MANUAL")
            .remove("${today}_${TRIP_HOME_TO_UNIVERSITY}_$SOURCE_AUTO")
            .remove("${today}_$TRIP_UNIVERSITY_TO_HOME")
            .remove("${today}_${TRIP_UNIVERSITY_TO_HOME}_$SOURCE_MANUAL")
            .remove("${today}_${TRIP_UNIVERSITY_TO_HOME}_$SOURCE_AUTO")
            .apply()
        refreshSummary()
    }

    private fun confirmResetAllRecords() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_all_confirm_title))
            .setMessage(getString(R.string.reset_all_confirm_message))
            .setPositiveButton(getString(R.string.reset_confirm_positive)) { _, _ ->
                resetAllRecords()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetAllRecords() {
        val keysToRemove = prefs.all.keys.filter(::isRecordKey)
        val editor = prefs.edit()

        keysToRemove.forEach(editor::remove)

        editor.remove(KEY_LAST_AUTO_RECORD_TIME_MS)
            .remove(KEY_LAST_LOCATION_TIME_MS)
            .putString(KEY_LAST_KNOWN_ZONE, ZONE_UNKNOWN)
            .putString(KEY_LAST_OBSERVED_ZONE, ZONE_UNKNOWN)
            .apply()

        resetJourneyTracking()
        refreshSummary()
        refreshAutoStatus()
        showToast(getString(R.string.reset_all_done))
    }

    private fun isRecordKey(key: String): Boolean {
        return DAILY_RECORD_KEY_REGEX.matches(key) || TOTAL_RECORD_KEY_REGEX.matches(key)
    }

    private fun refreshSummary() {
        val today = todayKey()

        val todayHomeToUniversity = prefs.getInt("${today}_$TRIP_HOME_TO_UNIVERSITY", 0)
        val todayHomeToUniversityManual =
            prefs.getInt("${today}_${TRIP_HOME_TO_UNIVERSITY}_$SOURCE_MANUAL", 0)
        val todayHomeToUniversityAuto =
            prefs.getInt("${today}_${TRIP_HOME_TO_UNIVERSITY}_$SOURCE_AUTO", 0)

        val todayUniversityToHome = prefs.getInt("${today}_$TRIP_UNIVERSITY_TO_HOME", 0)
        val todayUniversityToHomeManual =
            prefs.getInt("${today}_${TRIP_UNIVERSITY_TO_HOME}_$SOURCE_MANUAL", 0)
        val todayUniversityToHomeAuto =
            prefs.getInt("${today}_${TRIP_UNIVERSITY_TO_HOME}_$SOURCE_AUTO", 0)

        val todayTotal = todayHomeToUniversity + todayUniversityToHome

        todaySummaryText.text = getString(
            R.string.today_summary_format,
            today,
            todayHomeToUniversity,
            todayHomeToUniversityManual,
            todayHomeToUniversityAuto,
            todayUniversityToHome,
            todayUniversityToHomeManual,
            todayUniversityToHomeAuto,
            todayTotal
        )

        val totalHomeToUniversity = prefs.getInt("total_$TRIP_HOME_TO_UNIVERSITY", 0)
        val totalHomeToUniversityManual =
            prefs.getInt("total_${TRIP_HOME_TO_UNIVERSITY}_$SOURCE_MANUAL", 0)
        val totalHomeToUniversityAuto =
            prefs.getInt("total_${TRIP_HOME_TO_UNIVERSITY}_$SOURCE_AUTO", 0)

        val totalUniversityToHome = prefs.getInt("total_$TRIP_UNIVERSITY_TO_HOME", 0)
        val totalUniversityToHomeManual =
            prefs.getInt("total_${TRIP_UNIVERSITY_TO_HOME}_$SOURCE_MANUAL", 0)
        val totalUniversityToHomeAuto =
            prefs.getInt("total_${TRIP_UNIVERSITY_TO_HOME}_$SOURCE_AUTO", 0)

        val total = totalHomeToUniversity + totalUniversityToHome

        totalSummaryText.text = getString(
            R.string.total_summary_format,
            totalHomeToUniversity,
            totalHomeToUniversityManual,
            totalHomeToUniversityAuto,
            totalUniversityToHome,
            totalUniversityToHomeManual,
            totalUniversityToHomeAuto,
            total
        )
    }

    private fun withLocationPermission(
        reason: PermissionRequestReason,
        action: () -> Unit
    ) {
        if (hasLocationPermission()) {
            if (reason == PermissionRequestReason.AUTO_TRACKING) {
                ensureBackgroundLocationPermissionIfNeeded(action)
            } else {
                action()
            }
            return
        }

        pendingPermissionAction = action
        pendingPermissionReason = reason
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun ensureBackgroundLocationPermissionIfNeeded(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundLocationPermission()) {
            action()
            return
        }

        pendingBackgroundPermissionAction = action
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun todayKey(): String {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
}
