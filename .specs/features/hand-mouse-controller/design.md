# Hand Mouse Controller Design

**Spec**: `.specs/features/hand-mouse-controller/spec.md`
**Status**: Approved

---

## Architecture Overview

The system captures video frames from the camera (front camera or USB webcam), processes hand landmarks using Google MediaPipe Hand Landmarker, calculates smoothed screen coordinates for the index finger tip, detects pinch gestures between index and thumb, and renders an interactive mouse cursor overlay that dispatches touch events to the active view.

```mermaid
graph TD
    Camera[CameraX ImageAnalysis] -->|ImageProxy / Bitmap| Preprocess[Frame Preprocessor & Mirroring]
    Preprocess -->|MPImage| Detector[MediaPipe HandLandmarker Helper]
    Detector -->|HandLandmarkerResult 21 Landmarks| GestureEngine[Gesture & Coordinate Engine]
    GestureEngine -->|Smoothed X,Y| MouseOverlay[VirtualMouseOverlayView]
    GestureEngine -->|Pinch Detect (Down/Up)| EventDispatcher[MotionEvent Dispatcher]
    EventDispatcher -->|ACTION_DOWN / UP / MOVE| TargetView[Player Content View Hierarchy]
    MouseOverlay -->|Render Cursor & Click Pulse| Screen[Android Display]
```

---

## Code Reuse Analysis

### Existing Components to Leverage

| Component | Location | How to Use |
| --- | --- | --- |
| `AudienceCamera` camera picker logic | `com.remotedisplay.player.audience.AudienceCamera` | Reference Camera2 / CameraManager enumeration for USB/external webcams on TV Boxes |
| `TriggerOverlay` / Custom Views | `com.remotedisplay.player.trigger.TriggerOverlay` | Reuses view overlay layering patterns over the main player video/web hierarchy |
| `MainActivity` | `com.remotedisplay.player.MainActivity` | Mounts the overlay view and manages camera lifecycle without breaking main signage loop |

### Integration Points

| System | Integration Method |
| --- | --- |
| `MainActivity.kt` | Initializes `HandMouseManager` when gesture control is enabled in settings or triggered |
| Player View Hierarchy | Receives synthetic `MotionEvent` (ACTION_DOWN, ACTION_MOVE, ACTION_UP) targeted at the current cursor coordinates |

---

## Components

### 1. `HandLandmarkerHelper`
- **Purpose**: Wraps MediaPipe Tasks Vision `HandLandmarker` in `LIVE_STREAM` mode with background executor and listener callbacks.
- **Location**: `com.remotedisplay.player.gesture.HandLandmarkerHelper`
- **Interfaces**:
  - `initLandmarker(context: Context)`
  - `detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean)`
  - `clear()`
- **Dependencies**: `com.google.mediapipe:tasks-vision`
- **Reuses**: Background thread pooling

### 2. `HandTrackingCameraManager`
- **Purpose**: Manages CameraX `ProcessCameraProvider`, selects camera (front-facing or external lens), configures `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`.
- **Location**: `com.remotedisplay.player.gesture.HandTrackingCameraManager`
- **Interfaces**:
  - `startCamera(lifecycleOwner: LifecycleOwner, onFrame: (ImageProxy) -> Unit)`
  - `stopCamera()`
- **Dependencies**: `androidx.camera:camera-core`, `androidx.camera:camera-camera2`, `androidx.camera:camera-lifecycle`

### 3. `CursorGestureProcessor`
- **Purpose**: Normalizes landmark coordinates (0..1) to screen pixels, applies Exponential Moving Average (EMA) smoothing to eliminate hand jitter, calculates Euclidean distance between thumb tip (index 4) and index finger tip (index 8) to trigger Pinch-to-Click.
- **Location**: `com.remotedisplay.player.gesture.CursorGestureProcessor`
- **Interfaces**:
  - `process(landmarks: List<NormalizedLandmark>, screenWidth: Int, screenHeight: Int): CursorState`
- **Dependencies**: Android `PointF`, Math helpers

### 4. `VirtualMouseOverlayView`
- **Purpose**: Lightweight custom view added above the player content that draws the mouse pointer, hover ring, and pinch/click animation.
- **Location**: `com.remotedisplay.player.gesture.VirtualMouseOverlayView`
- **Interfaces**:
  - `updateCursor(x: Float, y: Float, isClicking: Boolean, isVisible: Boolean)`
- **Dependencies**: Android `Canvas`, `Paint`

### 5. `SyntheticTouchDispatcher`
- **Purpose**: Generates and dispatches `MotionEvent.obtain(...)` events to the underlying Activity/ViewGroup at the cursor's location.
- **Location**: `com.remotedisplay.player.gesture.SyntheticTouchDispatcher`
- **Interfaces**:
  - `dispatch(targetView: View, state: CursorState)`

---

## Data Models

```kotlin
data class CursorState(
    val x: Float,
    val y: Float,
    val isPinching: Boolean,
    val isHandPresent: Boolean,
    val confidence: Float
)

enum class ClickAction {
    NONE,
    DOWN,
    UP
}
```

---

## Error Handling Strategy

| Error Scenario | Handling | User Impact |
| --- | --- | --- |
| No camera available / USB webcam disconnected | Catch CameraX exception, log warning, invoke fallback callback | Virtual mouse silently disables; signage continues uninterrupted |
| Camera permission denied | Check `Manifest.permission.CAMERA`, request if appropriate or notify operator | Cursor does not appear, warning in diagnostic log |
| MediaPipe initialization failure (e.g. missing asset) | Log error, safe fallback without app crash | Player runs normally without gesture overlay |
| Low-power device CPU throttling | Frame skipping in `ImageAnalysis` (only process next frame when previous inference finishes) | Lower inference FPS, but cursor stays responsive and UI never locks up |

---

## Risks & Concerns

| Concern | Location | Impact | Mitigation |
| --- | --- | --- | --- |
| MediaPipe library binary size | `build.gradle.kts` | Adds ~5-10MB to the APK | Tasks-vision uses standard TFLite C++ runtime; keep model `.task` asset lightweight (`hand_landmarker.task` is ~8MB) |
| Hand jitter when clicking | `CursorGestureProcessor` | Pinch movement might displace cursor right before click | Lock cursor position during the transition into pinch down |
| TV Box USB camera compatibility | `HandTrackingCameraManager` | Camera2 might fail on legacy TV Boxes | Fallback camera selector that iterates all Camera IDs including external USB cameras |

