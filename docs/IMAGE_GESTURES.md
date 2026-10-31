# Image gesture ownership and context clicks

The 2D noise image owns its left press and original navigation/pointer coordinates. It also captures the image width/height at that press: resizing the viewport during a drag cannot reinterpret the same pointer displacement or jump the requested center. The final release-frame displacement is applied. A sticky three-pixel threshold distinguishes drags from short clicks, including drags that return to their starting point.

Only an owned short left release inside the image can replace the picked bookmark coordinate. Foreign, canceled, collapsed, long or dragged releases preserve the previous picked coordinate. Escape restores the original request; popup/window/application focus loss, source changes, hidden/collapsed viewports and external navigation drop ownership without later adopting a held button. Any held button suppresses the 2D wheel, and a right-held context gesture cannot also start a left/middle pan.

2D noise and orthogonal slice images use the same owned right-click classifier. A press must begin on that eligible image and release inside within 0.35 seconds without ever exceeding three pixels of Euclidean displacement. Long presses, out-and-back drags, foreign releases, focus/popup/tab changes and competing left/middle buttons cannot open a context menu or replace the picked coordinate. A fresh short right click captures its coordinate from the accepted image, then opens existing context actions. Escape closes the menu.

All image surfaces own ImGui items so navigation cannot move their containing window. A fresh image press explicitly transfers Visualizers focus. Production 2D navigation uses the same application/window/popup/text eligibility guard as other viewport input. Hover and bookmark pointers normalize in double precision against the accepted 2D snapshot; requested navigation and manual preview retention cannot relabel the displayed field. Hover remains a continuous procedural evaluation, not GPU pixel readback.

These changes do not alter project data, Undo history, field generation, engine API, schemas or archives. The existing 2D row-cache and shared-handler probes remain in place. Production native GL/ImGui fixtures add controlled resizing/collapse, final-release movement, bookmark retention and shared image-menu checks, with independent affine geometry. Physical input, OS focus/DPI, full GUI and Windows 11 hardware acceptance remain separate.


[Native viewport input ownership](NATIVE_VIEWPORT_INPUT.md) subsequently uses the hosting ImGui viewport's native window for eligibility and drops gesture ownership when that host changes. This development extension remains uncompiled/untested under the owner testing pause.

The unvalidated [visualizer command follow-up](VIEWPORT_COMMANDS.md) retains short-right-click admission and accepted coordinate capture while adding bounded scrolling context menus, complete source/Escape arbitration and post-popup command callbacks. Four regression definitions, thirty-six native reflow cases and independent route/persistence replay remain deferred; actual image/camera/rendering acceptance remains separate.
