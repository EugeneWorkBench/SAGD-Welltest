# SAGD WellTest — Android

Offline-first SAGD well-test field capture app (Kotlin + Jetpack Compose).

## Layered architecture

The app follows the shared layered architecture from the design document.
Dependencies point downward only; the domain layer has no knowledge of UI,
persistence engines, or networking.

| Package (`com.sagd.welltest.*`) | Layer | Key tech |
|---|---|---|
| `ui` | UI | Jetpack Compose, ViewModel, StateFlow |
| `domain` | Domain / use cases (pure) | Plain Kotlin; primary PBT target |
| `repository` | Repository | Interfaces over Room DAOs, Flow queries |
| `localstore` | Local store | Room (SQLite) + file blob store |
| `sync` | Sync engine | WorkManager (CoroutineWorker), ConnectivityManager |
| `integrations` | Platform integrations | CameraX + ML Kit barcode, FusedLocationProvider |
| `network` | Cloud API client | REST push/pull endpoints |

## Dependencies

Declared in `gradle/libs.versions.toml`: Room, WorkManager, CameraX, ML Kit
barcode scanning, and play-services-location for production code;
`kotest-property` (plus kotest assertions and the JUnit 5 runner) for tests.

## Testing

Unit tests run on the JVM via JUnit Platform (`useJUnitPlatform()` in
`app/build.gradle.kts`). Property-based tests use kotest-property. The
project-wide kotest config (`src/test/.../KotestProjectConfig.kt`) defaults
every property test to a minimum of **100 iterations**; use the `pbtConfig(...)`
helper when a test needs an explicit config that still respects that floor.

```bash
./gradlew test            # run unit + property tests
./gradlew assembleDebug   # build the app
```

## First-time setup

The Gradle wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) is a binary and is
not checked in as text. Generate it once with a local Gradle install:

```bash
gradle wrapper --gradle-version 8.9
```

A JDK 17 and the Android SDK (compileSdk 34) are required to build.
