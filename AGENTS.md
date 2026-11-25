# The Gallery

## Product and interaction direction

The Gallery is a local image gallery for Android handheld gaming devices. The primary target is landscape handhelds and controller-first operation; touchscreen input is also supported. Keep controller focus visible, directional movement predictable, and prompts expressed with the matching controller icons.

## Application structure

- Native Android application written in Java with Android Views. `MainActivity` owns app state, persistence, media discovery/loading, screen construction, and lifecycle; keep input policy in the screen controllers and shared hold/prompt/wheel behavior in `InteractionSystem`.
- `Screen` identifies the active screen. `ScreenController` defines screen entry, hint refresh, and key dispatch. `GalleryController`, `ViewerController`, `SettingsController`, and `ShareController` handle their respective screens.
- `DebugOverlay` is the hidden performance monitor: it draws a text panel on the activity root and reads its snapshot from `MainActivity.debugReport()`. `MainActivity.dispatchKeyEvent` intercepts START for it before screen controllers see the key.
- `HintPanel` describes button and D-pad prompts declaratively; `InteractionSystem` renders those prompts and manages shared key/hold state. Before screen transitions or modal overlays, use the shared `resetButtonHoldForUiChange()` path so a held action cannot leak into the next UI.
- Keep changes focused and avoid duplicating controller/input state across screens. Prefer the existing Java/Views architecture and established patterns.

## Current product behavior

- Choose folders with Android's system document-tree picker (SAF). Persist URI access and the configured source list in app preferences. Settings can add/remove image sources; the gallery scans the direct children of each source, not nested directories, and filters to image MIME types.
- Settings is split into Sources and Appearance sub-pages; LB/RB switch pages, and a page strip under the title shows only the current page name, with a direction arrow and its shoulder glyph (LB left, RB right) at each edge. The glyph stacks are tappable too. Appearance holds the Theme option (Follow system by default, Light, Dark). The choice is persisted, and a forced mode resolves the night resources through a configuration-adjusted context, so a change only rebuilds the visible screen.
- The gallery uses a virtualized `GridView`. Thumbnail decoding runs asynchronously with limited concurrent work and a bounded in-memory LRU cache; full-screen images are decoded asynchronously. Preserve responsiveness for large folders.
- D-pad moves the visible selection; A opens the selected image. In the viewer, left/right changes images, up/down zooms 25 percentage points per step (100% → 125% → 150%) around the image point at the centre of the view and keeps stepping while the direction is held (a step that would cross best fit or 100% snaps onto that size and waits twice as long before the next one) with a small scale readout, and A toggles a details bar along the top edge. B restores best fit on an image that is not at best fit and otherwise returns to the gallery. Either analog stick pans an image that is larger than best fit (the right stick moves the view, the left stick drags the image); at or below best fit the image stays centered. Viewer prompts stay hidden until a face button is pressed and disappear 1.5 s after the last release; only the held button fills its progress ring. X cycles sorting: newest, oldest, name A–Z, name Z–A. The held A/X controller wheels provide image actions and sorting/display options; preserve in-place wheel updates and release-to-dismiss behavior.
- The current UI also provides settings and a local-LAN image sharing screen. Sharing serves the selected image over a local HTTP socket and shows a QR code. This is an explicit product capability, not cloud or remote-service integration.
- A hidden performance monitor toggles on LB+RB+START and closes on START. It overlays app memory, the thumbnail cache tiers, and worker thread state; it shows no on-screen prompt, takes no focus, and passes every other key through to the active screen.
- Folder/source, sort, display-mode, and theme state are persisted as implemented. When changing folder selection semantics or defaults, update the implementation and this document together; do not assume the first-release decisions below are already implemented.

## Android and storage constraints

- Application ID, Android namespace, and Java package: `com.dove.thegallery`. Min SDK 28, compile/target SDK 35, Java 17, landscape orientation. Verify behavior on Android API 30 hardware when possible.
- Use SAF and persisted URI grants for user-selected media. Do not add broad storage permissions.
- Keep image enumeration and decoding off the UI thread. Keep thumbnail work bounded and avoid retaining full-resolution images in the grid.
- The manifest currently declares `INTERNET` for the local sharing server. Do not describe the app as having no network-related functionality; do not add network access/features beyond the intentional local sharing flow without updating the product/security design.
- Project license is AGPL-3.0. Check license obligations before adding dependencies or distribution/network features.
- Prefer build/compile validation; avoid unnecessary tests. Build APKs for feature/bug delivery tasks as required by the project workflow.

## Controller and UI invariants

- Face-button prompt cluster follows the physical layout: X above, Y left, A right, B below, fixed aligned centers. Keep Y's icon visible even with an empty caption; place the secondary D-pad wheel at lower left.
- A held direction is its own input layer: the face buttons drop every normal action while any direction is down, leaving only X (with Up/Left) to jump to the top and B (with Down/Right) to jump to the bottom.
- Hold-operated wheels open on the initiating face-button hold and close on its release. Toggle actions update underlying state and refresh the wheel label/grid in place—do not recreate the wheel or scrim. Keep short-press actions separate from hold actions.
- Before changing screens or showing modal overlays, cancel in-progress holds and swallow their eventual key-up through `resetButtonHoldForUiChange()` / the shared interaction system.
- Viewer prompts are hidden while nothing is held: any face button reveals them, and they disappear 1.5 s after the last face button is released. Only the held face button's ring fills.
- Viewer zoom is stored as a ratio relative to best fit (1 = the whole image fitted to the viewport) and each step changes the pixel scale by 25 percentage points; every step keeps the image point at the centre of the view fixed, best fit and 100% are snap stops that a held zoom dwells on twice as long, zooming runs from half of best fit to three times best fit, and the pixel scale is derived from the view and its bitmap on every apply so no cached measurement can block zooming.
- Keep selected-item feedback clearly visible for controller users and preserve selection when returning between gallery and viewer.

## Known gaps / decisions to revisit

- The code currently launches with the saved set of sources and supports add/remove in Settings; it does not currently provide a distinct “current folder vs explicit default folder” workflow. Decide that behavior before changing persistence semantics.
- B-hold exit timing and other precise long-press thresholds are implementation details; check the controller code before documenting or changing exact durations.
- Touch gestures, remaining controller mappings, and target-device validation are not fully specified. Do not add remapping settings without a product requirement.
