# Fixed image and mesh uploads — reconstructed, unvalidated

W06-C28 addresses render-thread upload/state ownership in 1.4.1/14.1.5 and the logical acceptance portion of 15.5.3. This batch reconstructs the pending image/mesh work after the workspace restored W06-C16. See [workspace recovery](WORKSPACE_RECOVERY.md): C17 has since been reconstructed, while C18–C27 integrations remain open. The shared transaction/texture-state dependencies below do not restore their other integrations.

Previously setImage/setMesh cleared volumes and changed display metadata before native upload. Image writes inherited unpack/PBO state and reset texture binding to zero. Mesh writes reset VAO/global array-buffer bindings to zero and retained a mutable caller array for later shadow/draw metadata. Neither fixed-upload path checked completion errors.

## State and input ownership

ViewportUploadData validates positive image dimensions and an exact long-product pixel count, then clones packed words. Mesh input requires nonnull whole seven-float vertices and is cloned with its points flag; empty meshes remain valid. No new float filtering, geometry transformation, color conversion or triangulation is introduced. The owned mesh supplies native upload and accepted CPU/shadow metadata. Image preparation retains the direct IntBuffer representation. Device image-size admission precedes native mutation.

TextureUploadState captures ten values before image work: active unit, selected target binding, unpack PBO, alignment, byte swap, row length, image height and skip pixels/rows/images. Work uses the captured unit, PBO zero, alignment four and zero remaining unpack offsets/flags. Shared RenderTransaction independently attempts all restores, retaining the body primary or first restoration fault. It neither reads failure text nor adds suppression. The image still uses RGBA8 and linear filtering. Palette/host-volume integration of this helper remains missing C25 work.

MeshUploadState captures VAO and global array-buffer bindings. Both restore independently in that order. Attribute and element-buffer state remain properties of each VAO; only the private mesh VAO is configured. The layout remains position/normal/type = 3/3/1 floats, stride 28 bytes, offsets 0/12/24 and static vertex storage. Native image/mesh completion errors reject acceptance after remaining restores. Snapshot failure invents no restoration or upload.

## Logical acceptance and retirement

ViewportUpload serializes work through current checks, upload/state restoration, predecessor retirement and publication. Reentry rejects before copying another payload; render rejects before timing/raster work while upload is active. Commands check current/open lifetime before and after work. Scene activation/disable, document reset, accepted volume stage/completion, volume cancel/release and root close invalidate old upload acceptance. Root close closes this coordinator before existing native cleanup callbacks; its wider cleanup remains missing C20 work.

Only successful upload/restoration proceeds to retirement. The root detaches scene and shadow peers before volume deletion; the existing volume uploader detaches its own resident/candidate before deletion callbacks. Independent volume/shadow/scene cleanup cannot skip peers, and callback-created successor scene/shadow owners are not selected for old retirement. Target image/mesh flags, retained-frame release and mesh/bounds metadata publish after successful retirement and current checks. Retiring a volume clears its own flag immediately, including when later retirement fails.

Failed admitted operations clear the existing width/height cache. A later render must reallocate storage instead of trusting dimensions from before the upload; this is not the missing C24 full frame/timing transaction. The failure observer cannot replace an original body/restore/retirement primary. Input/admission failures before the coordinator retain previous metadata. Healthy retry uses fresh snapshots and owned input.

GPU writes remain in place: old pixels/vertices are not rolled back after failure. Retirement/context/caller-object validity and physical restoration are not certified. Full atomic retained-preview behavior, Viewports.apply palette/metadata publication and general rendering/streaming reentry remain open. This batch adds no second GPU candidate set, executor or queue. It adds one payload-sized CPU copy alongside the existing image buffer plus fixed callback/snapshot state; heap/native-call/latency costs and pressure remain unmeasured. Public signatures, headless boundaries, scalar/mesh/shader math, palette lookup, budgets, UUID mappings, schemas and authored formats are unchanged.

## Deferred definitions

Thirteen ViewportUploadTest definitions exercise production data/coordinator/mesh/texture state and retirement helpers:

- Six exact RGBA words and fourteen floats/two points/56 bytes survive caller mutation, with restored state before retirement/publication.
- Null/malformed/overflowing shapes and partial vertices reject; one-pixel images and empty meshes remain valid.
- Every modeled mesh command, both mesh restores, every image normalization/restore, all twelve snapshot reads and image bind/data/completion positions before/after RuntimeException/Error effects.
- Independent cleanup tails, original/shared/unreadable fault priority, failed observers and no premature acceptance.
- Close/invalidation through work/restoration/retirement, image normalization/body/all ten restores, terminal admission, recursive work rejection, owned binding snapshots and exact retry.

FixedUploadProbe extends CameraGeometryProbe without changing its existing 144 raster/DDA expectations or report shape. Definitions bind a foreign texture, unpack PBO, seven hostile pixel stores and a VAO/VBO/EBO with attribute zero. Queued image/mesh completion errors must restore these sentinels. Failed image completion retains its prior volume; healthy image retry releases it and reads six exact words. Mesh recovery precedes the original camera cases. Queued errors do not emulate OOM, failing driver cleanup calls or pixel rollback. Native/root mapping is source-traced; no probe/report was executed.

## Status

All additions remain **uncompiled and untested** under the owner's pause. No compiler, Gradle, JUnit, native probe, consumer, checker, package, update request or workflow ran. Source/Git content/whitespace review is the available verification. Baseline remains **922 tests / 120 suites** and all acceptance/Windows 11/release gates remain open. Local-main publication remains pending.

Subsequent [reconstructed C17](SCENE_FRAME_UPLOADS.md) restores captured frame upload acknowledgement/activation ownership, also awaiting execution. Next recover C18–C27, starting with streaming observation isolation and retained removal delivery, then complete streaming caller-state restoration, parent preview/palette/profiler acceptance, retained physical display, other owner/caller/control inventories and W05/W06/W07/native/resource/performance/release work.
