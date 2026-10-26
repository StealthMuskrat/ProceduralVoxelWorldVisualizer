# 2D popup and window-focus input guards

Validated implementation: `619fa93204d94d30333173f76ee6e6d31ce3866a`.

[Actions run 40419216613](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40419216613) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. The required local command was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 722 tests across 90 suites with zero failures, errors or skips; see [JUnit summary](image-guard-junit.json). Native event coverage expands rather than adding duplicate mathematical tests.

The shared production handler checks any open ImGui popup and root/child window focus before processing capture or wheel input. When superseded, it drops capture and preserves the current pose. Closing a popup or returning focus cannot resume a still-held drag; navigation requires a fresh press.

The native probe opens a real ImGui popup during a held pan, moves the pointer and injects wheel input, closes the popup, and checks that held movement remains blocked. It then starts a fresh pan, focuses a second ImGui window, moves while held, returns focus and confirms no resumed movement. Fresh drags recover after both interruptions. Native assertions check unchanged pose and work counts during blocking, plus unchanged authored project/revision/history. See [raw format-3 observations](image-guard-native.json). Python independently checks recorded popup-before/after and focus-before/after equality alongside all earlier cursor-anchor and outside-pan equations. IMAGE_GUARD_PYTHON_PASS identifies the added checks.

Both platform workflows run the same probe with existing native GL/editor/bundled/packaged smokes, exact image digests, engine-only replay and reproducibility checks. This is ImGui popup/window-focus evidence, not OS application activation, physical mouse capture, arbitrary popup/docking/DPI transitions or Windows 11 hardware certification. Complete A27 and production sign-off remain separate.
