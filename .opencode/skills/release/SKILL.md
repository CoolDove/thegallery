---
name: Release
description: Prepare releases for The Gallery: changelog, version bump, signed APK, and release gating.
---

## Release workflow

1. Read the current version in `app/build.gradle` and the latest release tag.
2. Review user-visible changes since that tag. Exclude docs-only changes, build plumbing, and internal task IDs.
3. Confirm the next `versionName` with the user. Increment `versionCode` by one unless the user says otherwise.
4. Build and verify a signed release APK.
5. Draft a short changelog in the conversation before changing release state:
   - Start with `## The Gallery <version>`.
   - Use `### Highlights` for user-visible capabilities and `### Fixes` for user-visible bugs.
   - Keep bullets short. No commit hashes, ticket IDs, dependency updates, or README-only changes.
   - Do not include local paths, machine names, device addresses, account names, signing details, or other environment information.
6. Do not tag, publish a GitHub release, or distribute the APK until the user explicitly approves the changelog.
7. After approval, commit the version bump and approved changelog state, tag `v<version>`, push, and publish using the approved notes.

## Version and APK naming

- `versionName` is the public version, for example `0.5`.
- `versionCode` must increase monotonically; usually add one.
- Gradle already names the artifact from `versionName`: `TheGallery-<versionName>.apk`. Do not rename it manually.
- Release artifact: `app/build/outputs/apk/release/TheGallery-<versionName>.apk`.
- Debug and release builds share the same filename, so always use the correct build directory.

## Signed release

- Release signing is configured through the `DOVE_ANDROID_KEYSTORE` environment variable and `keystore.properties` beside the keystore. Never hardcode or print keystore paths, aliases, passwords, certificates, or fingerprints.
- Build with the environment available to the Gradle process. If it is missing, start a fresh shell or ask the user to restart their IDE; do not fall back to a hardcoded location.
- Verify the artifact:
  - `aapt dump badging <apk>` for `versionCode` and `versionName`.
  - `apksigner verify --print-certs <apk>` for signing status. Report pass/fail only, not certificate contents.

## Changelog style

Use this shape and keep it concise:

```markdown
## The Gallery <version>

### Highlights
- ...

### Fixes
- ...
```

Prefer the user's wording over implementation details. A release with only a few changes should have only a few bullets.
