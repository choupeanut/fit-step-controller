# Task 1 implementation report: Health Connect write contract

## Scope

Implemented the Health Connect write contract and direct-write semantics from the Task 1 brief. `MainActivity.kt` and `WalkingSessionService.kt` were intentionally left unchanged for the integration/service tasks.

## Changes

- Added `StepWriteRequest` around the existing `StepWriteInterval`. It carries a non-blank stable `clientRecordId` and non-negative `clientRecordVersion`.
- Added `VerifiedStepWrite`, which exposes the request, insert count, exact client-record count, platform record ID, aggregate diagnostic, retry/idempotency flag, and a computed `verified` flag.
- Added `StepWriter.writeAndVerify(request)`. The existing interval-only `write(interval)` entry point remains as a compatibility bridge and derives a deterministic legacy client ID so an identical retry can be recognized.
- `HealthConnectStepWriter` now writes with `Metadata.manualEntry(clientRecordId, clientRecordVersion)`, queries app-origin raw records, selects the exact client ID, validates one matching interval/count, and only then returns success. A pre-existing exact record is returned as `wasAlreadyPresent` without inserting another record. Aggregate count remains diagnostic and is not the exact-record success criterion.
- `FakeStepWriter` now models exact client IDs and idempotent retries, allowing controller tests to exercise the same contract.
- Added regression tests for exact record verification and aggregate diagnostics, retry non-duplication, failed write progress behavior, and a 3000-step direct interval ending at the supplied instant. The planner uses 2.5 steps/sec, so 3000 steps produce a 1200-second interval.

## TDD/test evidence

The new contract tests were first run before implementation and failed at compilation with unresolved `StepWriteRequest`/`writeAndVerify` references. After implementation:

```text
./gradlew :app:testDebugUnitTest --tests 'com.choupeanut.fitstepcontroller.data.FakeStepWriterTest' --tests 'com.choupeanut.fitstepcontroller.domain.StepPlannerTest'
BUILD SUCCESSFUL

./gradlew :app:testDebugUnitTest --tests 'com.choupeanut.fitstepcontroller.domain.WalkingSessionControllerTest'
BUILD SUCCESSFUL

./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL
```

## Follow-up/integration notes

- The service and direct UI still call `write(interval)` for staged compatibility. Task 2/3 should construct stable IDs per session/chunk and call `writeAndVerify` directly, persisting a pending chunk until verification succeeds.
- The production writer keeps `appPackageName` filtering for raw reads. When it is null, exact client-ID filtering still applies; integration should pass the application package name for strongest source scoping.
- Google Fit totals are outside this contract. `aggregateSteps` is intentionally diagnostic because Health Connect source-priority/deduplication can make aggregate totals differ from the app's exact manual record.
