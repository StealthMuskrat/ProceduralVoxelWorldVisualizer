# Sampled calculus, neighborhood filters and distance shapes

These twelve operations extend the canonical Processing registry. Saved definitions, editor previews, the World Generator and independent engine consumers use the same compiler. There is no project-format change; processing registry version 3 records supported dimensions, result signatures, controls and formulas. Existing operation behavior is unchanged.

## Procedural calculus

GRADIENT returns a value Vector2 in X/Z order for a 2D generator, or Vector3 in X/Y/Z order for 3D. Each component is the centered derivative `(f(p+h e)-f(p-h e))/(2h)`. It is not normalized. Convert it to a Scalar explicitly, for example with Length, Dot or Component, before selecting it as final output.

LAPLACIAN returns the sum of axial centered second differences. It samples the center once and each positive/negative axis neighbor in X, Y (3D only), Z order, accumulating `(positive-center)+(negative-center)` and dividing the sum by `h*h`. Spacing must be positive and its stencil denominator finite and nonzero. No diagonal samples or implicit smoothing are used.

Both operations accept Source Field, Earlier Field Prefix or Noise Generator Field, plus optional matching Coordinates. Default locations are incoming coordinates; the selected field applies its own transforms exactly once. Their declared sample costs are `2*dimension` and `2*dimension+1`; normal compiler work/depth admission applies even when an enabled item is not the selected output.

## Neighborhood filters

MIN_FILTER, MAX_FILTER and MEDIAN_FILTER visit the full odd square/cube in Y/Z/X order, with integer radius 0..4 and positive finite spacing. The center is reused. Median sorts signed double values and selects the single middle element; there is no even-count interpolation. It owns at most 729 primitive doubles per executing thread, allocated lazily and reused. Nested operators have separate scratch; definitions cannot reference themselves.

Strength in 0..1 blends `center + (filtered-center)*strength`. Exact endpoints return center/filtered directly. Zero strength and zero radius sample only the center; conservative admission still includes the authored neighborhood. Min/max are morphological erosion/dilation of the sampled Scalar field. Values remain signed and are never clamped to 0..1. Any executed nonfinite coordinate/value fails through the existing structured boundary.

## Signed-distance primitives

All shapes return Scalar signed distance: negative inside, zero on the surface, positive outside. Default positions are transformed definition coordinates. Optional `position` must be Coordinates2D/3D matching the generator; it supplies the position directly. Center and endpoint coordinates use that same space. Hidden Y controls are ignored in 2D.

| Operation | Dimensions | Definition and controls |
|---|---|---|
| PLANE | 2D/3D | Axis-aligned plane, offset and POSITIVE/NEGATIVE normal: `sign*(p[axis]-offset)`. Y is unavailable in 2D. |
| CAPSULE | 2D/3D | Distance to the closed Start→End segment minus nonnegative radius. Coincident endpoints are a circle/sphere. |
| CYLINDER | 3D | Capped cylinder with center, axis, nonnegative radius and half height. Exact radial/axial box combination gives Euclidean distance outside corners. |
| TORUS | 3D | Ring torus with center, axis, major radius R and minor radius r: `hypot(radial-R, axial)-r`, requiring `0<=r<=R`. |
| ROUNDED_BOX | 2D/3D | Outer half extents and corner radius r. Evaluate the sharp box at extents minus r, then subtract r. Requires r no larger than any active outer half extent; rounding keeps the authored outer bounds fixed. |

SMOOTH_INTERSECTION and SMOOTH_SUBTRACTION accept Scalar inputs `a,b` and positive smoothness k. Intersection uses smooth maximum of a,b; subtraction uses a,-b. The polynomial is `max(a,b)+k/4*(1-abs(a-b)/k)^2` inside `abs(a-b)<k`, and exactly max outside. These inputs may bind earlier Scalar distance results or generator samples. They operate on current sampled distances; they do not resample neighbors. Like other smooth SDF combinations, they are useful implicit fields and do not promise exact Euclidean distance throughout the blend.

## Authoring and compatibility

Add Processing filters dimension-specific shapes and advertises the matching gradient result type. A saved incompatible active signature rejects before evaluation; the compiler never silently rewrites a saved type. The historical two-argument factory defaults to a 3D signature; the new dimension-aware factory is preferred for authoring. Disabled experimental rows retain their authored controls and do not execute.

Properties exposes only applicable controls, optional shape position, canonical field selectors and explicit sampling coordinates. Creation defaults are validated separately, baked into new items and never consulted by project evaluation. Cross-field default failures (torus radii, rounded-box radius/extents and stencil denominator) reject preference publication. Item/control UUID diagnostics, private drafts, Apply, Undo/Redo, pins and dependency navigation use the existing workflow.

Automated vectors, the native production-control workflow, engine-only consumer and generated schema/registry checks provide scoped evidence. They do not certify every Tree 6 GUI scenario, platform/DPI combination, representative hardware throughput or the full A01–A36 assignment.
