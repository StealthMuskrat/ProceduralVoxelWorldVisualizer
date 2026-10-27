# Fixed-preview picking and bounded integer traversal

Validated implementation: `d7800e5da8bbd76a7d3c70520a25f71ace2827a2`.

[Actions run 40507623190](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40507623190) passed Linux, Windows and reproducibility jobs with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. All Gradle test tasks, existing native probes, packaged launcher and cross-platform JAR hash comparison passed. Eight new `VoxelRaycastBoundsTest` methods are included. Raw XML and native reports are retained in the [Linux evidence artifact](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40507623190/artifacts/12735687470); totals were not recounted after the workspace disconnected. See [observed CI results](fixed-picking-ci.json).

Required local Gradle execution was attempted but the Java launcher lacks `libjli.so` (exit 127). Local schema families/fixtures and `git diff --check` passed before upload. The workspace subsequently disconnected; final evidence and publication used the already validated repository branch.

A solid procedural world previously allowed a fixed-preview ray to hit an occupied cell near the camera, outside the displayed mesh. Fixed World picking now captures immutable inclusive bounds belonging to the accepted mesh and treats every other cell as EMPTY without calling the world evaluator. Streaming traversal retains its previous sampling scope. Existing Before, After and Only Changes/removal-ghost predicates run inside the same restriction. The readiness and asynchronous trace revision/request gates remain in place.

Fixed voxel, point and isosurface generation now floors the requested lower corner before integer sampling and mesh publication. This aligns displayed geometry with the actual sampled cells for fractional centers and odd extents. Invalid/out-of-domain or overflowing cubes fail before region allocation/generation; the last valid preview is retained through the existing failure path. Scalar volume/slice positioning is unchanged.

DDA normalizes finite nonzero directions after scaling by their largest absolute component, avoiding squared-length underflow/overflow. Out-of-domain origins reject before any cell query. A tied boundary crossing that would leave signed integer cell space stops traversal instead of wrapping. Existing inclusive distance, initial-cell and tied-axis traversal contracts remain.

The eight new tests provide independent expectations:

- A solid world outside a displayed [-8,7] cube hits x=7 or x=-8 depending on ray direction; only the hit cells invoke the evaluator.
- Misses issue no out-of-region evaluator queries, while an inside ray at distance zero selects its current negative cell.
- Equal direction components at Double.MIN_VALUE, 1 and Double.MAX_VALUE hit (2,2,2); axis rays preserve the finite distance limit.
- Origins outside signed block-cell space reject without evaluating cells.
- Outward rays at both integer extremes stop after the boundary cell.
- Before-like, After-like and removal-ghost-like predicates preserve their expected first occupied cell inside the box.
- Fractional lower corner (-2.75,0.25,1.5) and extent 3 produce inclusive bounds (-3,0,1)..(-1,2,3), preserve the caller vector and pick the expected mesh boundary.
- Cube endpoints, invalid side/order/fractional exact origins and endpoint overflow are checked.

Existing native preview/recovery/camera probes passed on both platforms, including VISIBLE_FALLBACK_CAMERA_PASS and STREAM_RENDERER_FALLBACK_PASS. These are regressions of the existing fixtures; this batch adds no native World-click fixture. Streamed picking against unloaded/display-LOD regions, physical input, arbitrary camera/mesh/DPI/load cases, full A01–A36 acceptance and Windows 11 hardware remain separate.
