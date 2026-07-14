# AI Usage Guide

This file is the operating guide for AI coding agents working in Fit Step Controller. It records the project facts that must remain true when an agent reviews, changes, or releases the app.

## Mission and boundaries

The app is a Health Connect test client for verified step records. AI changes must preserve transparent, user-controlled behavior:

- Health Connect is the only write target. Do not add Google Fit Fitness API calls or claim that a Google Fit dashboard total proves a successful write.
- Every write must use a stable client record ID, read back the exact app-origin record, and verify count plus start/end timestamps.
- Never describe a missing `StepsRecord` as proof that the user was not physically active. Use “empty step-record window” or equivalent wording.
- Do not add stealth, anti-detection, reward-system automation, background evasion, or functionality that bypasses Android permissions or force-stop behavior.
- Treat Health Connect records as user health data. Do not log raw records, package data, or identifiers unnecessarily, and do not place real user data in tests or prompts.

## Architecture map

| Area | Responsibility | AI change invariant |
| --- | --- | --- |
| `domain/StepPlan.kt` | Speed, stride, duration, cadence, and ETA math | Keep calculations deterministic and unit-testable. |
| `domain/StepAvailability.kt` | Raw interval union, empty windows, capacity, oldest-first allocation | Use half-open non-overlapping intervals and floor capacity. |
| `domain/WalkingSessionController.kt` | Durable Mode 1 state machine and dynamic speed accrual | Never backfill paused/process-downtime intervals; preserve pending IDs. |
| `data/HealthConnectStepWriter.kt` | Health Connect reads, writes, pagination, exact verification, Mode 2 batch | Use all-source raw reads for availability and app-origin reads for verification. |
| `service/WalkingSessionService.kt` | Foreground service, commands, notification, wake lock, restoration | Keep `START_STICKY`, `stopWithTask=false`, timeout handling, and release resources on every terminal path. |
| `MainActivity.kt` | Compose permission flow, Mode 1 controls, Mode 2 scan/backfill UI | Disable unsafe inputs while a session/batch is active and show provider errors honestly. |

## Mode 1 rules

- Valid speed is 3–12 km/h; valid stride is 0.35–1.4 m.
- A running speed update must account for elapsed time using the previous speed before applying the new speed.
- UI speed updates are live but throttled; target and stride are locked while running.
- Current speed, remaining duration, and estimated finish are derived from persisted state and must survive service recreation.
- The five-hour session cap remains enforced. Reject a new speed if the projected active duration would exceed the cap.
- Paused time and service downtime must never become synthetic step intervals.

## Mode 2 rules

- The availability range is the device-local current date from 12:00 through the scan instant. Before noon, there is no range.
- Read all accessible raw `StepsRecord` sources with pagination. Use a conservative union of intervals so duplicate sources cannot create an unsafe overlap.
- The theoretical reference uses 10 km/h and 0.35 m stride: approximately 7.9365 steps/sec.
- Re-scan before each allocation. Allocate oldest-first, keep intervals non-overlapping, and retain a deterministic `batchId:index` client record ID for retries.
- A batch may partially complete. Report written and remaining counts; never claim the full request succeeded unless every exact record was verified.
- Aggregate results are diagnostics and must not be used to decide whether an exact write succeeded.

## Safe implementation workflow

1. Read the relevant source and tests before changing behavior.
2. Add or update a pure domain test before changing provider/UI code when the behavior has arithmetic or interval rules.
3. Keep Health Connect calls behind the data layer and make provider failures visible to the UI/service.
4. Run the focused tests first, then the complete verification command:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
   ```

5. Run `git diff --check`, inspect the staged diff, and exclude unrelated files such as local `.ai-local/` state.
6. For release work, update `versionCode`, `versionName`, `RELEASE_NOTES.md`, and this documentation together. Build both APK variants before tagging.

## Review checklist for AI agents

- Does the change preserve raw-record verification and idempotent IDs?
- Does it distinguish all-source availability reads from app-origin exact verification reads?
- Are interval boundaries normalized to epoch milliseconds before comparing with Health Connect?
- Are pagination, timezone, noon boundary, overlap, pause, restart, and provider failure cases tested?
- Does the UI state match persisted service state after process recreation?
- Are Android foreground-service, battery, force-stop, and Health Connect permission limits documented rather than hidden?
- Are README, design docs, release notes, and APK version metadata consistent?
