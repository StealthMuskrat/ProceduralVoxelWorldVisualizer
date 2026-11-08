# Streaming source-compiler ownership — unvalidated

W06-C12 addresses source admission/retirement in 1.4.1, 14.1.3/14.1.5 and 15.5.2/15.5.3. Streaming admission previously cancelled the old Future before advancing the owned-source mailbox. stopStream invalidated that mailbox before detaching its compiler/scene fields, then cleared live fields after cancellation/close callbacks. Cleanup could admit a replacement that old bookkeeping overwrote; cancellation faults could also skip scene closure.

Private CPU-only SourceCompilerTask now owns the compiler Future, requested key, exact opening ticket and existing OwnedPreviewMailbox. Begin/stop/close detach old ownership and revoke its mailbox before captured Future cancellation. Pending-source disposal remains outside the mailbox monitor and contains cleanup faults under its existing contract. A disposal callback can admit another opening or close the task; generation/version guards prevent the interrupted outer begin from installing a ticket afterward. Future cancellation reentry similarly makes the outer ticket obsolete without clearing its successor. Only the captured Future is notified, once per retirement attempt; its first fault is returned without reading or mutating it.

An opening remains pending while submission is in progress, including inline worker publication before submit returns. Its source cannot be taken until the exact returned Future is attached and the UI observes completion. A stale returned Future is cancelled directly, without looking up a successor's Future. A caller-side copy/submission failure explicitly settles the no-Future state before publishing its failure receipt. Workers use only the thread-safe mailbox eligibility/publication methods; UI-owned ticket/Future fields are not read by workers. Late source publication is rejected and disposed even if cancellation throws or interruption is ignored.

Viewports checks ticket/Future identity, current source key, open state and streaming intent around submission, completion inspection, failure description and receipt consumption. Queue rejection retires inline prepared results and leaves existing capacity-retry feedback. Other admission failures replace the opening before guarded primary description/publication, disposing any inline prepared source. Existing terminal STREAM_SOURCE_FAILED feedback and explicit Regenerate behavior are retained. STREAM_CANCEL_FAILED is a separate bounded diagnostic guarded by the captured generation/key before and after text reads; diagnostic delivery cannot change the established request owner.

stopStream captures and detaches its old scene before compiler/mailbox callbacks, attempts that captured scene's closure and relevant executor purge independently of compiler cancellation faults, and performs no late scene/key clear. Compiler cancellation has priority over later scene/purge diagnostics. Source-close physical side effects remain ambiguous after a throwing callback; no automatic replay or successful release is assumed. Calls remain UI-owned and cooperative callbacks may block. General document-reset/caller and shutdown transactions are separate work.

## Deferred definitions

Eleven SourceCompilerTaskTest definitions exercise the production controller and actual owned mailbox:

- Exact-Future completion gating, once-only source transfer, retained literal sample arrays and late-result disposal.
- Inline publication held until submit returns; caller-side no-submission failure settled explicitly.
- Before/after Future cancellation effects under RuntimeException/Error faults, with mailbox/Future retirement observed before pending-source/Future callbacks, unread faults and a healthy replacement.
- Pending-source disposal reentry into same-key replacement or close before captured Future cancellation.
- Future cancellation reentry into same-key replacement/close, rejecting a stale returned Future and disposing its late source.
- Stop callbacks admitting a successor during pending-source or Future cleanup, preserving that successor despite both cleanup faults.
- Stale-return cancellation admitting another successor, without clearing/cancelling the newest owner.
- Guarded unreadable cancellation text reentering replacement/close, suppressing stale diagnostics.
- Admission rollback disposing an inline prepared source before a healthy same-key retry.
- A real exceptional Future replacing its prepared source, whose disposal admits a successor that survives the old failure publication.
- A held executor worker that ignores/clears interruption and publishes after throwing close cancellation; an executor barrier requires rejection and exactly-once disposal without a UI drain.

These are source-lifetime/identity definitions, not numerical generation or native rendering claims. Literal sample arrays distinguish retained/replacement payloads. Actual Viewports controls, submit/rejection/description guards, captured ChunkScene closure, diagnostics and native probes remain source-traced; existing mathematical, scene, mailbox, fixed/volume, Inspector and native expectations are retained.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git whitespace/content review adds no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, canonical mathematics, source UUID mappings, schemas, registries, formats and configured budgets remain unchanged. Local-main publication is pending.

This closes the recorded compiler admission/retirement ordering gap in source. Scene adoption and renderer/palette acceptance, rendererFallback, surrounding document/control callers, general Viewports.close/GL teardown, other representation transactions, instrumentation and full W05/W06/W07 leaf/caller inventories remain open. Representative native/physical-input/DPI, resource/performance, Windows 11 and release validation are deferred; no measured overhead or audit completion is claimed.
