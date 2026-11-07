# Hand Mouse Controller Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implement these tasks with the `tlc-spec-driven` skill: **activate it by name and follow its Execute flow and Critical Rules.**

---

**Design**: `.specs/features/hand-mouse-controller/design.md`
**Status**: Done

---

## Test Coverage Matrix

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Gesture / Math / Coordinate Logic | unit | 1:1 to spec ACs (smoothing, coordinate mapping, pinch detection) | `app/src/test/java/com/remotedisplay/player/gesture/*Test.kt` | `./gradlew testDebugUnitTest --tests com.remotedisplay.player.gesture.*` |
| Touch Event Dispatcher | unit | All state transitions (`NONE -> DOWN -> MOVE -> UP`) | `app/src/test/java/com/remotedisplay/player/gesture/*Test.kt` | `./gradlew testDebugUnitTest --tests com.remotedisplay.player.gesture.*` |
| UI Overlay & Camera Engine | unit / build gate | Compilation & Lifecycle integration | `app/src/main/java/com/remotedisplay/player/gesture/*` | `./gradlew assembleDebug` |

## Gate Check Commands

- Unit tests: `.\gradlew testDebugUnitTest`
- Build check: `.\gradlew compileDebugKotlin`

---

## Execution Plan

### Phase 1: Dependencies & Math Core
- [x] **T1: Configure Dependencies in Gradle** (Added CameraX and MediaPipe Tasks Vision, set minSdk = 24).
- [x] **T2: Implement CursorGestureProcessor with Unit Tests** (`CursorGestureProcessor.kt`, `CursorGestureProcessorTest.kt` passing).

### Phase 2: Touch Injection & UI Overlay
- [x] **T3: Implement SyntheticTouchDispatcher with Unit Tests** (`SyntheticTouchDispatcher.kt`, `SyntheticTouchDispatcherTest.kt` passing).
- [x] **T4: Implement VirtualMouseOverlayView** (`VirtualMouseOverlayView.kt` providing glowing pointer, hover ring, and pinch animation).

### Phase 3: MediaPipe Vision & CameraX Integration
- [x] **T5: Implement HandLandmarkerHelper & Asset Setup** (`HandLandmarkerHelper.kt` + downloaded `hand_landmarker.task` 7.8MB model in assets).
- [x] **T6: Implement HandTrackingCameraManager** (`HandTrackingCameraManager.kt` capturing ImageProxy from front camera / external USB webcam).
- [x] **T7: Wire HandMouseCoordinator into MainActivity / Player UI** (`HandMouseCoordinator.kt` wired into `MainActivity.kt` with key shortcuts `M`/`H`, overlay attached to content root).
