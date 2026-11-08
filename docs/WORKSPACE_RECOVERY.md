# Workspace recovery and missing local batches

The restored checkout starts at a5b5533 (W06-C16), with a clean main branch 39 commits ahead of the local origin/main reference at 6f76999. Before the execution environment disconnected, the conversation recorded e70a5ee (W06-C27), 50 commits ahead, and a staged W06-C28 image/mesh batch whose commit could not be confirmed.

Read-only recovery checked branch refs, all reflogs, known e70a5ee/63e359c objects and unreachable objects. The later commits were absent. The two unreachable blobs contained older resource-form/documentation content, not the missing batches. No remote publication, reset, history rewrite, workflow or test was attempted. The existing 39 commits were retained.

## Recovery disposition

| Batch | Last recorded scope | State in the restored checkout |
| --- | --- | --- |
| W06-C17 | Captured frame upload acknowledgements | Reconstructed in source: activation/pending receipts, captured UI frame/pump and fourteen new deferred definitions; see SCENE_FRAME_UPLOADS.md |
| W06-C18 | Optional streaming upload observation isolation | Reconstructed in source with independent work/observation outcomes and eleven real-scene/public-profiler deferred definitions; see SCENE_UPLOAD_OBSERVATION.md |
| W06-C19 | Retained/versioned scene removals | Reconstructed in source with bounded retained receipts, exact acknowledgement, version-gated GPU remove/reset, thirteen deferred CPU definitions and native probe cases; see SCENE_REMOVAL_OWNERSHIP.md |
| W06-C20, e7cd11e | Native renderer shutdown/typed retirement | Reconstructed in source with detached root/scene handles, independent once-only typed retirement, closed guards and eight deferred CPU definitions; see NATIVE_RENDERER_SHUTDOWN.md |
| W06-C21, a5be076 | Two-phase UI peer shutdown | Reconstructed in source with detached UI/compiler/Inspector/scene/renderer/cursor owners, independent once-only cleanup and ten deferred CPU definitions; see UI_PEER_SHUTDOWN.md |
| W06-C22, b88a27b | Timer/shadow retirement | Reconstructed in source with terminal/captured query and shadow ownership, independent typed retirement, callback guards, eleven deferred CPU definitions and updated native probe definitions; see TIMER_SHADOW_RETIREMENT.md |
| W06-C23, 3e190f2 | Shadow allocation/raster state transactions | Reconstructed in source with private candidate acceptance, twelve allocation/fifteen raster restores, callback guards, eleven deferred CPU definitions and native/checker definitions; see SHADOW_STATE_TRANSACTIONS.md |
| W06-C24, 1693696 | Viewport frame state/size/timing acceptance | Missing; C28 uses the older width/height cache directly |
| W06-C25, 7cf5c2d | Palette/host-volume state/cache ownership | Missing; only the texture-state dependencies needed by C28 are reconstructed here, used by image upload |
| W06-C26, 63e359c | Overlap-copy framebuffer ownership | Missing; recover/reimplement |
| W06-C27, e70a5ee | Generic volume-candidate callback ownership | Missing; recover/reimplement |
| W06-C28, previously staged | Fixed image/mesh state and logical acceptance | Reconstructed in source with thirteen deferred CPU definitions and native camera-path sentinels |

These IDs are retained to avoid confusing previously discussed work with the current source. A helper shared with a missing batch does not restore that batch's integration or verification scope. No original missing commit identity or bit-for-bit recovery is claimed. The new commit records the actual reconstruction on the available main history.

## Next work

- [x] Preserve the available main history and establish the actual checkpoint before editing.
- [x] Reconstruct C28 image/mesh input, binding/unpack restoration, logical acceptance and deferred definitions without depending on absent frame/retirement helpers.
- [x] Reconstruct C17 captured frame upload acknowledgement and activation/pending provenance without claiming native execution or the original commit identity.
- [x] Reconstruct C18 optional streaming observation isolation, captured counters/finalization and guarded post-settlement diagnostics.
- [x] Reconstruct C19 retained/versioned removal delivery, bounded reset recovery and native typed retirement integration.
- [x] Reconstruct C20 native renderer shutdown, typed once-only retirement, independent child/handle attempts and closed-owner guards.
- [x] Reconstruct C21 two-phase UI peer shutdown, sealed publication/job owners, independent cleanup and original failure preservation.
- [x] Reconstruct C22 timer/shadow retirement, terminal guards, callback ownership, query namespaces and independent first-failure cleanup.
- [x] Reconstruct C23 shadow candidate/caller-state acceptance, independent allocation/raster restoration tails, closed/recursive guards and deferred definitions.
- [ ] Recover/reimplement C24–C27 integration and definitions, beginning with frame state/size/timing acceptance.
- [ ] Continue streaming state, parent preview/palette/profiler acceptance, retained physical display, other owners/callers/document/controls and W01–W08 source audits.
- [ ] After implementation and the owner's testing pause ends, compile and execute the current source and complete V01–V04 validation/delivery gates.

Reconstructed source remains **uncompiled and untested**. The last observed baseline is still **922 tests / 120 suites**; no A01–A36, Windows 11 or release evidence advances. Only source/Git content, metadata and whitespace review is available. The original recovery did not refresh or modify the remote branch. The owner now authorizes publication of completed main batches under AGENTS.md; verify the remote head after each push.
