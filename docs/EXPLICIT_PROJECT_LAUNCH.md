# Explicit project launch — unvalidated integration

This source batch supports specification 2.1, 2.6.5–2.6.7, 4.4, 18.7.3, 19.2.1–19.2.2 and A01/A03/A32/A35. It remains uncompiled/untested. No compiler, JUnit, native probe, consumer, checker, packaged launch or workflow ran. The owner testing pause and the last observed validated baseline of 922 tests / 120 suites remain unchanged.

## One Open workflow for an explicit filename

Desktop previously handled a command-line project path by directly calling DocumentSession.open during preRun. That bypassed background preparation and the file dialog's guarded completion/retry handling, delaying startup on reading/parsing/compiler work. Desktop now passes its explicit filename to ProjectFileDialog.openLocation before the first frame. The existing bounded worker prepares a private candidate; the UI continues into its normal ready frame with a loading dialog and an empty committed document until publication succeeds.

The supplied path is attached only after the file workflow explicitly admits that Open request. A blocked/closed/busy request, or an external blocker that reentrantly admits a successor, cannot prefill or submit the outer request's path. The existing void request(Intent) contract remains; a separate internal admission receipt supports path delivery. Repeated supplied paths do not retarget an in-flight request.

A newly launched empty session starts preparation immediately without a redundant path-confirmation click. If the same entry point is used with an existing private/dirty session, its ordinary draft and unsaved-project decisions still run. After those guards, the prefilled Open path remains for explicit review/submission; it is not automatically opened by approving a different decision.

Malformed/missing files or compiler/scheduler failure preserve the empty unsaved session and show the supplied path in the existing retry dialog. Native rendering may begin before failure delivery, or the failure may arrive before the first dialog frame; both retain the same path. Cancel Open/Escape and shutdown revoke publication authority, including late worker results. A changed document cannot be overwritten by the pending launch. Success uses the same single UI publication, new lifetime, clean checkpoint/history, cleared selection/private inputs and resource refresh callback as menu Open.

Desktop arguments are now an owned clone per instance. Mutating a caller's argument array cannot change a pending launch, and a default Desktop() remains empty independently of other launches in the JVM. Main passes its explicit arguments into that instance. Existing --smoke precedence and first-filename argument interpretation are retained; no new option syntax, project history, automatic reopening or autosave is added.

For example, with the documented runtime and artifact paths:

```sh
java --enable-native-access=ALL-UNNAMED -jar jars/ProceduralVoxelCreator.jar "C:/projects/example.pvproj.json"
```

The bundled Windows command file forwards the same optional filename. The current package remains Development and no packaged Windows 11 launch has been observed for these changes. Physical startup timing and large-file snapshot/ownership costs remain acceptance work.

## Deferred definitions

Ten ProjectLaunchOpenTest definitions use controlled worker/UI queues without drawing a native frame. They cover Unicode/space path preservation, UI-only publication and exact empty-to-loaded state, malformed input and correction/retry, cancellation after preparation, blocked retargeting, retained draft/unsaved guards, closed/blocked owners, reentrant successor admission with and without an exception, missing/blank/invalid paths, worker rejection and shutdown. The injectable background factory retains the same executor/UI handoff used by Desktop.

ProjectFileCompletionProbe includes six ProjectLaunchProbe scenarios through the production factory before its first dialog frame: success, malformed input, correction through the real Open button, Cancel Open, direct committed change and shutdown. Controlled compiler gates define twelve responsive frames with no replacement. The deferred checker independently reads complete post-state JSON and checks .75 constant fields only after accepted launch, exact seeds, empty rejected sessions, unchanged preferences and final phases. These cases reuse the existing manual-only command/artifact directory.

StartupProbe also invokes three StartupProjectProbe whole-Desktop cases. These launch actual Desktop instances with explicit Unicode/space filenames, mutate the caller argument array afterward, gate compiler validation off-thread and require three rendered frames while the active document is still empty. Success installs the requested file; malformed input retains the empty session/path decision; shutdown while blocked leaves no late document replacement. All cases require native window/context/worker teardown and unchanged incoming bytes. The original eight empty-startup/cleanup cases remain intact. scripts/verify_startup.py defines the nested report/project checks. This is source wiring only, not observed GL/launcher/hardware evidence.

The existing engine-only prepared-open analytical replay remains the underlying model/publication definition; no generated-field behavior or schema changes. Full compilation, native/physical input, large-file/race/load, packaged independent artifacts, Windows 11/hardware and final release acceptance remain pending. Main remains authoritative, with no new development branch or workflow trigger/dispatch.
