# Coarser display sampling for scalar volumes

Status: **implementation awaiting validation** on `codex/development-phase`. The owner has paused GitHub testing until development is complete. This source batch and its new/updated verification definitions have not been compiled or executed. It adds no passing evidence to the last validated main baseline.

Camera-relative Volume now reduces display sampling before reducing visible world extent under RenderPressure. Level 0 retains the unit cell-center grid. Level 1 uses two world units between sample centers, retaining the configured whole-chunk extent. Levels 2–3 keep this spacing while reducing extent and then releasing the optional cache. Deferral, critical pause, explicit Resume and gradual restoration retain their existing ownership rules. Fixed analysis stays on the unit grid.

A physical 64-unit window therefore has 64³ texels at full detail and 32³ texels at the first fallback. Texture bytes and sample/encoding staging fall by a factor of eight for that same physical window. Working admission includes the actual sample array, encoded array, one spacing-specific tile and bounded GPU staging. Configured limits and preferences are never rewritten. Preview dimension/cell limits constrain the actual display sample grid; physical camera-relative extent stays separately bounded to 32..256 world units.

## Sampling and cache contract

A coarse sample queries the same immutable canonical scalar field at `origin + (i + 0.5)*spacing`. This is point sampling at coarse-cell centers, not a low-pass filter, average of authoritative voxels or change to the procedural program. High-frequency features can alias or disappear at coarse quality. Exact world generation, engine sampling and archives remain independent of this display choice.

`VolumeData.size()` counts texture samples. `sampleStep()` is the spacing in world units and `extent()` is their product. Existing constructors/sample methods retain spacing 1 and preserve their historical floating-point addition order at large fixed-analysis origins. `sampleGrid` explicitly accepts power-of-two spacing 1, 2, 4, 8 or 16, and local interpolation consumes world coordinates divided by spacing. Camera-relative pressure currently selects only 1 or 2; the wider primitive vocabulary allows bounded display consumers without changing persistence. Direct access `value(x,y,z)` still addresses sample indices.

VolumeWindows keeps physical 32-unit chunks and caches immutable double tiles keyed by source generation, global chunk origin and spacing. A spacing-2 tile is 16³ values, rather than 32³. Each entry is charged its own actual array-plus-entry allowance. Mixed levels share the same LRU budget and compiled source; coarse tiles never substitute for unit tiles. Returning to full detail can reuse retained full-detail tiles. Serialized allocation, owned cancellation, complete-tile caching and complete-window publication remain required. Spacing changes participate in the request tag without changing the source generation or adopting a manual authored edit.

## Rendering and GPU overlap

Resident metadata retains sample count and physical spacing. The ray box, view-step lengths and optional self-shadow light lengths use physical world extent. Density lookups still normalize coordinates by that extent; opacity remains per world unit. A constant field must therefore have the same optical result and geometry across detail levels. CPU interpolation and light references follow the same coordinate convention.

GPU overlap copies require the same explicit source identity, encoding map, spacing and center-grid phase. Equal spacing alone is insufficient: origin differences on every axis must be whole multiples of that spacing. Eligible overlap and copy offsets are computed in texel coordinates using long world-coordinate differences. Refinement, coarsening or a phase mismatch uses fresh host samples. Both copied and host-written destination bytes share the existing upload budget; no interpolation blit invents missing fine detail. Region bounds validate the physical extent, including signed coordinate edges.

Accepted origin, extent, encoding and spacing remain unchanged until a complete candidate publishes. Rejected/cancelled replacements retain the prior texture and its metadata. The toolbar distinguishes target quality from accepted quality while they differ. There is still a complete replacement texture and a bounded moving window, not sparse GPU residency.

`VolumeStreamingLimits.Plan`, `VolumeWindows.Statistics`, `VolumeTextureUpload.Region` and `Resident` gain spacing components; historical constructors remain with unit defaults (the four-argument Resident derives spacing from an explicit Region). Record-pattern/reflection users must account for the added components. Project/User Config formats and schemas are unchanged; this automatic quality choice is not a new persisted setting.

## Deferred verification

Ten new grid contracts define direct arithmetic sample locations, world interpolation/clamps, homogeneous light distance, mixed-level cache/source ownership, actual-byte eviction, cancellation, signed domain boundaries, admission before extent reduction, extreme finite encodings and historical defaults. Five new upload contracts define spacing/phase copy gates, actual texel storage budgets, partial/cancelled publication, region rejection and host fallback.

VolumeCoarseProbe defines normal and strict OpenGL 3.0 scenarios for signed affine grids, compatible half-window copies, phase mismatch, detail changes, partial metadata retention, exact texture readback and complete framebuffer parity against full host uploads. Homogeneous unit/spacing-2/spacing-4 frames must match with lighting both off and on. The independent Python consumer checks every sample location, copy split, frame hash and physical Beer–Lambert/light-box calculations. Existing pressure definitions are updated to expect coarse sampling before extent reduction, CPU recovery at level 1, restoration of unit detail, cancellation, explicit Resume and unchanged project/preferences. None of these definitions has run in this batch.

Representative throughput, adaptive/distance-varying scalar detail, anti-alias filtering, sparse volume residency and the full physical GUI/DPI/Windows 11 hardware matrix remain separate work.
