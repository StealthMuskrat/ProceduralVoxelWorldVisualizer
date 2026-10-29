# Main-window placement and recovery

Status: implemented on the development branch, **uncompiled and untested**. This extends the prior [workspace lifecycle batch](WORKSPACE_LIFECYCLE.md). The owner testing pause remains in force; no build, native scenario, schema validator, independent checker or GitHub workflow has run for this work. No acceptance gate or historical test count is advanced.

## Persisted geometry

User Config format 1 adds optional Workspace `x`, `y` and `maximized` fields. Coordinates are GLFW screen coordinates for the client area, not framebuffer pixels and not coordinates multiplied by UI scale. X/Y may both be missing/null for historical files; an explicit position requires both signed 32-bit integers. Missing maximized means false. Existing width/height continue to mean the last normal client size, with a 400 x 300 minimum. Strict reads reject partial positions, integer overflow/coercion, null sizes and non-Boolean maximized values. The schema/generator, default fixture and valid/invalid placement fixtures are updated together. Reads do not rewrite files.

`WindowPlacement` is a pure geometry policy. Current native frame extents reserve room for the title bar and borders inside a monitor work area. Monitor selection uses greatest normal-window/frame intersection; ties retain primary-first inventory order. An offscreen rectangle with no intersection uses the smallest Manhattan rectangle gap, again with stable ties. Missing historical positions are centered on the primary work area, including a nonzero or negative monitor origin. Current work areas are read again on startup, so a saved monitor handle or obsolete DPI factor is never reused.

Normal size is reduced to available work area after reserving current frame extents; an otherwise valid saved position is clamped within that area. Integer edge/area arithmetic is widened before addition/multiplication. Very small monitors retain the 400 x 300 minimum and anchor the framed top-left; fitting every control on a monitor smaller than that minimum is not promised. A temporarily unavailable startup inventory has an explicit 1440 x 900 fallback area. The initial native allocation is separately capped to that bootstrap size before monitor information is available, so a stored huge normal size is not passed directly into initial window creation.

## Main-window lifecycle

`GlfwMainWindow` adapts the real main GLFW handle, normal position/size, iconified/maximized attributes, frame extents and primary-first current work areas. It reuses native argument arrays and retains no monitor pointer, callback or framebuffer-derived geometry. Attribute changes detected across a position/size sample discard that unstable sample.

`MainWindowPlacement` restores the normal fitted bounds first and then requests maximization when saved. Samples taken during its own native changes cannot capture an intermediate restore. An asynchronous maximize request retains its saved intent for up to two seconds until a maximized sample arrives; a backend that never acknowledges it eventually returns to observed normal state. This is a bounded observation policy, not repeated maximize requests.

Normal captures update all four rectangle values. Maximized captures update mode without replacing normal bounds; iconified, zero-size or unstable samples do not replace the last non-minimized state. Subminimum sizes do not replace normal bounds. Shutdown takes one last sample before preference persistence. Geometry changes remain User Config only and do not dirty the project, add history, publish private Properties input, replace the engine or start profiling.

Once per second while not iconified, the owner compares current work areas with its last inventory. On topology/work-area changes, a remembered rectangle whose title-bar segment is no longer reachable is fitted and restored, retaining the saved maximized intent. A usable title bar means at least 160 screen-coordinate units, or the entire narrower work area, with its native top frame vertically reachable on one work area. Empty transient inventories are ignored. Recovery waits while the application is minimized, so a monitor change does not unminimize it; returning to a visible state checks the pending inventory change. An unchanged topology does not repeatedly reposition user drags.

View > Center Main Window and the same Workspace settings button restore normal mode and center its fitted normal size on the primary monitor. This preserves dock INI, tool flags and private editors. Interactive/topology failures reach the shared Error Center as `WINDOW_PLACEMENT_FAILED`; successful native operations publish the requested fitted preferences only after command completion.

## Frame ownership

The pinned [imgui-java 1.89.0 Window implementation](https://github.com/SpaiR/imgui-java/blob/v1.89.0/imgui-app/src/main/java/imgui/app/Window.java) installs a native resize callback that directly invokes `runFrame`. Main-window restoration can invoke that callback before `preRun` has constructed the panels, and event polling during an editor frame can invoke it recursively. `EditorFrameGate`, used by Desktop's `runFrame` override, refuses frames until panel initialization finishes and while another frame is active. Its finally block releases ownership after an exception. The normal application loop renders the next ready frame.

Placement is applied before the first editor frame. The pinned framework can present a cleared native buffer during its earlier initialization; complete no-intermediate-startup-UI acceptance remains separate. This batch does not replace the framework's window/GL bootstrap or event backend.

## Deferred definitions and remaining scope

Twenty-three regression definitions cover analytical primary/negative/overlap/nearest-monitor vectors, frame-aware fitting, minimum/fallback/extreme coordinates, title-bar reachability, strict/historical preference shapes, native command ordering, normal/maximized/minimized sampling, bounded polling, minimized topology deferral, asynchronous maximize acknowledgement/fallback, explicit centering, failed-command retry, user/project/engine separation and startup/reentrant/failed frame gates.

`WindowPlacementProbe` defines actual normal move/resize sampling and saved geometry restoration into a fresh GLFW window, plus real mouse input on the production Workspace Center button in an isolated ImGui context. It retains docking, visibility and a private resource ID/raw seed/project/history. Native windows use GLFW_NO_API without a renderer. Its report explicitly sets native maximize, minimize and monitor-removal verification to false; service fixtures do not certify these physical window-manager actions. It recreates the native window in one process rather than launching the full packaged editor twice.

The independent Python definition verifies actual persisted screen-coordinate integers, frame/work-area containment, primary centering, fresh-window persistence, preference schema/shortcuts and temporary cleanup. Future Linux/Xvfb and Windows workflow definitions include the scenario/artifacts with development trigger exclusions unchanged.

Physical maximize/restore/minimize sequencing, real monitor removal/reconnection, taskbar/work-area changes, mixed-DPI movement, Wayland/macOS capabilities, full startup, detached ImGui windows and Windows 11 hardware acceptance remain pending. Frame extents use current native units, but font scaling/per-monitor DPI adaptation, arbitrary popup/dialog work-area clamping and detached-window recovery are not implemented by this main-window policy. Existing global/per-window UI scaling behavior is unchanged.
