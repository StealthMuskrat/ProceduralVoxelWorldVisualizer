# Deferred volume display acceptance — unvalidated

W06-C10 covers render-thread publication in 1.4.1/14.1.5 and previous-preview retention/recovery in 15.5.2/15.5.3. A volume texture previously became drawable before acceptPreview uploaded its palette and published matching metadata. Palette failure could leave a new GPU resident alongside partially changed origin/hover state, while the failure message claimed that the previous display was retained.

Texture completion and editor display acceptance are now separate. A private VolumeDisplayGate records whether a staged upload requires confirmation, then holds the existing framebuffer at the committed texture version. The editor opts into that path. GlViewport will not render or resize the framebuffer while display confirmation is pending, even if the scalar texture upload has completed. No additional scalar texture, framebuffer or full-volume CPU clone is introduced by the gate.

The editor prepares source palette resolution, bounds, completion labels, message and optional probe receipt before GPU setup. GPU palette setup precedes publication of origin, raw values, sampler/hover, histogram, source palette and accepted epoch. Only successful setup/metadata publication confirms the captured texture version and releases the framebuffer. Existing recoverable retirement faults still accept a healthy display before their guarded warning; original retirement Errors retain their propagation policy.

On setup failure, VOLUME_ACCEPTANCE_FAILED settles pending/active/working and dirty/force state through the existing failure owner. Candidate cancellation does not release an already-held display receipt or delete the committed resident. A previous framebuffer remains visible with its saved image orientation; if no prior display exists, the UI shows the incomplete-setup message without an image. Hover, surface navigation and histogram/picking interactions pause while the receipt is held. Controls and explicit Regenerate remain available. A new successful replacement supersedes the held version; switching representation, release/document clearing or closure clears the gate.

This is presentation isolation rather than physical GPU rollback. Palette writes remain in place and may have changed pixels before failing; the held framebuffer prevents those uncertain resources from being drawn. A late CPU assignment failure may leave partially assigned metadata, but held presentation and paused interactions remain until successful confirmation. This does not provide arbitrary reentrant/concurrent renderer access or transactional image/mesh GPU setup. Source geometry, palette UUID mapping and canonical samples remain unchanged.

## Renderer API

| Entry point | Contract |
| --- | --- |
| Existing setVolume and stageVolume overloads | Keep immediate display on complete texture upload; no confirmation is required. |
| stageVolume(data, budget, region, true) | Opt into deferred display; validation/budget rejection preserves an existing candidate/receipt. |
| volumeDisplayPending() | Reports a complete resident awaiting display confirmation, independently of statistics.pending(). |
| acceptVolumeDisplay(version) | Releases only the matching held resident version with no staged candidate; wrong, stale, future and repeated receipts reject without release. Caller-owned metadata must already be ready. |
| cancelVolume() | Cancels incomplete work while preserving a previously held complete display receipt. |
| releaseVolume, image/mesh/scene transitions, clearPreview, close | Retire the presentation gate with the existing representation/resource lifetime. Physical deletion failures remain ambiguous and are not blindly retried. |

The complete-but-unconfirmed scalar texture remains included in active GPU bytes; a later replacement must coexist within the existing texture budget. The retained framebuffer remains within the pre-existing framebuffer reservation/exclusion policy. The gate and saved orientation/availability flags are bounded runtime state, not authored data or preferences. No headless API/dependency, schema, registry or archive changes. Renderer signatures are extended additively; existing overloads remain available.

## Deferred definitions

Six new VolumeDisplayGateTest definitions combine the real uploader with independent CPU pixels/metadata: partial/complete/exact confirmation; rejected/invalid staging; cancellation and stale receipts across explicit replacement; RuntimeException/Error setup failure before/after CPU assignment; before/after-release retirement failure; and legacy completion/clear/close. Exact x + 10y + 100z + base + 0.25 arrays distinguish old/new frame snapshots and verify byte/texture/version ownership. The CPU frame is a presentation model, not shader readback.

One additional VolumePreviewFailureTest definition requires the acceptance-specific diagnostic while preserving old CPU metadata and the already committed texture. That class now has eight definitions; all fifteen handoff definitions remain unchanged. Actual Viewports preparation, held orientation and hover/navigation wiring remain source-traced pending native execution.

VolumeUploadProbe gains two native scenario definitions. The renderer case uploads a complete deferred texture, queues a controlled GL error during palette setup, retains every old framebuffer byte through cancel/resize/retry, rejects stale confirmation and confirms a fresh replacement. Its new receipt retains versions [1,2,3], a framebuffer hash and analytical red-volume/background pixels. The editor case uses a one-shot package-private setup failure before palette upload, requiring old accepted origin/epoch and center pixel, settled failed UI ownership, no automatic retry across two frames, then successful explicit Regenerate. Its versions are [4,5,6] after the original UI sequence. The injected setup exception and queued GL error are controlled fixtures, not claims of actual hardware failure.

verify_world_picking.py requires both new receipts and independently checks Beer–Lambert transmittance exp(-0.1×4), background channels, origin/version transitions and retained/retry flags. Original 17-cube texels/optics, upload-byte bounds, palette state, UI [1,2,3] sequence and project/revision/history assertions retain their expectations. Native readback now checks dimensions before allocating its destination buffer. No new report exists; historical reports lack the new receipts.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, Python/checker, package, update request or workflow ran. Source review and Git whitespace/content checks provide no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Source remains on local main with publication pending.

The recorded volume acceptance/presentation gap is addressed in source, with all execution and physical-input/DPI/driver/performance validation deferred. Subsequent [W06-C11 request retirement](PREVIEW_REQUEST_RETIREMENT.md) addresses mark/terminal-preview cancellation ordering and associated Inspector ownership in source. Other image/mesh acceptance transactions, source-compiler/general close/GL teardown and instrumentation, complete leaf/caller inventories and representative resource/Windows 11/release verification remain open. No measured overhead or complete W05/W06/W07 audit closure is claimed.
