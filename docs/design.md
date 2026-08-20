# Fit Step Controller Reliability Design

## Objective

Build an Android Health Connect step-entry app with two primary step-writing modes:

1. Paced walking at 3-12 km/h with a target step count and automatic stop.
2. Availability-aware backfill from empty windows in the device-local current day, capped to the most recent 12 hours by default with a full-day option.

Advanced direct entry remains available for exact end-at-now placement.

## Technical Choice

Health Connect is the only write target. Google Fit is treated as an optional consumer of Health Connect data; its displayed aggregate is not the app's write-success criterion. The deprecated Google Fit Fitness API is not used.

## Architecture

- `StepPlanner`: pure domain logic for speed, stride, duration, warning, and interval calculations.
- `StepWriter`: writes a stable client record ID and verifies the exact app-origin record.
- `HealthConnectStepWriter`: production implementation backed by `StepsRecord`.
- `StepAvailabilityPlanner`: pure interval union, empty-window, theoretical capacity, and oldest-first allocation logic.
- `WalkingSessionController`: durable state machine with pending chunks, retries, pause/resume, dynamic speed accrual, ETA, and a five-hour limit.
- `WalkingSessionService`: `dataSync` foreground service that writes verified 60-second chunks, remains active after the app task is removed, restores sticky sessions, and holds a bounded `PARTIAL_WAKE_LOCK` while running. It also handles Android 15 timeout.
- Compose UI: requests Health Connect permissions, restores persisted progress, exposes Mode 2 availability/capacity and backfill progress, and applies Mode 1 speed changes live.

## Verification

Unit tests cover exact record verification, idempotent retries, all-source pagination, interval union and capacity allocation, durable pending chunks, dynamic speed changes, ETA, pause/resume, failure states, automatic stop, aggregate diagnostics through `FakeStepWriter`, and the background wake-lock policy. The target is Android 9+, with manual Health Connect validation on Android 14/15. Android battery-management policies, user force-stop, and the Android 15 six-hour `dataSync` limit remain system-level constraints; paced sessions are capped at five hours.

## AI-assisted changes

Repository-specific AI development rules, architecture invariants, privacy boundaries, validation commands, and release checks are maintained in [AI_USAGE.md](../AI_USAGE.md).
