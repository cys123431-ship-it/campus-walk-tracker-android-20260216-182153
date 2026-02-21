# Foreground Auto Tracking Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move commute auto-tracking to a foreground service with destination confirmation and undo notification.

**Architecture:** Keep Activity as control panel while a foreground service owns location updates and trip detection state. Persist all state in SharedPreferences so behavior survives Activity recreation. Use explicit state transitions to prevent false positives from same-zone movement.

**Tech Stack:** Kotlin, AndroidX AppCompat, Google Play Services Location, NotificationCompat, SharedPreferences.

---

### Task 1: Add failing tests for trip transition behavior

**Files:**
- Create: `app/src/test/java/com/example/campuswalktracker/TripStateMachineTest.kt`
- Create: `app/src/main/java/com/example/campuswalktracker/TripStateMachine.kt`

**Step 1: Write the failing test**
- Add tests for:
  - `home -> unknown -> university(confirmed) => HOME_TO_UNI`
  - `university internal movement => no trip`
  - `undo applies once`

**Step 2: Run test to verify it fails**
- Run: `./gradlew testDebugUnitTest --tests "com.example.campuswalktracker.TripStateMachineTest"`
- Expected: FAIL because `TripStateMachine` does not exist.

**Step 3: Write minimal implementation**
- Create pure Kotlin state machine to satisfy tests.

**Step 4: Run test to verify it passes**
- Run same command.
- Expected: PASS.

**Step 5: Commit**
- `git add app/src/test/java/com/example/campuswalktracker/TripStateMachineTest.kt app/src/main/java/com/example/campuswalktracker/TripStateMachine.kt`
- `git commit -m "test: add trip state machine tests and minimal logic"`

### Task 2: Add foreground tracking service integration

**Files:**
- Create: `app/src/main/java/com/example/campuswalktracker/WalkTrackingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Write failing service-related tests (or logic-level tests)**
- Add tests for service command handling through extracted helper functions.

**Step 2: Run tests and verify failing state**
- `./gradlew testDebugUnitTest`

**Step 3: Implement minimal service**
- Add START/STOP/UNDO intent actions.
- Start foreground notification channel.
- Request location updates and feed state machine.

**Step 4: Run tests and verify pass**
- `./gradlew testDebugUnitTest`

**Step 5: Commit**
- `git add app/src/main/java/com/example/campuswalktracker/WalkTrackingService.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml`
- `git commit -m "feat: add foreground location tracking service"`

### Task 3: Wire MainActivity to service and status updates

**Files:**
- Modify: `app/src/main/java/com/example/campuswalktracker/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Write failing tests for key helpers (if extracted) and assertions for counter updates**

**Step 2: Run tests and verify fail**
- `./gradlew testDebugUnitTest`

**Step 3: Implement minimal wiring**
- Replace in-Activity continuous tracking with service start/stop commands.
- Keep manual controls for setting home/university.
- Keep summaries and statuses in Activity.

**Step 4: Run tests and verify pass**
- `./gradlew testDebugUnitTest`

**Step 5: Commit**
- `git add app/src/main/java/com/example/campuswalktracker/MainActivity.kt app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml`
- `git commit -m "feat: connect activity controls to foreground tracking"`

### Task 4: Verification and release build

**Files:**
- Modify: `app/build.gradle.kts` (if test dependencies needed)

**Step 1: Run full verification**
- `./gradlew testDebugUnitTest lintDebug assembleRelease`

**Step 2: Manual scenario checks**
- Home departure, background app usage, school arrival record, undo action.

**Step 3: Commit final changes**
- `git add -A`
- `git commit -m "feat: stabilize background auto commute tracking with undo confirmation"`
