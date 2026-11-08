# Scene renderer allocation and epoch ownership — unvalidated

W06-C16 addresses the renderer scene slot in 1.4.1, 14.1.5 and 15.5.2/15.5.3. GlViewport.scene previously closed the registered renderer before clearing its reference/budget and enabled display after unguarded construction/epoch reset. A throwing close/reset could strand stale ownership; callback reentry could overwrite or close a replacement.

Private CPU-testable SceneRendererOwner now owns the renderer reference, registered budget, active/transition/valid state and opening generation. Existing 16-byte arena bounds validate before representation preparation. Stable same-budget openings reuse a valid renderer; budget changes detach reference/budget before captured closure, then create only after normal closure. Epoch reset runs while inactive, and current normal completion precedes display publication. Preparation, retirement, creation, reset and publication guards protect successors.

Factory calls serialize without a queue: recursive allocation revokes the outer opening and throws CancellationException while another factory is on the stack. A normally returned obsolete candidate is closed without registration. Factories own partial handles they cannot return; SceneGpuRenderer construction now attempts cleanup for RuntimeException/Error and preserves the original allocation/link fault if cleanup also fails. Primary recovery attempts captured cleanup without fault text, suppression, replacement or replay. Cleanup faults during that recovery are contained rather than separately retained.

Validity is separate from display activation. Disable during epoch reset cannot leave the partially reset resource reusable; it is detached/closed even if reset returns normally. A complete valid renderer can still disable/re-enable without allocation. Closed owners reject activation before backend work. Superseded activation throws CancellationException rather than returning a successor as the old call's result.

GlViewport delegates activation, runs volume retirement inside the guarded opening and publishes image/volume/retained-frame flags only after current normal reset. Rendering skips transitions; raster/shadow consumers use the active owner. Representation switches disable it, releaseScene captures/detaches it before existing independent shadow/scene cleanup, and viewport close makes scene ownership terminal before other teardown callbacks. General native teardown remains a separate audit.

Normal budget replacement does not intentionally retain two registered arenas. Physical deletion is not certified: close may throw before release, partial allocation may have ambiguous effects, and serialization covers in-progress factories rather than every native deletion or cross-representation operation. No physical rollback, universal callback-time peak-memory bound or framebuffer transaction is claimed.

## Deferred definitions

Twelve SceneRendererOwnerTest definitions use the production owner, real ArenaAllocator instances and independent handle/epoch callbacks:

- Same-epoch reuse, new-epoch reset, disable/re-enable and budget replacement, requiring 16-byte slice offsets [0,16,0,16], detached retirement and one live handle on the healthy ordinary path.
- Six invalid sizes and terminal closure, rejecting before preparation/factory/reset callbacks and capturing a resource once for caller-owned closure.
- Preparation RuntimeException/Error plus opposite cleanup fault, preserving primary identity and admitting healthy fresh allocation.
- Before/after budget-retirement faults, without registering the retired renderer, starting another factory or replaying close.
- Before/after factory faults, factory-owned partial cleanup, no epoch/publication/budget owner and healthy retry.
- Before/after epoch faults plus opposite cleanup fault, with captured cleanup and unchanged primary text/suppression state.
- Preparation replacement/close reentry, preserving successor or terminal ownership.
- Captured old-renderer close admitting a successor, without late outer allocation/clear.
- Factory reentry into allocation/detach/close, requiring factory depth one, unpublished candidate retirement and healthy retry where open.
- Epoch replacement/close followed by an old fault, without retiring the successor.
- Disable during reset followed by normal return or Error, rejecting partial-resource reuse.
- Primary recovery cleanup admitting a successor and then throwing, preserving both primary identity and new ownership.

Backend entry assertions distinguish inactive registered reset targets from detached retirement and empty allocation ownership. Handle/residency counters are CPU models, not OpenGL evidence; fake before-release residue is discarded during fixture disposal without claiming deletion. Actual GlViewport adapters/flags/render/shadow and native constructor cleanup remain source-traced. Existing startup/adoption/fallback/scene/volume/palette/Inspector/native/math definitions retain scope; no native probe or report is added.

## Status and remaining work

All additions remain **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source/Git whitespace/content review adds no runtime evidence. Baseline remains **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Public signatures, headless dependencies, canonical math, UUID/source mappings, configured budgets, schemas, registries and formats remain unchanged. Local-main publication remains pending.

The recorded renderer-slot allocation/reset ordering gap is addressed in source. Full GPU/palette/framebuffer acceptance, frame-time caller/epoch/upload provenance, stale/mismatched receipt disposal, complete frame/profiling and document/control/shadow inventories, general Viewports.close/GL teardown, other representations and W05/W06/W07 audits remain open. Native/physical-input/DPI, resource/performance, Windows 11 and release work remain deferred; no measured overhead or source-audit completion is claimed.
