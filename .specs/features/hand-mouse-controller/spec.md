# Hand Mouse Controller Specification

## Problem Statement

Users interacting with Android TV Boxes or smartphone displays in digital signage, kiosks, or interactive presentations lack touchscreens or convenient physical mice. By leveraging the built-in front camera (on smartphones) or connected USB webcams (on Android TV Boxes), users can point and interact with screen elements using natural hand movements and gestures as a virtual mouse.

## Goals

- [ ] Provide real-time hand tracking at >= 20 FPS on mobile and >= 10 FPS on entry TV Boxes.
- [ ] Map index fingertip movement smoothly to screen (X, Y) cursor coordinates without jitter.
- [ ] Detect pinch gesture (index finger + thumb touch) to trigger mouse click down and release up (`ACTION_DOWN` / `ACTION_UP`).
- [ ] Provide an on-screen cursor overlay visualizing current pointer position and click state.

## Out of Scope

Explicitly excluded to prevent scope creep.

| Feature | Reason |
| --- | --- |
| System-wide OS background accessibility service outside ScreenTinker app | Requires manual OS-level permission granting and complex permission hurdles on TV Boxes. Mouse will operate on top of the ScreenTinker player window. |
| Multi-touch or two-hand pinch zoom | Focus on single-hand virtual mouse cursor and click for MVP. |
| Complex sign language / multi-finger custom gestures | Adds inference overhead for low-end TV Boxes; keep simple pinch/click. |

---

## Assumptions & Open Questions

Every ambiguity is resolved or recorded here.

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --- | --- | --- | --- |
| Camera source | Front-facing camera on phones; external USB camera fallback on TV Box | Front camera allows natural mirror interaction for users facing the screen | Yes |
| Coordinate Mirroring | Horizontal flip (mirror) enabled by default | Moving physical hand right moves screen cursor right (mirror mode) | Yes |
| Click Trigger | Pinch gesture (distance between landmark 4 - Thumb Tip and landmark 8 - Index Tip < threshold) | Standard, intuitive gesture for virtual mouse point-and-click | Yes |
| Cursor jitter reduction | Exponential Moving Average (EMA) / low-pass filter | Prevents camera noise from causing erratic cursor shaking | Yes |

**Open questions:** none — all resolved or logged above.

---

## User Stories

### P1: Hand Tracking & Virtual Mouse Cursor ⭐ MVP

**User Story**: As a user standing in front of an Android device or TV Box, I want my hand movement to control an on-screen mouse cursor so that I can point to content without a physical mouse.

**Why P1**: Core functionality needed for touchless interaction.

**Acceptance Criteria**:

1. WHEN camera starts and a hand is visible THEN system SHALL detect hand landmarks within <= 50ms per frame.
2. WHEN the user moves their index finger tip across the camera frame THEN system SHALL smoothly update the virtual cursor position across screen width and height.
3. WHEN the hand leaves the camera frame THEN system SHALL gracefully fade out or hide the virtual cursor.

**Independent Test**: Can demo by launching camera preview, moving hand, and observing the on-screen cursor tracker mirroring the motion.

---

### P2: Gesture Click (Pinch to Click)

**User Story**: As a user controlling the cursor, I want to pinch my index finger and thumb together so that I can click buttons and interact with on-screen content.

**Why P2**: Enables meaningful interaction (clicking, activating buttons, triggering actions) beyond passive hovering.

**Acceptance Criteria**:

1. WHEN index finger tip (landmark 8) and thumb tip (landmark 4) come within pinch distance threshold THEN system SHALL trigger mouse down / click state (`ACTION_DOWN`) and visually highlight the cursor.
2. WHEN fingers separate after pinch THEN system SHALL trigger mouse up (`ACTION_UP`) and dispatch touch/click to the underlying view hierarchy at cursor (X, Y).
3. WHEN hand is unstable or jittery THEN smoothing algorithm SHALL prevent accidental double clicks or position jumps during pinch.

**Independent Test**: Can demo by positioning cursor over an on-screen button, performing pinch gesture, and verifying that the button's click listener fires.

---

### P3: Performance & TV Box Optimization

**User Story**: As an operator deploying on budget Android TV Boxes, I want configurable inference rate and fallback options so that the device CPU does not overheat or drop video playback frames.

**Why P3**: Essential for long-running digital signage stability on lower-end hardware.

**Acceptance Criteria**:

1. WHEN running on low-resource hardware THEN system SHALL allow throttling inference (e.g. 15 FPS) while maintaining smooth cursor interpolation.
2. WHEN camera error or disconnect occurs THEN system SHALL report status without crashing the player.

---

## Edge Cases

- WHEN hand enters or exits frame abruptly THEN system SHALL not jump cursor to (0,0); instead, smoothly disappear cursor after brief timeout.
- WHEN lighting is low or hand is partially obscured THEN system SHALL ignore low-confidence landmark detections (< 0.5 confidence).
- WHEN multiple hands appear in the frame THEN system SHALL lock onto the dominant/first detected hand to avoid cursor flickering between users.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| --- | --- | --- | --- |
| HAND-01 | P1: Hand Tracking & Cursor | Design | In Design |
| HAND-02 | P1: Smoothing & Coordinate Mapping | Design | In Design |
| HAND-03 | P1: Virtual Cursor Overlay | Design | In Design |
| HAND-04 | P2: Pinch Click Gesture | Design | In Design |
| HAND-05 | P2: Event Dispatching to Views | Design | In Design |
| HAND-06 | P3: Performance & Throttling | Design | In Design |

---

## Success Criteria

- [ ] Cursor follows hand movement smoothly with low latency (< 100ms end-to-end).
- [ ] Pinch gesture reliably triggers clicks with > 90% accuracy in standard room lighting.
- [ ] No crash on camera disconnect or permission rejection.

