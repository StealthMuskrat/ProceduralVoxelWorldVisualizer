# Accepted-volume retirement feedback — unvalidated

W06-C09 covers current preview feedback in 1.4.1/15.5.2/15.5.3 and the existing Error Center boundary. [C05's handoff](VOLUME_RETIREMENT_HANDOFF.md) already kept committed texture/metadata together when previous-texture deletion failed, but its later warning still read host exception text and then unconditionally overwrote Viewports.message. A message reader could close the view or start another request; an old warning could then overwrite that successor's status. Diagnostic delivery RuntimeException/Error also escaped despite the replacement being valid.

Viewports now captures the committed texture version immediately after the handoff returns its recoverable retirement fault, before optional measurements run. It reports that warning only while the viewport is open, the upload epoch and accepted epoch match, the preview is complete and not failed, no job/pending volume owns it, a volume resident exists and the current texture version equals that captured receipt. The version guard also rejects a newer accepted volume at the same epoch. There is no new persisted receipt or retained Preview payload; the extra version read happens only on the retirement-fault path.

VolumePreviewHandoff.reportRetirement reuses the guarded diagnostic path. It checks current ownership before reading the original failure's bounded/guarded description and again afterward. It then updates the ready-with-cleanup-warning message and rechecks before attempting VOLUME_RETIREMENT_FAILED delivery. A feedback or diagnostic RuntimeException/Error is contained without reading that secondary fault's text or mutating either Throwable. No late state write follows diagnostic delivery, so a callback's successor status survives even when reporting throws. An event recorded before a reentrant transition remains valid history; no rollback or blind event replay is attempted.

The failure helper is not invoked by this warning. Accepted metadata, resident texture, byte accounting and preview readiness remain authoritative. Errors thrown by retirement itself still follow the existing C05/C07 propagation path after acceptance; this change only delivers recoverable retirement RuntimeExceptions. A backend deletion may have failed before or after physical release, so the diagnostic still does not guarantee release or retry the old texture name.

## Deferred definitions

Five added VolumePreviewHandoffTest definitions use the actual uploader, independent CPU float-array storage and explicit accepted source/origin/side/texture/version records:

- Current warning after before/after-release retirement failure, preserving the complete replacement, exact samples, one publication and original fault identity while distinguishing physical handle sets.
- Diagnostic RuntimeException/Error before/after event recording, keeping the ready warning, accepted payload and once-only old-texture deletion attempt.
- Already closed, newer-request and newer-same-epoch volume owners, rejecting warnings before any exception text read; null faults deliver nothing.
- Readable/unreadable message reentry into close, a new request or a real same-epoch successor texture commit, with no old warning or event after ownership changes.
- Feedback/report reentry followed by delivery failure, requiring the successor's status to survive and preventing stale notification after feedback changes ownership.

Independent x + 10y + 100z + base + 0.25 arrays define the retained/replacement textures. The same-epoch replacement actually uploads a 4-cube under a new texture/version and uses distinct accepted origin/source metadata. Before-release deletion can leave backend residue; fixture disposal clears that fake residue without claiming the uploader freed it. The handoff class now contains fifteen deferred definitions; its ten existing upload/observation definitions retain their expectations.

These tests exercise the production delivery helper with CPU owner guards, not actual Viewports widgets or native Error Center storage. The caller's accepted epoch/readiness checks and receipt capture timing are source-traced. Existing native upload/palette/optics/project assertions are unchanged; no native probe, report or engine-JAR dependency is added.

## Status and limits

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content checks add no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, geometry/math, budgets, schemas, registries and authored formats remain unchanged. Source stays on local main with publication pending.

This resolves the recorded retirement-warning lifetime/delivery gap in source. Subsequent [W06-C10 display acceptance](VOLUME_DISPLAY_ACCEPTANCE.md) holds presentation through volume setup/metadata failure while retaining immediate legacy renderer behavior. Physical palette rollback and other representation transactions remain separate boundaries. Other instrumentation, general mark/close/worker cancellation, GL teardown, complete leaf/caller inventories, real native/UI/Windows 11 recovery and representative resource/performance/release verification remain open. Warning callbacks are bounded and fault-path-only, without measured allocation/latency results; W05/W06/W07 audits remain incomplete.
