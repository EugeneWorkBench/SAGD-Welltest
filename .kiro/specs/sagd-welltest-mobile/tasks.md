# Implementation Plan: SAGD WellTest Mobile

## Overview

This plan implements the offline-first SAGD WellTest field capture app as two native clients — iOS (Swift/SwiftUI, GRDB, BGTaskScheduler, AVFoundation, CoreLocation) and Android (Kotlin/Jetpack Compose, Room, WorkManager, CameraX/ML Kit, FusedLocationProvider) — plus a shared Cloud_Backend REST API client on each platform.

Both codebases share one architecture, data model, domain logic, and the same 24 correctness properties. The plan is organized by layer following the layered build order: scaffolding → data model & local store → repository layer → pure domain logic → sync engine → platform integrations → UI screens → cloud backend API client → wiring/end-to-end. Pure domain logic (subcool estimator, validators, well lookup) is implemented early because it is the primary property-based-testing target.

Planning is shared across platforms, but each layer has concrete, separate iOS and Android implementation tasks because the two native codebases are distinct. Android tasks are grouped under their own epics and are not marked optional by default (both platforms are required). To defer one platform for an MVP, the Android epics (or the iOS epics) can be skipped as a unit; this is the natural optional boundary.

Property-based testing: each of the 24 design correctness properties is covered by exactly one property-based test per platform, minimum 100 iterations, tagged `Feature: sagd-welltest-mobile, Property {n}: {text}`. iOS uses SwiftCheck; Android uses kotest-property. Example-based unit tests and integration tests follow the design's testing strategy.

## Tasks

- [x] 1. iOS: Project scaffolding and shared domain contracts
  - [x] 1.1 Create iOS app project structure and layer skeleton
    - Create an Xcode project (SwiftUI app) with module/group folders for `Domain`, `Repository`, `LocalStore`, `Sync`, `Integrations`, `UI`, `Network`
    - Add Swift Package dependencies: GRDB; SwiftCheck (test target only)
    - Configure iOS test target and a PBT helper that runs a minimum of 100 iterations
    - _Requirements: 1.2_
  - [x] 1.2 Define shared domain model types and result enums (iOS)
    - Create Swift types: `WellType`, `Well`, `WellTest`, `WellheadLog` (discriminated by `logType`), `SubcoolReading`, `MaintenanceTask`, `Escalation`, `SyncMetadata`, `SyncState`
    - Create result enums: `SaveResult` (`success`/`storageFull`/`failure`), `ValidationResult`, `FieldError`, `SubcoolResult`, `WellLookupResult`
    - Define repository and use-case protocols: `WellRepository`, `LogRepository`, `EscalationRepository`, `SyncRepository`, `SubcoolEstimating`, validators, `WellLookupUseCase`, `SyncEngine`
    - _Requirements: 1.1, 2.6, 3.1, 4.1, 5.1, 6.2, 7.1_

- [x] 2. Android: Project scaffolding and shared domain contracts
  - [x] 2.1 Create Android app project structure and layer skeleton
    - Create a Gradle Android project (Compose) with packages for `domain`, `repository`, `localstore`, `sync`, `integrations`, `ui`, `network`
    - Add dependencies: Room, WorkManager, CameraX, ML Kit barcode, play-services-location; kotest-property (test only)
    - Configure the unit test module and a kotest property config defaulting to a minimum of 100 iterations
    - _Requirements: 1.2_
  - [x] 2.2 Define shared domain model types and result sealed classes (Android)
    - Create Kotlin data/sealed classes mirroring the iOS model: `WellType`, `Well`, `WellTest`, `WellheadLog`, `SubcoolReading`, `MaintenanceTask`, `Escalation`, `SyncMetadata`, `SyncState`
    - Create result sealed classes: `SaveResult`, `ValidationResult`, `FieldError`, `SubcoolResult`, `WellLookupResult`
    - Define repository/use-case interfaces matching the iOS protocols
    - _Requirements: 1.1, 2.6, 3.1, 4.1, 5.1, 6.2, 7.1_

- [x] 3. iOS: Local store — schema, migrations, and blob store
  - [x] 3.1 Implement GRDB schema and migrations (iOS)
    - Create GRDB migrations for `well`, `wellTest`, `wellheadLog`, `subcoolReading`, `maintenanceTask`, `escalation` tables with embedded `SyncMetadata` columns (`syncState`, `retryCount`, `createdAt`, `lastAttemptAt`, `remoteId`)
    - Set up `DatabaseQueue` opening the existing DB on launch so persisted records reload after restart
    - _Requirements: 1.1, 1.3_
  - [x] 3.2 Implement photo blob file store (iOS)
    - Write blobs to app `Application Support`; write blob file before the referencing row; add orphan-blob garbage collection on launch
    - _Requirements: 7.3, 7.4_

- [x] 4. Android: Local store — schema, migrations, and blob store
  - [x] 4.1 Implement Room entities, DAOs, and migrations (Android)
    - Create Room `@Entity` classes and DAOs mirroring the shared schema with embedded `SyncMetadata`
    - Configure the Room database to open the existing DB on launch so persisted records reload after restart
    - _Requirements: 1.1, 1.3_
  - [x] 4.2 Implement photo blob file store (Android)
    - Write blobs to app-internal storage; write blob file before the referencing row; add orphan-blob garbage collection on launch
    - _Requirements: 7.3, 7.4_

- [x] 5. iOS: Repository layer over the local store
  - [x] 5.1 Implement repositories with transactional, storage-aware saves (iOS)
    - Implement `WellRepository`, `LogRepository`, `EscalationRepository`, `SyncRepository` over GRDB
    - Transactional saves: success commits `syncState=pending`, `createdAt=now`, `retryCount=0`; I/O failure rolls back leaving prior state untouched; pre-write free-space check returns `storageFull`
    - `SyncRepository.nextPendingBatch` orders by `createdAt` ASC; `markSynchronized`, `recordFailure`, `manualRetry`, `observeSyncState` via GRDB observation
    - `WellRepository.getWellTests` ordered most-recent-first; `getOpenMaintenanceTasks` filters `status=open`
    - _Requirements: 1.1, 1.4, 1.5, 2.1, 2.3, 2.5, 2.6, 2.7, 6.2_
  - [x] 5.2 Property test — persistence round-trip (iOS)
    - **Property 1: Persistence round-trip** (save then reload, including close/reopen, yields equal captured fields)
    - **Validates: Requirements 1.1, 1.3, 7.3**
  - [x] 5.3 Property test — capture requires no network (iOS)
    - **Property 2: Capture requires no network** (repository create/view/edit succeed with network layer unavailable)
    - **Validates: Requirements 1.2**
  - [x] 5.4 Property test — atomic save preserves prior state (iOS)
    - **Property 3: Atomic save — failure preserves prior state** (injected write failure leaves store unchanged, reports failure)
    - **Validates: Requirements 1.4, 7.4**
  - [x] 5.5 Property test — QR lookup ordering and open-task filtering (iOS)
    - **Property 19: QR lookup ordering and open-task filtering** (tests most-recent-first, only open tasks)
    - **Validates: Requirements 6.2**
  - [x] 5.6 Unit test — storage-full rejection (iOS)
    - Assert `storageFull` return path retains in-form data; boundary insufficient-storage scenario
    - _Requirements: 1.5_

- [x] 6. Android: Repository layer over the local store
  - [x] 6.1 Implement repositories with transactional, storage-aware saves (Android)
    - Implement `WellRepository`, `LogRepository`, `EscalationRepository`, `SyncRepository` over Room DAOs
    - Transactional saves with the same success/rollback/`storageFull` semantics as iOS
    - `nextPendingBatch` orders by `createdAt` ASC; sync-state mutators; observable queries via `Flow`
    - `getWellTests` most-recent-first; `getOpenMaintenanceTasks` filters `status=open`
    - _Requirements: 1.1, 1.4, 1.5, 2.1, 2.3, 2.5, 2.6, 2.7, 6.2_
  - [x] 6.2 Property test — persistence round-trip (Android)
    - **Property 1: Persistence round-trip**
    - **Validates: Requirements 1.1, 1.3, 7.3**
  - [x] 6.3 Property test — capture requires no network (Android)
    - **Property 2: Capture requires no network**
    - **Validates: Requirements 1.2**
  - [x] 6.4 Property test — atomic save preserves prior state (Android)
    - **Property 3: Atomic save — failure preserves prior state**
    - **Validates: Requirements 1.4, 7.4**
  - [x] 6.5 Property test — QR lookup ordering and open-task filtering (Android)
    - **Property 19: QR lookup ordering and open-task filtering**
    - **Validates: Requirements 6.2**
  - [x] 6.6 Unit test — storage-full rejection (Android)
    - Assert `storageFull` return path retains in-form data; boundary insufficient-storage scenario
    - _Requirements: 1.5_

- [x] 7. Checkpoint — storage and repository foundation
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. iOS: Pure domain logic — subcool estimator
  - [x] 8.1 Implement SteamTable and SubcoolEstimator (iOS)
    - Bundle the monotonic `Steam_Table`; implement `saturationTemperature(pressure)` with bracket lookup and linear interpolation, returning nil outside the domain
    - Implement `estimate(casingPressure, pumpTemperature)` returning `Success(subcool, isFlashing)` / `OutOfRange` / `InvalidInput(field)`; `subcool = Tsat - Tpump`; `isFlashing = subcool <= flashingThreshold` with injected threshold
    - _Requirements: 5.1, 5.3, 5.4, 5.5_
  - [x] 8.2 Property test — subcool definition (iOS)
    - **Property 14: Subcool definition** (subcool equals interpolated Tsat minus pump temp)
    - **Validates: Requirements 5.1**
  - [x] 8.3 Property test — subcool monotonicity (iOS)
    - **Property 15: Subcool monotonicity** (non-decreasing in pressure; strictly decreasing in pump temp)
    - **Validates: Requirements 5.1**
  - [x] 8.4 Property test — flashing-zone classification (iOS)
    - **Property 16: Flashing-zone classification** (flashing iff value <= threshold)
    - **Validates: Requirements 5.3**
  - [x] 8.5 Property test — out-of-range pressure rejection (iOS)
    - **Property 17: Out-of-range pressure rejection** (outside domain rejects; inside produces a value)
    - **Validates: Requirements 5.4**
  - [x] 8.6 Property test — invalid subcool input rejection (iOS)
    - **Property 18: Invalid subcool input rejection** (empty/non-numeric rejected, names invalid field)
    - **Validates: Requirements 5.5**

- [x] 9. iOS: Pure domain logic — validators
  - [x] 9.1 Implement production and injection log validators (iOS)
    - Numeric-format check (digits, at most one decimal point, optional leading minus); per-field range checks (water cut [0,100]; production fields [0.00,999999.99]; steam pressure [0,25000]; steam temp [0,400]; steam flow [0,2000]); save-blocking returns exactly the offending field set and retains values
    - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.2, 4.3, 4.4, 3.6, 4.5_
  - [x] 9.2 Property test — numeric-format validation (iOS)
    - **Property 10: Numeric-format validation**
    - **Validates: Requirements 3.3, 4.2**
  - [x] 9.3 Property test — range validation per field (iOS)
    - **Property 11: Range validation per field**
    - **Validates: Requirements 3.2, 3.4, 4.3**
  - [x] 9.4 Property test — save blocked exactly on invalid fields (iOS)
    - **Property 12: Save blocked exactly on invalid fields**
    - **Validates: Requirements 3.5, 4.4**
  - [x] 9.5 Property test — valid log association and timestamp (iOS)
    - **Property 13: Valid log association and timestamp** (UTC, 1s precision, well id associated)
    - **Validates: Requirements 3.6, 4.5**
  - [x] 9.6 Unit test — range boundary examples (iOS)
    - Exact boundaries: `0`, `100`, `25000`, `400`, `2000`, `999999.99`
    - _Requirements: 3.2, 3.4, 4.3_

- [x] 10. iOS: Pure domain logic — well lookup and escalation assembly
  - [x] 10.1 Implement WellLookupUseCase and EscalationValidator (iOS)
    - `WellLookupUseCase.lookup(wellId)` over the repository returns `Found(well, tests[desc], openTasks)` / `NotFound` / `UnavailableOffline`, identical regardless of connectivity when the well exists locally
    - `EscalationValidator` requires a priority (High/Medium/Low) and a well id; reports exactly the absent selections and retains data
    - _Requirements: 6.2, 6.5, 7.2_
  - [x] 10.2 Property test — QR lookup independent of connectivity (iOS)
    - **Property 20: QR lookup independent of connectivity**
    - **Validates: Requirements 6.5**
  - [x] 10.3 Property test — escalation save requires priority and well (iOS)
    - **Property 22: Escalation save requires priority and well**
    - **Validates: Requirements 7.2**

- [x] 11. Android: Pure domain logic — subcool estimator
  - [x] 11.1 Implement SteamTable and SubcoolEstimator (Android)
    - Mirror the iOS algorithm: bundled monotonic steam table, bracket + linear interpolation, domain check; `estimate` returning the same three result cases with injected `flashingThreshold`
    - _Requirements: 5.1, 5.3, 5.4, 5.5_
  - [x] 11.2 Property test — subcool definition (Android)
    - **Property 14: Subcool definition**
    - **Validates: Requirements 5.1**
  - [x] 11.3 Property test — subcool monotonicity (Android)
    - **Property 15: Subcool monotonicity**
    - **Validates: Requirements 5.1**
  - [x] 11.4 Property test — flashing-zone classification (Android)
    - **Property 16: Flashing-zone classification**
    - **Validates: Requirements 5.3**
  - [x] 11.5 Property test — out-of-range pressure rejection (Android)
    - **Property 17: Out-of-range pressure rejection**
    - **Validates: Requirements 5.4**
  - [x] 11.6 Property test — invalid subcool input rejection (Android)
    - **Property 18: Invalid subcool input rejection**
    - **Validates: Requirements 5.5**

- [x] 12. Android: Pure domain logic — validators
  - [x] 12.1 Implement production and injection log validators (Android)
    - Mirror iOS numeric-format, per-field range, and save-blocking semantics
    - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.2, 4.3, 4.4, 3.6, 4.5_
  - [x] 12.2 Property test — numeric-format validation (Android)
    - **Property 10: Numeric-format validation**
    - **Validates: Requirements 3.3, 4.2**
  - [x] 12.3 Property test — range validation per field (Android)
    - **Property 11: Range validation per field**
    - **Validates: Requirements 3.2, 3.4, 4.3**
  - [x] 12.4 Property test — save blocked exactly on invalid fields (Android)
    - **Property 12: Save blocked exactly on invalid fields**
    - **Validates: Requirements 3.5, 4.4**
  - [x] 12.5 Property test — valid log association and timestamp (Android)
    - **Property 13: Valid log association and timestamp**
    - **Validates: Requirements 3.6, 4.5**
  - [x] 12.6 Unit test — range boundary examples (Android)
    - Exact boundaries: `0`, `100`, `25000`, `400`, `2000`, `999999.99`
    - _Requirements: 3.2, 3.4, 4.3_

- [x] 13. Android: Pure domain logic — well lookup and escalation assembly
  - [x] 13.1 Implement WellLookupUseCase and EscalationValidator (Android)
    - Mirror the iOS lookup result cases and escalation validation rules
    - _Requirements: 6.2, 6.5, 7.2_
  - [x] 13.2 Property test — QR lookup independent of connectivity (Android)
    - **Property 20: QR lookup independent of connectivity**
    - **Validates: Requirements 6.5**
  - [x] 13.3 Property test — escalation save requires priority and well (Android)
    - **Property 22: Escalation save requires priority and well**
    - **Validates: Requirements 7.2**

- [x] 14. Checkpoint — pure domain logic (primary PBT target)
  - Ensure all tests pass, ask the user if questions arise.

- [x] 15. iOS: Sync engine and connectivity
  - [x] 15.1 Implement SyncEngine with per-record state machine (iOS)
    - `runSyncCycle` pulls `nextPendingBatch` oldest-first, pushes each via the network client, advances state: 2xx → `synchronized` + `remoteId`; failure → `retryCount++`, keep record; 5th failure → `failed`; exclude `failed` from batches; `manualRetry` resets to `pending`
    - Exponential backoff keyed on `lastAttemptAt`; idempotent push via client `id`
    - `NWPathMonitor` drives `onConnectivityAvailable` (sync within 60s) and the offline indicator (within 5s of loss); register `BGTaskScheduler` task and background `URLSession` for blob uploads
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.8, 7.6, 7.7_
  - [x] 15.2 Property test — oldest-first push ordering (iOS)
    - **Property 4: Oldest-first push ordering**
    - **Validates: Requirements 2.1**
  - [x] 15.3 Property test — acknowledgment yields synchronized state (iOS)
    - **Property 5: Acknowledgment yields synchronized state**
    - **Validates: Requirements 2.3**
  - [x] 15.4 Property test — no data loss on failed sync (iOS)
    - **Property 6: No data loss on failed sync**
    - **Validates: Requirements 2.4**
  - [x] 15.5 Property test — five-retry boundary and manual-retry reset (iOS)
    - **Property 7: Five-retry boundary and manual-retry reset**
    - **Validates: Requirements 2.5, 7.7**
  - [x] 15.6 Property test — pending-state invariant (iOS)
    - **Property 8: Pending-state invariant**
    - **Validates: Requirements 2.6**
  - [x] 15.7 Property test — sync idempotency (iOS)
    - **Property 9: Sync idempotency**
    - **Validates: Requirements 2.3, 2.4**
  - [x] 15.8 Integration test — connectivity timing (iOS)
    - Fake `NWPathMonitor` + controllable clock: sync begins within 60s of restore; offline indicator within 5s of loss
    - _Requirements: 2.2, 2.8_

- [x] 16. Android: Sync engine and connectivity
  - [x] 16.1 Implement SyncEngine with per-record state machine (Android)
    - Implement the same cycle, ordering, state transitions, backoff, and idempotency over a `CoroutineWorker`
    - `ConnectivityManager.NetworkCallback` enqueues unique `WorkManager` work constrained to `CONNECTED` (sync within 60s); `onLost` publishes the offline indicator (within 5s); periodic WorkManager fallback
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.8, 7.6, 7.7_
  - [x] 16.2 Property test — oldest-first push ordering (Android)
    - **Property 4: Oldest-first push ordering**
    - **Validates: Requirements 2.1**
  - [x] 16.3 Property test — acknowledgment yields synchronized state (Android)
    - **Property 5: Acknowledgment yields synchronized state**
    - **Validates: Requirements 2.3**
  - [x] 16.4 Property test — no data loss on failed sync (Android)
    - **Property 6: No data loss on failed sync**
    - **Validates: Requirements 2.4**
  - [x] 16.5 Property test — five-retry boundary and manual-retry reset (Android)
    - **Property 7: Five-retry boundary and manual-retry reset**
    - **Validates: Requirements 2.5, 7.7**
  - [x] 16.6 Property test — pending-state invariant (Android)
    - **Property 8: Pending-state invariant**
    - **Validates: Requirements 2.6**
  - [x] 16.7 Property test — sync idempotency (Android)
    - **Property 9: Sync idempotency**
    - **Validates: Requirements 2.3, 2.4**
  - [x] 16.8 Integration test — connectivity timing and background execution (Android)
    - Fake `NetworkCallback` + controllable clock: sync begins within 60s of restore; offline indicator within 5s of loss; `WorkManager` job resumes across process death
    - _Requirements: 2.2, 2.8, 7.6_

- [x] 17. Checkpoint — sync engine
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. iOS: Platform integrations — camera/QR, location
  - [x] 18.1 Implement QR scanner (iOS)
    - `AVCaptureMetadataOutput` (`.qr`/barcode); deliver decoded string to `WellLookupUseCase`; 2s decode budget, 10s unreadable timeout + rescan control
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 6.6_
  - [x] 18.2 Implement location provider and escalation capture (iOS)
    - `CLLocationManager` fix bounded at 30s → decimal-degree lat/long + UTC 1s timestamp; on timeout set `locationUnavailable` and allow proceed; assemble + persist escalation (blob first, then row) within 5s
    - _Requirements: 7.1, 7.3, 7.4, 7.5_
  - [x] 18.3 Property test — escalation geotag attachment (iOS)
    - **Property 21: Escalation geotag attachment**
    - **Validates: Requirements 7.1**
  - [x] 18.4 Property test — location-unavailable fallback (iOS)
    - **Property 23: Location-unavailable fallback**
    - **Validates: Requirements 7.5**
  - [x] 18.5 Integration test — scanner timing (iOS)
    - Feed known/undecodable frames: assert 2s decode and 10s unreadable budgets
    - _Requirements: 6.1, 6.4_

- [x] 19. Android: Platform integrations — camera/QR, location
  - [x] 19.1 Implement QR scanner (Android)
    - CameraX `ImageAnalysis` + ML Kit barcode; deliver decoded string to `WellLookupUseCase`; 2s decode budget, 10s unreadable timeout + rescan control
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 6.6_
  - [x] 19.2 Implement location provider and escalation capture (Android)
    - `FusedLocationProviderClient` fix bounded at 30s → decimal-degree lat/long + UTC 1s timestamp; on timeout set `locationUnavailable` and allow proceed; assemble + persist escalation (blob first, then row) within 5s
    - _Requirements: 7.1, 7.3, 7.4, 7.5_
  - [x] 19.3 Property test — escalation geotag attachment (Android)
    - **Property 21: Escalation geotag attachment**
    - **Validates: Requirements 7.1**
  - [x] 19.4 Property test — location-unavailable fallback (Android)
    - **Property 23: Location-unavailable fallback**
    - **Validates: Requirements 7.5**
  - [x] 19.5 Integration test — scanner timing (Android)
    - Feed known/undecodable frames: assert 2s decode and 10s unreadable budgets
    - _Requirements: 6.1, 6.4_

- [x] 20. iOS: UI screens and view models
  - [x] 20.1 Implement well selection and wellhead log forms (iOS)
    - SwiftUI well selection; production form (emulsion flow rate, casing gas pressure, ESP motor temp, water cut %) and injection form (steam injection pressure, steam temp, steam flow rate) chosen by `Well.type`; wire validators; show per-field validation messages, retain values, block save; success indicator within 2s; storage-full message
    - _Requirements: 1.1, 1.5, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4_
  - [x] 20.2 Implement subcool estimator, QR result, and escalation screens (iOS)
    - Subcool screen displaying the value within 1s with a distinguished flashing-zone alert state; QR result screen with recent-first tests + open tasks, not-found/offline branches, and local-storage annotation; escalation capture screen with priority/well selection and photo
    - _Requirements: 5.2, 5.3, 6.2, 6.3, 6.5, 6.6, 7.1, 7.2_
  - [x] 20.3 Implement sync-state and offline indicators (iOS)
    - Per-record pending/synchronized/failed badges updating within 5s via observable queries; manual retry control; global offline indicator
    - _Requirements: 2.6, 2.7, 2.8_
  - [x] 20.4 UI tests — form rendering and sync-state display (iOS)
    - SwiftUI snapshot/preview tests: field presence per well type (Req 3.1, 4.1); sync-state badge rendering (Req 2.7)
    - _Requirements: 3.1, 4.1, 2.7_

- [x] 21. Android: UI screens and view models
  - [x] 21.1 Implement well selection and wellhead log forms (Android)
    - Compose well selection; production and injection forms chosen by `Well.type`; wire validators with per-field messages, value retention, save blocking; success indicator within 2s; storage-full message
    - _Requirements: 1.1, 1.5, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4_
  - [x] 21.2 Implement subcool estimator, QR result, and escalation screens (Android)
    - Subcool screen (value within 1s, flashing alert state); QR result screen with recent-first tests + open tasks, not-found/offline branches, local-storage annotation; escalation capture screen
    - _Requirements: 5.2, 5.3, 6.2, 6.3, 6.5, 6.6, 7.1, 7.2_
  - [x] 21.3 Implement sync-state and offline indicators (Android)
    - Per-record badges via `StateFlow` updating within 5s; manual retry control; global offline indicator
    - _Requirements: 2.6, 2.7, 2.8_
  - [x] 21.4 UI tests — form rendering and sync-state display (Android)
    - Compose UI tests: field presence per well type; sync-state badge rendering
    - _Requirements: 3.1, 4.1, 2.7_

- [x] 22. iOS: Cloud backend API client and end-to-end wiring
  - [x] 22.1 Implement REST API client (iOS)
    - `URLSession` client for push endpoints (`/v1/well-tests`, `/v1/wellhead-logs`, `/v1/subcool-readings`, `/v1/escalations` multipart) and pull endpoints (well, tests desc, open tasks, incremental wells); parse `{id, remoteId, status}`; classify `409/422` non-retryable vs `5xx`/timeout retryable; cache pulled data into the local store
    - _Requirements: 2.1, 2.3, 6.2, 6.5, 7.6_
  - [x] 22.2 Wire sync engine to the API client and finalize app graph (iOS)
    - Connect `SyncEngine` push/pull to the client; multipart escalation upload streaming the blob; compose the dependency graph (repositories, domain, sync, integrations, UI) into the running app
    - _Requirements: 2.1, 2.3, 7.6_
  - [x] 22.3 Property test — escalation photo included in push (iOS)
    - **Property 24: Escalation photo included in push** (payload includes metadata + photo bytes identical to the blob store)
    - **Validates: Requirements 7.6**
  - [x] 22.4 Integration test — end-to-end sync against stub backend (iOS)
    - Push representative records against a stub Cloud_Backend; assert acknowledgment, synchronized-state update within 5s, and multipart photo integrity
    - _Requirements: 2.3, 7.6_

- [x] 23. Android: Cloud backend API client and end-to-end wiring
  - [x] 23.1 Implement REST API client (Android)
    - HTTP client (e.g., Retrofit/OkHttp) for the same push/pull endpoints with multipart escalation upload; parse the acknowledgment contract; classify retryable vs non-retryable; cache pulled data into Room
    - _Requirements: 2.1, 2.3, 6.2, 6.5, 7.6_
  - [x] 23.2 Wire sync engine to the API client and finalize app graph (Android)
    - Connect the `CoroutineWorker` sync cycle to the client; multipart escalation upload streaming the blob; compose the full app graph
    - _Requirements: 2.1, 2.3, 7.6_
  - [x] 23.3 Property test — escalation photo included in push (Android)
    - **Property 24: Escalation photo included in push**
    - **Validates: Requirements 7.6**
  - [x] 23.4 Integration test — end-to-end sync against stub backend (Android)
    - Push representative records against a stub Cloud_Backend; assert acknowledgment, synchronized-state update within 5s, and multipart photo integrity
    - _Requirements: 2.3, 7.6_

- [x] 24. Final checkpoint — full build and all tests
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (property tests, unit tests, integration tests, UI tests) and can be skipped for a faster MVP, though all 24 correctness properties are expected to be covered before release.
- All 24 design correctness properties are covered exactly once per platform (Properties 1–24). Each property test runs a minimum of 100 iterations and is tagged `Feature: sagd-welltest-mobile, Property {n}: {text}`.
- iOS property tests use SwiftCheck; Android property tests use kotest-property. Neither platform implements PBT from scratch.
- Sync property tests (4–9, 24) run against an in-memory recording stub of the Cloud_Backend; subcool properties (14–18) inject a deterministic Steam_Table; the local store uses a temporary SQLite database.
- Both platforms are required by default. To defer one platform, skip its epics as a unit (iOS: 1, 3, 5, 8, 9, 10, 15, 18, 20, 22; Android: 2, 4, 6, 11, 12, 13, 16, 19, 21, 23).
- Each task references the specific requirements and/or correctness properties it implements for traceability.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["1.2", "2.2"] },
    { "id": 2, "tasks": ["3.1", "3.2", "4.1", "4.2"] },
    { "id": 3, "tasks": ["5.1", "6.1", "8.1", "9.1", "11.1", "12.1"] },
    { "id": 4, "tasks": ["5.2", "5.3", "5.4", "5.5", "5.6", "6.2", "6.3", "6.4", "6.5", "6.6", "8.2", "8.3", "8.4", "8.5", "8.6", "9.2", "9.3", "9.4", "9.5", "9.6", "10.1", "11.2", "11.3", "11.4", "11.5", "11.6", "12.2", "12.3", "12.4", "12.5", "12.6", "13.1"] },
    { "id": 5, "tasks": ["10.2", "10.3", "13.2", "13.3", "15.1", "16.1"] },
    { "id": 6, "tasks": ["15.2", "15.3", "15.4", "15.5", "15.6", "15.7", "15.8", "16.2", "16.3", "16.4", "16.5", "16.6", "16.7", "16.8", "18.1", "18.2", "19.1", "19.2"] },
    { "id": 7, "tasks": ["18.3", "18.4", "18.5", "19.3", "19.4", "19.5", "20.1", "20.2", "20.3", "21.1", "21.2", "21.3"] },
    { "id": 8, "tasks": ["20.4", "21.4", "22.1", "23.1"] },
    { "id": 9, "tasks": ["22.2", "23.2"] },
    { "id": 10, "tasks": ["22.3", "22.4", "23.3", "23.4"] }
  ]
}
```
