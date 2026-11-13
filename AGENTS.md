# The Gallery

## Project direction

The Gallery is an offline image viewer for Android handheld gaming devices, primarily used to browse game screenshots. The first release targets landscape handheld consoles; controller usability is the primary interaction design focus, with touchscreen support as well.

## First-release product scope

- Browse images in a user-selected directory using Android's system folder picker (SAF). Persist access permission so the selected default folder remains available across launches.
- Users can switch the current folder. Switching folders affects the current session only unless the user explicitly sets that folder as the default.
- On first launch, prompt the user to choose a folder. If they cancel, show an empty state with an action to choose one.
- Launch directly into the default folder when one has been set. If it is missing or inaccessible, explain the problem and prompt the user to choose another folder.
- Show image files directly inside the selected folder only. Do not recurse into subfolders and do not include videos.
- Support common image formats that Android can decode.
- Provide grid and full-screen viewing modes. In grid mode, D-pad navigation selects an image and A opens it full-screen. In full-screen mode, left/right on the D-pad changes images and B returns to the grid.
- Remember the selected image when returning to the grid. When opening a folder, initially select the newest image.
- Full-screen images initially use fit-to-screen display; zoom controls are out of first-release scope.
- Support touchscreen interaction, while prioritizing controller operation in the design.
- X cycles through multiple sorting options: modified time newest-first (default), modified time oldest-first, name A–Z, and name Z–A.
- Holding B in either mode displays a progress indicator and exits the app after 1.5 seconds. No confirmation dialog is needed.
- The app has no network-related functionality; image and folder browsing are local.

## Development constraints

- Respect Android scoped storage and SAF persisted URI permissions; do not request broad storage access for folder browsing.
- Keep browsing responsive when loading image thumbnails and full-screen images.
- Prefer small, focused changes and avoid unnecessary tests; compile/build validation is preferred when available.
- Project license: AGPL-3.0. Check license obligations before adding dependencies or distribution/network features.

## Technical decisions pending

- Android SDK: min SDK 28 (Android 9), target SDK 35 (Android 15). Target hardware includes Android 11 / API 30; verify on API 30 devices. Distribution is outside Google Play, but target 35 is selected conservatively.
- Native Android project using Java and Android Views to give direct control over controller focus and standard key events. Default Android controller key mappings are used; do not add remapping settings unless required later.
- Controller-first input behavior is a top-level requirement. Keep focus visible and directional movement predictable; validate D-pad, A/B/X, and long-press behavior on target hardware.
- For controller prompts, use the corresponding button icon rather than text labels such as `[B]`.
- Keep the face-button prompt cluster in the physical controller layout: X above, Y left, A right, B below, with fixed aligned centers; show Y's icon even when its caption is empty. Place the secondary D-pad wheel at the lower left.
- Wheel menus are hold-operated: the initiating face button opens the wheel and its release closes it. Toggle selections (such as sorting or display mode) update the underlying state and refresh the wheel label/grid in place; never close/recreate/reopen the wheel or its scrim to apply a toggle. This avoids accumulating dark overlays and preserves one-release dismissal behavior. Keep existing short-press actions separate from hold actions.
- Thumbnail presentation and performance limits. Expect folders containing thousands of images; first phase should use a virtualized grid, asynchronous thumbnail loading, and a bounded in-memory thumbnail cache rather than loading full-resolution images into the grid.
- Exact touch gestures and remaining controller mappings.

## Android project baseline

- Application ID: `com.cooldove.thegallery`.
- Compile SDK: 35; target SDK: 35; min SDK: 28.
- Landscape orientation.
- Java + Android Views; no network permission or network-related dependencies.
- Use a virtualized grid and asynchronous thumbnail loading with bounded caching for large folders.
