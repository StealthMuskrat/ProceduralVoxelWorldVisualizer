# Searchable viewport generator selection validation

Validated implementation: `b2e0b73fa1e68653caebbe4bff189f83dbb5fe71`.

[Actions run 40425884496](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40425884496) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit contains 735 tests across 92 suites with zero failures, errors or skips; see [JUnit summary](viewport-picker-junit.json). The new ResourceChoicesTest case checks exact dimension catalogs, rejection of foreign-dimensional UUIDs, UUID preservation across rename, missing UUID rejection after deletion and nullable selection.

Both noise tabs now use ResourcePicker with dimension-scoped choices and independent ImGui widget IDs, reusing the existing cached searchable/clipped selector. Same-UUID callbacks do not dirty a preview. Explicit selection clears the old picked bookmark and pan capture, then marks only that view. Empty and missing selections use the existing selector behavior; resource definitions/project history remain unchanged.

The native production Viewports probe builds 64 2D fields and a 3D field with the same visible ID as its target. Real mouse/text events open the picker, observe bounded unfiltered rows, filter to one compatible 2D choice without changing selection, click its UUID and await a 0.75 sampler image. The independent 3D selection stays unchanged. Reopening and Escape must close the popup while preserving selection and image. The probe then passes the real budget/full-queue retry cases with the selected field, and project JSON/revision/history stay unchanged. Python checks row bounds, UUID shape, report flags and exact final constant output. See [raw format-2 native observations](viewport-picker-native.json).

The first CI run exposed a shared picker issue: Escape was consumed by the text input, leaving the popup open and swallowing the next toolbar click. ResourcePicker now closes on Escape before drawing its input, and the probe explicitly asserts popup closure. The final run passes that recovery path.

This is native GL/ImGui 2D selector evidence. Shared dimension logic covers 3D, but this batch does not click every 3D picker gesture, clear/missing-resource flow or arbitrary catalog/DPI case. Physical OS-window/pointer, representative hardware and complete A27 acceptance remain separate.
