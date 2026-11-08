# Failed-volume UI ownership — unvalidated

W06-C07 addresses the caller gap recorded by [palette upload ownership](PALETTE_UPLOAD_OWNERSHIP.md): render/UI ownership in 1.4.1, cancellation/overload feedback in 14.1.3/14.1.5 and failed-preview/retry presentation in 15.5.2/15.5.3. volumeUploadFailed previously cancelled the backend before clearing pendingVolume, working bytes and activeJob. A throwing deletion could leave that stale UI owner attached. Two RuntimeException callers also invoked getMessage before recovery, so unreadable or reentrant fault text could prevent settlement entirely.

The private VolumePreviewFailure path now settles the failed UI owner first: it clears the pending payload and working estimate, releases active status, consumes dirty/force request flags, marks failure and installs a primary retry message. An early GPU-budget rejection could previously leave a manual Regenerate force flag set, permitting another attempt on later frames despite failed status. Failed requests now wait for a new explicit retry. The helper then attempts backend cancellation once. Cancellation RuntimeException/Error is contained as a separate cleanup fault after settlement. The existing uploader detaches its candidate before deletion; no blind texture-name retry or physical release guarantee is added.

Each failure receives a private identity ticket as well as its existing epoch. Feedback and diagnostic operations require an open viewport with that same ticket/epoch and settled failed state. Marking a new request and accepting a preview revoke the ticket. Cancellation callbacks, primary getMessage, cleanup getMessage and primary diagnostic delivery may reenter; current-owner checks before and after text reads prevent a closed viewport, successor or nested same-epoch failure from receiving old feedback. Already delivered history remains valid for the owner that was current when it was recorded.

Primary exception text is delayed until after settlement/cancellation and uses the existing bounded, guarded BackgroundFailure.describe path. The primary VOLUME_UPLOAD_FAILED detail remains the viewport's feedback. A distinct VOLUME_UPLOAD_CLEANUP_FAILED entry records a cancellation fault when its owner remains current; cleanup does not overwrite primary feedback. Original Throwables are not mutated or linked through suppression. Diagnostic delivery RuntimeException/Error is contained without reading its text, preserving the primary viewport message and allowing the other notification attempt when still current. If diagnostic storage fails, its event may be absent or may already have been recorded; no replay is attempted.

Stage Errors settle the failed UI before being rethrown unchanged. Advance Errors do likewise while a pending upload remains. A post-commit retirement Error has already cleared that pending payload through [C05's handoff](VOLUME_RETIREMENT_HANDOFF.md), so it still propagates with accepted metadata rather than entering failure settlement. Existing runtime upload rejection/failure remains recoverable through explicit Regenerate; the failure helper neither submits nor accepts replacement work.

## Deferred definitions

Seven VolumePreviewFailureTest definitions use the real VolumeTextureUpload against independent CPU float arrays and an explicit accepted source/origin/side/texture/version model:

- Cancellation RuntimeException/Error before/after physical release observes already-settled UI and detached uploader ownership, retains primary rejection and exact old samples, reports cleanup separately and permits an explicit healthy successor.
- Unreadable primary RuntimeException/Error is read only after settlement and cancellation, with guarded class-name fallback and no toString read.
- Cancellation callback close/replacement suppresses all stale text/feedback and preserves replacement staging or closed teardown.
- Primary message close/replacement prevents later feedback and cleanup text reads.
- Diagnostic faults before/after recording preserve primary feedback and still attempt both current-owner reports, without Throwable text reads or mutation.
- Primary-report or cleanup-message reentry prevents stale secondary notifications while the real successor completes correctly.
- Nested failure at the same epoch replaces the original ticket and is the sole surviving feedback/history owner.

Independent x + 10y + 100z + base + 0.25 expectations compare retained and successor texture samples. Before-release deletion faults may leave a physical allocation; fixture teardown clears that fake backend residue rather than asserting that the uploader freed it. These tests exercise the production failure helper with a CPU owner model; actual Viewports fields and Error catch boundaries remain source-traced rather than executed through native widgets.

VolumeUploadProbe also strengthens its existing GPU-budget rejection scenario to require failed/idle worker state, no pending UI volume and a consumed force flag before explicit retry. Original origin/version, analytical pixel, texel, palette-state, byte-budget and authored-project expectations remain. No report format or checker change is required for this extra assertion, and no new report exists.

## Status and limits

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content checks add no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Source remains on local main with publication pending.

Public signatures, headless dependencies, geometry/math, upload budgets, UUID/source mappings, schemas, registries and authored formats remain unchanged. The ticket and callbacks are bounded per failure with no measured allocation/latency claim. This does not make in-place palette writes or the entire acceptPreview callback transactional, classify every profiler/acceptance failure after commit correctly, or finish general mark/close/worker cancellation and GL teardown recovery. Those caller/resource/native/performance audits remain open; this child closes only the recorded failure-settlement source gap.
