# Campus Walk Auto-Tracking Design

**Date:** 2026-02-21

## Goals
- User configures only home and university locations.
- User starts tracking once, then uses other apps during commute.
- Auto-record only valid `home -> university` and `university -> home` walking commutes.
- Ignore movement inside same zone.
- Auto-record first, then allow immediate undo confirmation.

## Architecture
- `MainActivity`: setup UI, permissions, status rendering, start/stop tracking commands.
- `WalkTrackingService` (foreground): persistent location tracking and commute detection while app is backgrounded.
- SharedPreferences store: configuration, counters, tracking state machine, last auto-record snapshot for undo.

## Detection Rules
- Zone radius: 150m.
- Arrival confirmation: destination zone must stay valid for about 1 minute with at least 2 consecutive samples.
- Valid trip requires state flow:
  1) stable at origin zone,
  2) leave origin zone (unknown),
  3) arrive and stay in opposite zone (confirmed),
  4) record trip.
- Movement inside one zone does not record.

## Post-Record Confirmation
- Trip is auto-recorded immediately once confirmed.
- Notification provides `Undo` action for last auto-record.
- Undo decrements both daily and total counters once.

## Error Handling
- Missing permissions: disable auto tracking and show clear status.
- Missing home/university points: auto tracking cannot start.
- Location unavailable: keep service alive and wait for next update.

## Verification
- Unit tests for trip state machine transitions and undo behavior.
- Manual test on device for background tracking during music/video usage.
