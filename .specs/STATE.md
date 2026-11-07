# Project State & Decision Log

## Architecture Decisions

| ID | Date | Decision | Rationale | Status |
|---|---|---|---|---|
| AD-001 | 2026-11-07 | Use MediaPipe Tasks Vision (Hand Landmarker) on Android | Lighter weight than full Pose for TV Box / mobile CPU/GPU, direct 21 hand landmarks for pointer + pinch detection. | Proposed |
| AD-002 | 2026-11-07 | CameraX ImageAnalysis pipeline with Front/USB camera support | Native Android standard for camera feeds, easily switchable between front camera and external UVC/USB webcam. | Proposed |
| AD-003 | 2026-11-07 | Virtual Mouse Overlay within Player View hierarchy | Renders cursor on screen and injects MotionEvents directly to the player view without requiring system-level Accessibility Service permissions. | Proposed |

## Active Feature
- **Feature**: `hand-mouse-controller`
- **Phase**: Specify -> Design -> Tasks -> Execute
- **Status**: Specify completed, awaiting confirmation / executing Design

