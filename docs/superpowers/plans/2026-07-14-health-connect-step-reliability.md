# Health Connect Step Reliability Implementation Plan

**Goal:** Make direct and paced step writes verifiable, resumable, and explicit about the Health Connect/Google Fit boundary.

**Architecture:** Health Connect remains the sole production writer. Each write carries a stable client record ID and is verified by reading that exact record back. A persisted walking session coordinates pending chunks, retries, service recovery, and UI state.

**Tech Stack:** Kotlin, compileSdk 36 / targetSdk 35, Jetpack Compose, Health Connect Client 1.1.0, coroutines, JUnit/Truth.

## Global Constraints

- Health Connect is the success boundary; Google Fit display is diagnostic only.
- Simulated steps use `Metadata.manualEntry`; never claim sensor-derived activity.
- Android 9+ is the supported floor; compileSdk is 36, targetSdk remains 35, and manual device validation targets Android 14/15.
- Paced sessions are capped at 5 hours and must handle Android 15 `dataSync` timeout.
- Pending chunks are idempotent by `clientRecordId`; never advance confirmed progress before verification.

## Tasks

### Task 1: Health Connect write contract and direct-write path
   - Modify `data/StepWriter.kt`, `data/HealthConnectStepWriter.kt`, `domain/StepPlanner.kt`, and direct-write tests.
   - Introduce request/result types carrying client ID and exact-record verification.
   - Add failing tests for exact raw read-back, failed writes not advancing state, and direct 3000-step interval semantics before implementation.
   - Keep aggregate read-back as a diagnostic field and filter exact records by app origin/client ID.

### Task 2: Walking session persistence and service execution
   - Modify `domain/WalkingSessionController.kt`, `service/WalkingSessionService.kt`; add `data/WalkingSessionStore.kt` and focused tests.
   - Persist pending/confirmed chunks, use a 60-second cadence, retry transient provider failures for at most three total attempts (two retries), and restore without backfilling downtime.
   - Add `onTimeout()` handling and a five-hour plan guard.

### Task 3: Application integration and platform configuration
   - Modify `MainActivity.kt`, `app/build.gradle.kts`, `AndroidManifest.xml`, and user-facing strings/docs.
   - Remove unused Google Sign-In dependency/UI, surface exact Health Connect status, restore persisted sessions, and update Health Connect dependency/minSdk.

### Task 4: Integration review and verification
   - Run unit tests, instrumentation-capable compilation, lint, debug/release builds, and inspect the complete diff.
   - Verify direct 3000-step and two-chunk paced flows with a fake writer; document the remaining Android 14/15 manual test procedure.
