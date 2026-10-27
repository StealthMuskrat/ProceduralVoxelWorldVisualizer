# 2D resize/release ownership and shared image context clicks

Validated implementation: `de794a3e598a48f47a36286ddb2a3a96de0f2bbb`.

[Actions run 40555468818](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40555468818) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Both platform logs contain IMAGE_GESTURES_NATIVE_PASS and IMAGE_GESTURES_PASS. Raw Linux JUnit reports **798 tests across 103 suites, zero failures/errors/skips**; see [summary](image-gestures-junit.json). Five new unit cases cover inclusive right-click bounds, long/outside/backward-time rejection, sticky drag classification, cancellation/recovery and prevention of press restarts.

2D pans previously used current image dimensions during a held gesture and could lose movement delivered on release. Bookmark selection used an unowned release check. Pans now retain press-time dimensions and apply release-frame displacement; only owned short clicks replace picked coordinates. Collapse/application-focus handling and ImGui item ownership match other viewport surfaces. 2D hover/bookmarks use double normalization against the accepted image. 2D and slice context menus share owned short-right-click classification, with competing-button cancellation. Histogram image height stays positive in short viewports. See [interaction contract](../IMAGE_GESTURES.md).

The [raw native report](image-gestures-native.json) and [saved affine project](image-gestures-project.json) use compiled fields `x + 100*z` and `x + 10*y + 100*z`. Production GL/ImGui image/key input, controlled window resize/collapse and native ImGui focus requests verify:

- An owned 2D left click captures (-65,63) from accepted continuous geometry and the corresponding affine raw value. Hover and picked coordinates use the same double-normalized pointer.
- A pan begins with image dimensions 934x538. Resizing to 1084x588 leaves its current request unchanged. Final movement (80,40) delivered with release uses the original dimensions, producing center approximately (-21.9271948608,-19.0334572491) at spacing 1. It preserves the earlier picked coordinate.
- Foreign and Escape-canceled left releases cannot replace that coordinate. Escape restores the original request. Collapsing/restoring the viewport cancels a held pan without resuming it. A middle-held wheel cannot change 2D navigation.
- A fresh short right click opens existing context actions and captures accepted coordinates in both the 2D and Y-slice images. Escape closes each menu.
- Long right presses, out-and-back drags, foreign releases, focus-canceled releases and competing left-button input open neither menu and preserve the previously selected coordinate in both image modes.
- Manual 2D navigation retains the accepted image object and procedural submission stamp. All image gestures leave authored JSON, revision and Undo history unchanged.

Independent Python reconstructs the release pan using press dimensions, recomputes continuous 2D/slice points and affine raw values, checks bookmark floors and validates every lifecycle flag. Existing standalone 2D input, preview admission/context retry, World picking/LOD, Inspector ownership/draft/freshness, orbit, free-fly and slice fixtures remain passing.

Initial native validation found the new fixture inherited the preceding slice tab and never generated its 2D image. It now explicitly selects 2D at startup; assertions and timeout remain unchanged.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schemas/fixtures, formatting, Python syntax, diff checks and independent replay of downloaded native reports passed. Pinned CI supplies compilation, automated/native tests and packaging evidence.

This is scoped native GL/ImGui/editor evidence. Controlled resize/collapse/focus requests are not a physical OS input/window/DPI matrix, and continuous procedural hover is not a GPU pixel readback. Full GUI and Windows 11 hardware acceptance remain separate. No field generation, engine API, schema, archive or project-format changes.
