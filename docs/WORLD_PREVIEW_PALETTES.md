# Source-owned World preview palettes

Status: **uncompiled and untested** on local `main` under the owner testing pause. The validated baseline remains 922 tests / 120 suites. This extends [preview document lifetime](PREVIEW_DOCUMENT_LIFETIME.md) and the existing [streaming renderer](STREAMING_AND_LOD.md). No A01–A36 or production acceptance gate advances.

A retained World mesh contains runtime type indices from its immutable source snapshot. Those indices can differ from the current project's type order while Auto is off, a replacement is pending or a request has failed. Previously, a later color-only revision could upload the current project's palette by array position onto that older mesh. Reordering/deleting/inserting types could then paint a different type's color onto the retained geometry. A second gap allowed a result compiled before a color edit to restore stale colors when accepted, because color-only edits intentionally keep the geometry request current.

WorldPreviewPalette freezes the source project UUID, ordered voxel-type UUIDs and packed normal/removal-ghost colors. Arrays returned to callers are copies; refresh returns an immutable successor. Current colors resolve by UUID while runtime indices and the source type count stay fixed. Removal ghosts retain the source count as their offset, including when the current type count/order changes. A deleted type retains its last resolved color; reusing its semantic ID with a different UUID cannot recolor that retained type. Restoring the original UUID permits current colors again. Foreign-project refresh preserves the source palette.

Fixed Preview payloads and streaming SceneSetup handoffs now carry that immutable palette owner. On successful acceptance, Viewports resolves the latest current colors against the incoming source's order. On later authored revisions it refreshes the accepted owner even when the computational identity changed and manual generation is retaining old geometry. It no longer builds a current-order World palette for an accepted older source. Failed/queued requests and ordinary source invalidation retain the accepted palette owner; successful replacement publishes its own mapping. Open/New clears ownership through the existing document-lifetime retirement. Noise palettes and scalar sampling are unchanged.

Colors remain display-only: geometry fingerprints still exclude colors, while type-order changes retain their existing computational invalidation behavior. Palette repaint does not submit a new mesh/chunk job, change the accepted source's runtime IDs, alter exact stored voxels or create an authored edit. Canonical engine/model/storage APIs, project schema, shaders and generation semantics remain unchanged. Existing explicit save, Undo/Redo and private Properties ownership remain authoritative.

Unvalidated W06-C06 additionally gives the render-thread [palette upload cache explicit retry ownership](PALETTE_UPLOAD_OWNERSHIP.md): layout/pixels prepare before backend mutation, successful writes publish lookup width/cache, failed writes invalidate cache eligibility and GL unpack/binding state is restored. UUID resolution and source-order color/ghost mappings above retain their existing expectations. In-place GPU failure effects and transactional preview acceptance remain separate open boundaries.

## Deferred definitions

Six WorldPreviewPaletteTest definitions cover immutable capture/returned arrays/type lists, insertion/reordering with exact independent normal/ghost packed values, deletion and semantic-ID reuse with last-known colors and original-UUID restoration, foreign projects reusing UUIDs, late refresh versus computation identity, and empty/undersized palette bounds. None ran.

The existing deferred ViewportDocumentProbe/checker additionally requires the palette binding to be empty while same-UUID replacement work is held, before a new source can publish.

WorldPaletteProbe defines a production GLFW/OpenGL/ImGui Viewports workflow using real bounded editor workers, Auto off and unlit fixed/streamed geometry. Eleven receipts capture actual center pixels, accepted UUID order, packed normal/ghost rows, submission stamps and scene epochs:

| Phase group | Deferred expectation |
| --- | --- |
| Fixed initial/reorder/recolor | Old source indices keep their UUID meaning; recolor changes pixels without a new submission. |
| Fixed semantic-ID reuse/Undo | Deleted original UUID keeps its last known color; a same-ID replacement cannot borrow its index; Undo restores the original UUID. |
| Fixed late acceptance | A worker admitted before a color edit publishes geometry using the latest colors at acceptance. |
| Streaming late acceptance | A compiled SceneSetup remains queued across a color edit and resolves the latest colors when consumed. |
| Streaming reorder/recolor | Existing scene epoch and submission remain; accepted source order maps the current colors correctly. |
| Removal ghosts before/after recolor | Only Changes keeps the accepted source's ghost offset through current-order changes and shows the exact derived removal color. |

The fixture keeps an unrelated private Properties draft/rejected seed, preferences and view-only repaint history isolated. Native frames define project/revision/history invariance around observations. Controlled fixture edits intentionally change authored order/colors/actions; they are saved as separate projects. This is one software/native scenario definition, not physical DPI, arbitrary device-failure coverage or hardware certification.

scripts/verify_world_palette.py independently checks all eleven phase routes, UUID order, channel values, normal/ghost offsets, native pixels, unchanged manual submission/scene identities and complete JSON transformations/preferences. WorldPaletteConsumer loads four projects using only the engine JAR, checks canonical .25 samples and generates 576 exact final cells over signed chunks at roots 2/4. Its CLEAR fixture additionally checks 144 coordinates for occupied Before, EMPTY After and changed membership. Removal ghosts remain a display encoding rather than new stored voxel types.

Future Linux commands after validation resumes:

```sh
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.WorldPaletteProbe build/world-palette
java -cp build/independent:build/release/jars/noise-generator-engine.jar io.voxelcreator.example.WorldPaletteConsumer build/world-palette
python scripts/verify_world_palette.py build/world-palette
```

Future Windows commands use the bundled runtime, semicolon classpath and explicit exit-code guards. Both future workflow artifact lists include the receipts. Development branch push triggers remain disabled. No build, compiler, test, native probe, checker, generator or GitHub workflow ran for this batch. Full GUI, physical input/DPI, representative GPU/Windows 11 acceptance and production sign-off remain pending.
