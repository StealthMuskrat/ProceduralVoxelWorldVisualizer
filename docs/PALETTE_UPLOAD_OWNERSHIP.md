# Palette upload ownership and retry — unvalidated

W06-C06 covers GPU palette updates without geometry rebuild in 17.1.5 and render-thread state ownership in 1.4.1. Inspection of the preview acceptance path found that GlViewport.setPalette stored lastPalette and paletteWidth before preparing or uploading the GPU data. A failed attempt could therefore suppress an identical retry. The write also inherited a bound pixel-unpack buffer, strides, skips and byte swapping, unlike the volume transfer path.

The private PaletteTextureUpload owner now validates layout and prepares an owned color snapshot plus padded direct pixels before backend mutation. Width is the smaller of the color count and device limit; row count uses signed-long arithmetic. Empty palettes, invalid device limits, excess texture height and padded byte capacity beyond an int-indexed direct buffer reject before backend write. Null palettes retain NullPointerException. The backend receives a read-only direct IntBuffer; source array mutation and buffer consumption cannot alter the retained cache. Packed color words keep their order, with zero words filling the final row.

Only a normally returned backend write publishes the cache and lookup width. A RuntimeException/Error invalidates cache eligibility and propagates unchanged. A backend may already have changed pixels before throwing, so even a request for the former colors must write again. Preparation failures before backend invocation leave an existing cache intact. Successful equal-color requests with the same layout continue to skip uploads; a changed width requires repacking. Scene and fixed-mesh shaders read the successfully published lookup width from the same owner.

GlViewport's backend saves the current 2D binding, unpack-buffer binding, alignment, swapping, row/image strides and XYZ skips. It unbinds the PBO, normalizes unpack state, uploads RGBA8 pixels and sets nearest filtering, then checks glGetError. A finally block restores the captured bindings and unpack values on normal or exceptional exit; the active texture unit is never changed by the write. Preparation completes before those bindings change.

This preserves retry eligibility rather than providing GPU rollback. Palette writes still update the existing texture in place. A failed backend call may have changed physical pixels, while lookup width remains at its last successful value; callers must handle the failure and explicitly retry. No old-image retention, transactional preview acceptance, driver recovery or exception-proof restoration guarantee is added. glGetError can also observe a previously queued context error, so its diagnostic is not proof that allocation itself failed. Existing palette textures remain outside scalar-volume/vertex-arena reservations; no budget or profiler semantics change.

## Deferred definitions

Four PaletteTextureUploadTest definitions cover:

- Eleven explicit layout expectations and nine rejection controls, including row padding, both texture axes and large descriptor-only byte/overflow boundaries without large allocation.
- Exact packed channel words, padding and multirow mapping, read-only direct buffers, immutable caller ownership, equal-color skips and changed-width repacking.
- RuntimeException/Error before or after backend storage, two identical failed retries, explicit restoration of old colors, later successful replacement, once-published width and unchanged fault identity without host text reads.
- Null, empty, invalid-device and over-capacity requests leaving accepted data/cache and backend call counts unchanged.

VolumeUploadProbe additionally defines palette writes under a bound PBO, nondefault unpack values and a foreign 2D binding on texture unit 3. Two controlled queued GL errors must each produce the palette diagnostic for the same request, followed by a successful identical retry; each attempt must restore all captured state. This deliberately queues an invalid-enum error for the completion check while a real write runs; it does not simulate a driver allocation failure. The original red palette is restored before the existing analytical volume/pixel checks, which retain their exact expectations. New receipt fields record two queued-error attempts and restored state; verify_world_picking.py now requires them while retaining its texel/byte/optics/metadata/project assertions. No new report exists, and older historical reports do not contain these fields.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, Python/checker, package, update request or workflow ran. Source review and Git whitespace/content checks add no runtime evidence. The observed baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Source stays on local main with publication pending.

Public signatures, packed colors, UUID/source palette mapping, geometry, exact generation/math, headless dependencies, schemas, registries and authored formats remain unchanged. Private preparation adds no measured memory/latency claim. Wider acceptance/profiling/reporting failures, actual GL failure effects and Windows 11/resource/performance validation remain open. In particular, volumeUploadFailed still calls backend cancellation before clearing its UI pending/active state; that caller settlement boundary requires a separate source fix so a cancellation fault cannot strand the old UI owner.
