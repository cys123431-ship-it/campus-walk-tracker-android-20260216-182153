# Simple Transition Auto Record Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 집↔학교 전이 완료 시 보행 판정 없이 1회 자동기록되도록 동작을 수정한다.

**Architecture:** 보행 여부 필터를 제거하되 기존 상태머신(출발-이탈-도착 확인)과 중복 방지 쿨다운은 유지한다. 자동기록 조건은 순수 Kotlin 정책 함수로 분리해 단위 테스트로 회귀를 막고, 서비스는 해당 정책 결과로만 기록을 수행한다.

**Tech Stack:** Kotlin, Android Foreground Service, SharedPreferences, JUnit4, Gradle.

---

### Task 1: 자동기록 정책 테스트 추가 (RED)

**Files:**
- Create: `app/src/test/java/com/example/campuswalktracker/AutoRecordPolicyTest.kt`
- Create: `app/src/main/java/com/example/campuswalktracker/AutoRecordPolicy.kt`

**Step 1: Write the failing test**

```kotlin
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
```

**Step 2: Run test to verify it fails**
- Run: `./gradlew testDebugUnitTest --tests "com.example.campuswalktracker.AutoRecordPolicyTest"`
- Expected: FAIL because `resolveAutoTripType` and `AutoTripType` do not exist.

**Step 3: Write minimal implementation**

```kotlin
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
```

**Step 4: Run test to verify it passes**
- Run: `./gradlew testDebugUnitTest --tests "com.example.campuswalktracker.AutoRecordPolicyTest"`
- Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/campuswalktracker/AutoRecordPolicy.kt app/src/test/java/com/example/campuswalktracker/AutoRecordPolicyTest.kt
git commit -m "test: add auto record policy regression coverage"
```

### Task 2: 서비스 자동기록 로직 단순화 (GREEN)

**Files:**
- Modify: `app/src/main/java/com/example/campuswalktracker/WalkTrackingService.kt`

**Step 1: Write failing integration expectation via existing/new tests**
- Extend policy test if needed for mapping consistency.

**Step 2: Run tests and verify fail for missing integration assumptions**
- Run: `./gradlew testDebugUnitTest --tests "com.example.campuswalktracker.AutoRecordPolicyTest"`
- Expected: FAIL if new assertion references service mapping not yet updated.

**Step 3: Write minimal implementation**
- `processCompletedTrip`에서 `isLikelyWalkingJourney()` 조건과 스킵 알림을 제거.
- `resolveAutoTripType(event, canRecordAutoTransition())`로 기록 여부를 결정.
- 정책 결과를 기존 문자열 키(`home_to_uni`, `uni_to_home`)로 매핑 후 카운트/알림/undo 저장 유지.

**Step 4: Run tests to verify pass**
- Run: `./gradlew testDebugUnitTest`
- Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/example/campuswalktracker/WalkTrackingService.kt
git commit -m "feat: record trips on zone transitions without walking filter"
```

### Task 3: 검증, 푸시, 릴리즈 배포

**Files:**
- Modify: `docs/plans/2026-02-27-simple-transition-auto-record-implementation-plan.md` (if command adjustments needed)

**Step 1: Run full verification**
- Run: `./gradlew testDebugUnitTest lintDebug assembleRelease`
- Expected: PASS (exit code 0).

**Step 2: Commit remaining changes**

```bash
git add -A
git commit -m "feat: simplify auto commute recording to transition-only"
```

**Step 3: Push branch**

```bash
git push origin main
```

**Step 4: Create release tag and push**

```bash
git tag v1.0.1
git push origin v1.0.1
```

**Step 5: Verify GitHub release workflow**
- Check Actions run for tag `v1.0.1`.
- Confirm release includes `app-release-local.apk` and unsigned APK artifact.