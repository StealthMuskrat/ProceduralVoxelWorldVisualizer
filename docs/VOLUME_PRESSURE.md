# Streamed scalar pressure controls

Development update: [coarser display sampling](VOLUME_DISPLAY_DETAIL.md) is implemented but unvalidated while GitHub testing is paused. The earlier unit-grid pressure evidence remains the last validated baseline.

Camera-relative Volume now observes its CPU window reservations, canonical chunk cache, active/candidate texture bytes and smoothed frame time through the existing RenderPressure controller. Fixed analysis retains explicit hard admission. Configured extent, CPU/GPU limits and cache preferences are never rewritten by automatic fallback.

The ordered effective policy is:

| Level | Scalar behavior |
|---|---|
| 0 | Configured extent, rounded up to whole 32-cell chunks |
| 1 | Coarsen display samples to spacing 2; retain physical extent |
| 2 | Reduce visible extent by one whole chunk per axis, to a minimum of 32 |
| 3 | Keep the reduced extent and release the optional canonical chunk cache |
| 4 | Defer new window sampling/source adoption; an already admitted replacement can complete |
| 5 | Cancel incomplete sampling/uploads and reclaim queued work; retain the accepted texture; require Resume Volume |

An explicit Resume returns to level 3. Stable low-pressure intervals gradually restore cache/extent through the shared hysteresis controller. Regenerate may record an explicit source request while paused, but does not bypass Resume. Auto-off camera/pressure changes keep the accepted compiled source; an explicit Regenerate adopts committed changes once admission resumes. Source snapshots and complete-candidate metadata keep their existing ownership rules.

The canonical field and finite encoding rules are unchanged. Display sampling becomes coarser at level 1; the subsequent extent reduction clips physical visibility. This does not edit exact procedural world output. A reduced complete window can reuse compatible resident overlap. UI reports configured/effective extent and the scalar-specific pressure action. Sparse residency and representative performance remain separate work.

VolumeStreamingLimits computes pure admission before queuing or allocating a candidate: sampling/encoding peak plus one tile and bounded staging, actual sample-grid dimension/cell limits, accepted texture plus full candidate storage, and optional cache from remaining CPU space. Level 3 trims cache without recompiling or resampling the current window. A queued window reserves its peak immediately; memory reporting counts the maximum of the serialized worker, queued reservation and upload staging rather than adding overlapping ownership of one window. Incomplete cancellation releases the UI reservation while an uncooperative running sampler remains visible through the worker's actual peak until it returns.

Hard admission still applies after fallback. A smaller level-2 window or changed limits can rearm a rejected request. Persistent rejection participates in pressure escalation without allocating a rejected candidate repeatedly. Invalid source/GL evaluation failures remain explicit retry failures, distinct from memory/queue rejection. Raising a budget rearms a failed streaming request; changing upload rate or cache size alone does not resample a completed texture. An active replacement is canceled if the revised CPU/GPU limits cannot admit it; the accepted texture may exceed a newly lowered limit until a legal replacement can coexist. It is never destroyed merely to satisfy a new limit.

The earlier validated unit-grid pressure implementation covers pure pressure/admission boundaries and canonical samples, plus production native window reduction, cache trimming, manual-source retention, level-4 deferral, canceled partial GPU replacement, paused queued-work reclamation, actual Resume button clicks, CPU/GPU limit recovery, gradual restoration and project/preference invariance. This is scoped policy/functional evidence, not representative hardware latency, throughput, arbitrary device-fault or complete GUI/Windows 11 certification.

The new spacing-aware pressure, texture and optics definitions are deferred; see [VOLUME_DISPLAY_DETAIL.md](VOLUME_DISPLAY_DETAIL.md).

The unvalidated [preview Resume follow-up](PREVIEW_RESUME_DECISIONS.md) replaces the scalar Resume trigger with a fitted, pause/source/limit-owned action and distinguishes subsequent pause episodes. Admission is consumed before the existing rejection clear/invalidation; source and hard-budget policies remain. Five regression definitions, eighteen native cases and independent policy/project/engine replay remain deferred.
