# Workspace recovery and missing local batches

The restored checkout starts at a5b5533 (W06-C16), with a clean main branch 39 commits ahead of the local origin/main reference at 6f76999. Before the execution environment disconnected, the conversation recorded e70a5ee (W06-C27), 50 commits ahead, and a staged W06-C28 image/mesh batch whose commit could not be confirmed.

Read-only recovery checked branch refs, all reflogs, known e70a5ee/63e359c objects and unreachable objects. The later commits were absent. The two unreachable blobs contained older resource-form/documentation content, not the missing batches. No remote publication, reset, history rewrite, workflow or test was attempted. The existing 39 commits were retained.

## Recovery disposition

| Batch | Last recorded scope | State in the restored checkout |
| --- | --- | --- |
| W06-C17 | Captured frame upload acknowledgements | Missing; recover/reimplement with definitions and source review |
| W06-C18 | Optional streaming upload observation isolation | Missing; recover/reimplement |
| W06-C19 | Retained/versioned scene removals | Missing; recover/reimplement |
| W06-C20, e7cd11e | Native renderer shutdown/typed retirement | Missing; recover/reimplement |
| W06-C21, a5be076 | Two-phase UI peer shutdown | Missing; recover/reimplement |
| W06-C22, b88a27b | Timer/shadow retirement | Missing; recover/reimplement |
| W06-C23, 3e190f2 | Shadow allocation/raster state transactions | Missing; only the generic transaction dependency needed by C28 is reconstructed here |
| W06-C24, 1693696 | Viewport frame state/size/timing acceptance | Missing; C28 uses the older width/height cache directly |
| W06-C25, 7cf5c2d | Palette/host-volume state/cache ownership | Missing; only the texture-state dependencies needed by C28 are reconstructed here, used by image upload |
| W06-C26, 63e359c | Overlap-copy framebuffer ownership | Missing; recover/reimplement |
| W06-C27, e70a5ee | Generic volume-candidate callback ownership | Missing; recover/reimplement |
| W06-C28, previously staged | Fixed image/mesh state and logical acceptance | Reconstructed in source with thirteen deferred CPU definitions and native camera-path sentinels |

These IDs are retained to avoid confusing previously discussed work with the current source. A helper shared with a missing batch does not restore that batch's integration or verification scope. No original missing commit identity or bit-for-bit recovery is claimed. The new commit records the actual reconstruction on the available main history.

## Next work

- [x] Preserve the available main history and establish the actual checkpoint before editing.
- [x] Reconstruct C28 image/mesh input, binding/unpack restoration, logical acceptance and deferred definitions without depending on absent frame/retirement helpers.
- [ ] Recover/reimplement C17–C27 integration and definitions, beginning with captured frame upload acknowledgement/streaming caller ownership.
- [ ] Continue streaming state, parent preview/palette/profiler acceptance, retained physical display, other owners/callers/document/controls and W01–W08 source audits.
- [ ] After implementation and the owner's testing pause ends, compile and execute the current source and complete V01–V04 validation/delivery gates.

Reconstructed source remains **uncompiled and untested**. The last observed baseline is still **922 tests / 120 suites**; no A01–A36, Windows 11 or release evidence advances. Only source/Git content, metadata and whitespace review is available. Publication remains pending; the remote branch was not refreshed or modified during recovery.
