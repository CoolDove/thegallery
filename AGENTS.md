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

- Android SDK / minimum supported Android version.
- App language, UI framework, and build setup.
- Thumbnail presentation and performance limits.
- Exact touch gestures and remaining controller mappings.
