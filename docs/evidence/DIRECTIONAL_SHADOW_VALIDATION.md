# Bounded directional mesh shadows

Implementation: `df9f7ae98facd8ccbd9a8748c51346ef50f1e33c`.

[Source run 40689036022](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40689036022) passed the full pinned Linux and Windows builds/native/reference families, Windows packaging/launcher and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1. Raw Linux XML contains **922 tests across 120 suites, zero failures/errors/skips**; see [summary](directional-shadow-junit.json). The final follow-up changes documentation/reports only.

Twelve new contracts cover bounded depth-map admission and preserved geometry capacity, invalid settings/bounds, empty/point/nonfinite meshes, root-256 bounds, all eight light-frustum corners for flat/signed/degenerate regions, independent light-ray/depth ordering, immutable deterministic matrices, sub-texel snapping, resolution/bias relationships, historical preference defaults, strict type/resolution rejection, exact preference restart and unchanged authored document/history. The generated user schema now participates in canonical drift checks; historical fixtures and explicit positive/negative shadow fixtures remain independently validated.

Ordinary Mesa OpenGL 4.5 and actual strict OpenGL 3.0 native probes record SHADOW_NATIVE_PASS / SHADOW_PASS. A top-down camera sees a ground plane, while a floating box outside the camera frustum blocks the existing light direction `(0.4,1,0.3)`. Independent Python intersects receiver-to-light rays with the declared box and computes ambient/directional/fog colors. Fixed and streamed meshes match the analytical interior samples:

| State | Occluded sample RGB | Unoccluded sample RGB |
|---|---|---|
| Shadows disabled / incomplete caster / removed caster | 238, 238, 238 | 238, 238, 238 |
| Complete opaque caster | 89, 89, 89 | 238, 238, 238 |
| Complete caster plus distance fog | 70, 71, 73 | 180, 181, 183 |
| Unlit | 255, 255, 255 | 255, 255, 255 |

These are software-GL functional samples rather than performance measurements. Fixed and streamed maps fit different conservative bounds, so their filtered boundary pixels need not be identical. All ten corresponding normal/strict-context framebuffer hashes match; see [normal report](directional-shadow-native.json) and [strict report](directional-shadow-gl30-native.json).

The streamed fixture combines half-cell voxel positions with 1/128-block surface positions. A 48-byte incomplete upload does not cast a shadow; completed publication adds the off-camera caster while camera draw/picking metadata still contains only the visible ground. Indirect/fallback frames match exactly. Every compaction step preserves the full shadowed framebuffer and camera version/detail metadata: **1,152 bytes** move over **six advances**, at most **192 bytes per advance**. Removing the caster restores the original unshadowed framebuffer. Translation to camera-relative origin `(-536870944,-288,-536871008)` retains the complete shadowed image byte for byte, beyond exact float integer world coordinates.

Native resource tests force color framebuffer resize with a caller-owned texture on unit 3, then check binding restoration and actual occlusion pixels. A separate depth callback deliberately throws while split read/draw FBOs, a pixel-unpack buffer, foreign texture/VAO/generic attribute, viewport and depth/raster states are installed. Their scoped states survive; real driver texture/framebuffer objects disappear after close. Unlit, image/volume/points, explicit disable and insufficient fixed-mesh budget reclaim or avoid depth allocation.

Production Viewports exercise an 8 MiB configured GPU budget:

| Requested maximum | Effective map | Vertex arena | Depth map |
|---|---:|---:|---:|
| 2048 | 1024 | 4 MiB | 4 MiB |
| 512 | 512 | 7 MiB | 1 MiB |
| Disabled | none | 8 MiB | 0 |

Quality changes, explicit retry and disable/reupload retain the source epoch/submission stamp and project/revision/history. The saved User Config preserves requested quality and enablement; the saved project contains no shadow settings. The actual Profiler panel draws, its separate depth-allocation gauge records admitted bytes, and JSON/CSV exports pass independent bounds/statistics/percentile replay. These backing-control fixtures do not certify every physical settings gesture or DPI combination.

[Linux evidence artifact](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40689036022/artifacts/12816890644) retains XML, normal/strict raw RGBA frames, reports, production profile/preference/project exports and the complete preceding native evidence family. Downloaded full World and strict shadow replay pass locally. Native framebuffer previews were visually inspected. Previous GPU timing, scalar surface LOD, volume pressure/overlap/encoding, picking, authoring, source vectors, schemas, exact voxel hashes and isolated engine consumer remain passing.

The headless engine JAR is byte-identical to the preceding main build `7c4e58a62479b32f4abcded5f3594a744045e9e2`, SHA-256 `0d4466a385ca1256c7662157f3773e50e9d8451b7a8ca64c5c5d0fc6621b80a0`; see [artifact comparison](directional-shadow-engine-invariance.json). Engine/model/archive code and public procedural APIs gain no native dependency or numerical change.

Initial source run 40688611675 passed platform builds/contracts before being superseded. Review then identified color framebuffer resize on a caller's active texture unit 3 as a way to overwrite shadow sampling. The corrected path selects texture unit 0 for color allocation and restores the caller's unit/binding; the updated native test forces that resize. No pixel tolerance, budget, availability or publication rule was relaxed.

Required local Gradle was attempted but cannot load libjli.so (exit 127). Formatting, schema fixtures, Python syntax, diff checks, raw engine-hash comparison and downloaded native replay passed; pinned CI supplies compiler/native evidence. See [settings, scope, allocation and API contracts](../DIRECTIONAL_SHADOWS.md).

This is one local depth map over complete resident triangle geometry, with PCF/bias and conservative memory accounting. Missing/unloaded geometry does not cast shadows. Triangle occluders are opaque; volume self-shadowing, alpha-aware transmission, cascades, arbitrary device-reset recovery, representative hardware performance/stress, full GUI/DPI and Windows 11 hardware acceptance remain separate. A28/A31 stay PARTIAL.
