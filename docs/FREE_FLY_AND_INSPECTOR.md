# Free-fly and Inspector handoff

Ctrl+left-click on a focused 3D or World image enters free-fly and captures the native cursor. The entry click is consumed once. It cannot also submit a World pick. Captured flight uses yaw/pitch, horizontal WASD, Space/Shift vertical movement and Escape to return to orbit without moving the camera eye.

A subsequent left-click in captured World flight picks along the center ray and immediately releases the cursor for the Inspector. The camera retains its free-fly position, orientation and navigation mode, while mouse look and movement keys are suspended. XYZ editing, source actions and trace work do not move the camera. The handoff also opens the Inspector immediately while a pick is pending or unavailable.

Flight intent and cursor capture have separate states: Orbit, Captured, Inspector and Paused. Inspector closure resumes capture only if the same image is visible, its Visualizers window is focused, the native application window is focused, and no text input or popup owns input. Otherwise it stays paused. Ctrl+click in the image explicitly resumes it. Returning focus or returning to a tab alone never resumes a paused capture. Escape exits paused flight only from an eligible viewport, leaving another window's Escape handling alone.

Changing tabs, hiding/collapsing the viewport, switching to a nonflight image, losing application/window focus or opening a popup pauses capture. A different viewport can capture flight only after pausing the previous owner. Viewports closure returns every camera to orbit and releases the native cursor. Application shortcuts use captured flight as their exclusion scope; a paused Inspector can use ordinary text and application commands.

Navigation state stays outside project JSON and Undo history. This batch changes no engine API, generation, project schema or archive semantics. Automated and native checks provide scoped input/cursor evidence; complete physical-input, DPI, hardware and GUI acceptance remain separate.


[Native viewport input ownership](NATIVE_VIEWPORT_INPUT.md) subsequently routes focus and cursor capture through the window hosting Visualizers, with prior-mode restoration and safe lease cleanup. This development extension and its deferred definitions remain uncompiled/untested under the owner testing pause; actual detached-layout acceptance remains pending.


[Raw Inspector coordinates](INSPECTOR_RAW_COORDINATES.md) subsequently retain incomplete XYZ text and require complete exact parsing before Trace or Bookmark actions. This development extension adds raw-input publication ownership and explicit traced-coordinate restoration; implementation and deferred definitions remain uncompiled/untested under the owner testing pause.
