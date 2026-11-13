# The Gallery

## Product and interaction direction

The Gallery is a local image gallery for Android handheld gaming devices. The primary target is landscape handhelds and controller-first operation; touchscreen input is also supported. Keep controller focus visible, directional movement predictable, and prompts expressed with the matching controller icons.

## Application structure

- Native Android application written in Java with Android Views. `MainActivity` owns app state, persistence, media discovery/loading, screen construction, and lifecycle; keep input policy in the screen controllers and shared hold/prompt/wheel behavior in `InteractionSystem`.
- `Screen` identifies the active screen. `ScreenController` defines screen entry, hint refresh, and key dispatch. `GalleryController`, `ViewerController`, `SettingsController`, and `ShareController` handle their respective screens.
- `HintPanel` describes button and D-pad prompts declaratively; `InteractionSystem` renders those prompts and manages shared key/hold state. Before screen transitions or modal overlays, use the shared `resetButtonHoldForUiChange()` path so a held action cannot leak into the next UI.
- Keep changes focused and avoid duplicating controller/input state across screens. Prefer the existing Java/Views architecture and established patterns.

## Current product behavior

- Choose folders with Android's system document-tree picker (SAF). Persist URI access and the configured source list in app preferences. Settings can add/remove image sources; the gallery scans the direct children of each source, not nested directories, and filters to image MIME types.
- The gallery uses a virtualized `GridView`. Thumbnail decoding runs asynchronously with limited concurrent work and a bounded in-memory LRU cache; full-screen images are decoded asynchronously. Preserve responsiveness for large folders.
- D-pad moves the visible selection; A opens the selected image. In the viewer, left/right changes images and B returns to the gallery. X cycles sorting: newest, oldest, name A–Z, name Z–A. The held A/X controller wheels provide image actions and sorting/display options; preserve in-place wheel updates and release-to-dismiss behavior.
- The current UI also provides settings and a local-LAN image sharing screen. Sharing serves the selected image over a local HTTP socket and shows a QR code. This is an explicit product capability, not cloud or remote-service integration.
- Folder/source, sort, and display-mode state are persisted as implemented. When changing folder selection semantics or defaults, update the implementation and this document together; do not assume the first-release decisions below are already implemented.

## Android and storage constraints

- Application ID `com.cooldove.thegallery`; min SDK 28, compile/target SDK 35, Java 17, landscape orientation. Verify behavior on Android API 30 hardware when possible.
- Use SAF and persisted URI grants for user-selected media. Do not add broad storage permissions.
- Keep image enumeration and decoding off the UI thread. Keep thumbnail work bounded and avoid retaining full-resolution images in the grid.
- The manifest currently declares `INTERNET` for the local sharing server. Do not describe the app as having no network-related functionality; do not add network access/features beyond the intentional local sharing flow without updating the product/security design.
- Project license is AGPL-3.0. Check license obligations before adding dependencies or distribution/network features.
- Prefer build/compile validation; avoid unnecessary tests. Build APKs for feature/bug delivery tasks as required by the project workflow.

## Controller and UI invariants

- Face-button prompt cluster follows the physical layout: X above, Y left, A right, B below, fixed aligned centers. Keep Y's icon visible even with an empty caption; place the secondary D-pad wheel at lower left.
- Hold-operated wheels open on the initiating face-button hold and close on its release. Toggle actions update underlying state and refresh the wheel label/grid in place—do not recreate the wheel or scrim. Keep short-press actions separate from hold actions.
- Before changing screens or showing modal overlays, cancel in-progress holds and swallow their eventual key-up through `resetButtonHoldForUiChange()` / the shared interaction system.
- Keep selected-item feedback clearly visible for controller users and preserve selection when returning between gallery and viewer.

## Known gaps / decisions to revisit

- The code currently launches with the saved set of sources and supports add/remove in Settings; it does not currently provide a distinct “current folder vs explicit default folder” workflow. Decide that behavior before changing persistence semantics.
- B-hold exit timing and other precise long-press thresholds are implementation details; check the controller code before documenting or changing exact durations.
- Touch gestures, remaining controller mappings, and target-device validation are not fully specified. Do not add remapping settings without a product requirement.
