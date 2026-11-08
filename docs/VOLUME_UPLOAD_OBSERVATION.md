# Optional volume upload observations — unvalidated

W06-C08 covers optional instrumentation in 19.4.2/19.4.4 at the render-thread upload/preview boundary in 14.1.5/15.5.3. Inspection of Viewports found that scope creation/begin could prevent an upload, and finish/gauge calls in finally could replace its original failure. After a complete replacement, an observation RuntimeException could also enter volumeUploadFailed and incorrectly mark that committed preview failed. A first measurement failure skipped the remaining gauges.

Profiling scope creation and begin are now guarded independently of the upload. If startup fails, the upload still proceeds and no finish is fabricated for an unavailable stamp. A normally started observation retains its original scope/stamp/outcome. Its three measurements—CPU timing, host bytes and resident-copy bytes—are attempted independently through VolumePreviewHandoff.observe. RuntimeException/Error from a measurement is retained separately; the first is selected for feedback without mutating or suppressing onto any original Throwable. The surrounding finally also contains failures while constructing/invoking those measurement callbacks. Profiling failures do not call volumeUploadFailed, cancel a candidate, accept metadata or change upload outcome.

The existing handoff remains authoritative: partial uploads do not publish, complete versions accept matching metadata, pre-publication faults retain their old failure behavior and retirement faults retain C05's committed replacement semantics. An original upload/retirement Error continues to propagate after the measurement finally; no observer diagnostic is promised after that fatal exit. CPU timing still covers the existing upload/handoff turn, with unchanged labels, units and byte counters. Missing observations are not retried or replaced with invented samples; a callback may have recorded data before throwing.

When normal caller recovery reaches reporting, at most the first profiling fault receives VOLUME_PROFILING_FAILED through the existing bounded Error Center. Open-viewport/epoch checks before and after guarded text formatting prevent a stale observation from being delivered after close or a new request. Reporting RuntimeException/Error is contained without reading the reporting fault's text or changing the accepted viewport message. This event is diagnostic feedback, not publication authority or a failed-preview flag. Repeated events retain existing grouping/retention policy; no additional queue or persistent fault owner is introduced.

Disabled profiling keeps the existing zero-stamp path: no measurement callbacks/array are created, and observation delivery callbacks are created only when an actual observation fault exists. Targeted captures create a bounded three-callback batch per upload turn. Neither overhead nor allocation/latency has been measured for this source change.

## Deferred definitions

Five added VolumePreviewHandoffTest definitions use the real uploader and existing independent CPU storage/source-origin metadata fixture:

- All eight measurement-failure masks with RuntimeException/Error choices across a 20-byte partial and 88-byte completing advance. Every admitted measurement is attempted once per turn; old/new metadata, exact samples, one publication and 140 cumulative bytes retain independent expectations.
- Primary write RuntimeException/Error with failing measurement finalization, requiring the original failure identity, old resident/metadata and exact samples to survive while all three measurements are attempted.
- Before/after-release retirement faults plus observer failure, preserving the complete replacement and original recoverable-return/Error-propagation behavior.
- Guarded unreadable observation text and failing diagnostic delivery, preserving accepted samples, metadata and unmutated Throwables.
- Already obsolete, null and message-reader-invalidated observations, requiring no stale delivery or change to accepted data.

The handoff class now has ten deferred definitions. These CPU tests exercise production handoff/observation helpers with controlled callbacks; they do not instantiate a failing native Profiler.Scope or certify actual Viewports widgets. The scope-start bridge and error-handler separation are source-traced. Existing profiler/capture, upload/native, texel/optics, palette and authored-project definitions retain their expectations; no native probe, report or engine-JAR dependency is added.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content checks add no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, exact math, budgets, schemas, registries and authored formats remain unchanged. Source stays on local main with publication pending.

This fixes optional CPU upload-observation isolation only. In-place palette/preview acceptance failures, retirement diagnostic reentry/delivery, other renderer/worker instrumentation, general mark/close cancellation, GL teardown, complete leaf/caller inventories, native/Windows 11 and representative resource/performance/release verification remain open. It does not make acceptPreview transactional or complete the W05/W06/W07 source audits.
