# Development notes

Notes for anyone working on the code, human or coding agent. The user-facing description is in [README.md](README.md).

The whole app was written by Claude (Anthropic's coding assistant) for a repository owner who is not an Android developer. Keep that in mind: explain Android-specific things in plain language in commit messages, comments and docs, and prefer simple, well-tested solutions over clever ones.

## Building and testing

- Needs a full JDK 17+ (with `javac`, a runtime alone is not enough) and the Android SDK with `ANDROID_HOME` set. If Gradle fails with "does not provide the required capabilities: [JAVA_COMPILER]", point `JAVA_HOME` at a full JDK.
- `./gradlew assembleRelease` builds the APK, `./gradlew :core:test` runs the unit tests, and `./gradlew lint` checks the Android code.
- **Always test and benchmark release builds.** Debug builds are about 20 times slower with big layouts, because Android turns off most optimizations for debuggable apps.
- `core/src/test/.../security/MaliciousInputTest.kt` holds fast tests with crafted files (loops, zip bombs, huge arrays, garbage geometry). `UntrustedInputProbeTest.kt` is a slow probe and fuzzer that only runs with `GDS_PROBE=1` (`PROBE_ONLY=<name>` picks single cases).
- `StressBenchmark` times big files on the desktop; generate them with `tools/make_stress_gds.py` (see [tools/README.md](tools/README.md)). `tools/check_gds.py` validates a GDS file independently, optionally against KLayout.
- Regenerate the built-in sample with `python3 tools/make_sample_gds.py` (`--big N` makes a stress file with an N × N array).

## How it works

The design follows [KLayout](https://github.com/KLayout/klayout): the cell hierarchy is never flattened, so a layout of a few hundred MB fits in a phone's memory even if it would have billions of shapes when expanded.

1. `GdsReader` reads the binary GDSII records into a compact database (`db/`): shapes are stored in packed number arrays per cell, not as one object each. Rectangles, including straight wires, take 20 bytes.
2. `LibraryIndex` computes every cell's bounding box (per layer too) and splits cells with many shapes into tiles ("chunks") of about 2000 shapes (at most 4096 shapes and 65535 vertices).
3. `MeshBuilder` turns each chunk into triangles and outlines once (`Triangulator`, `PathGeometry`). The meshes are uploaded to the GPU in batches.
4. Whenever the view changes a lot, `SceneBuilder` walks the hierarchy in the background. It skips everything off screen, works out directly which elements of an array are visible, and draws anything smaller than a few pixels into a per-layer bitmap instead (`SceneRaster`). The result is a draw list: which chunks to draw, and where.
5. `SceneRenderer` (OpenGL ES 3.0) draws each chunk once per placement with GPU instancing, so panning and zooming stay smooth. Fills are semi-transparent and drawn once per layer using the stencil buffer, so overlapping shapes do not get darker.
6. The user interface is built with Jetpack Compose, in the `ui/` package. `ViewerViewModel` holds the app state and survives screen rotation.

## Project structure

```
core/                      plain Kotlin, no Android code (so it can be unit-tested on the desktop)
  src/main/kotlin/.../
    db/Library.kt          the compact layout database (cells, packed shape arrays, placements)
    db/GdsReader.kt        reads GDSII binary records into it
    scene/LibraryIndex.kt  bounding boxes and per-cell tiles ("chunks")
    scene/MeshBuilder.kt   chunk to triangles and outlines; MeshCache.kt keeps them until uploaded
    scene/SceneBuilder.kt  what to draw for the current view (culling, level of detail)
    scene/Snapping.kt      ruler snapping
    scene/SceneTypes.kt    the data types shared by these steps
    geometry/Affine.kt     transforms (translate, rotate, mirror, scale)
    geometry/Triangulator.kt  polygon to triangles
    geometry/PathGeometry.kt  GDS paths to triangles and outlines
  src/test/                unit tests, security tests, StressBenchmark
app/                       the Android app
  src/main/
    AndroidManifest.xml
    java/.../MainActivity.kt          entry point, handles "Open with", "Share" and launcher shortcuts
    java/.../render/SceneRenderer.kt  OpenGL drawing
    java/.../data/RecentFiles.kt      recent files list and private copies
    java/.../ui/ViewerViewModel.kt    app state and logic
    java/.../ui/ViewerScreen.kt       top bar, menu and screens
    java/.../ui/LayoutViewer.kt       layout view, gestures, ruler, scale bar
    java/.../ui/LayerSheet.kt         layer list
    java/.../ui/CellPickerDialog.kt   cell chooser
    java/.../ui/theme/                colors and fonts
    res/                              icons, strings, backup rules
    assets/sample.gds                 built-in sample layout
docs/                      GitHub Pages site with the privacy policy
tools/                     Python scripts: sample and stress files, GDS checker
gradle/libs.versions.toml  library versions
```

`...` stands for the package path `io/github/dormail/gdsviewer`.

## Security rules

The app opens files from untrusted sources (mail attachments, downloads), so treat every file as hostile. Keep these properties when changing the code:

- **No permissions at all**, in particular no `INTERNET`. Check the merged manifest of the release APK (`aapt2 dump permissions`) after adding a library.
- **Only `content://` URIs from other apps.** `MainActivity` is exported, and an explicit intent skips the intent filters, so the scheme check in `handleIntent` is what stops other apps from making the viewer open its own private files.
- **Every loop over file data has a limit.** Reference loops are skipped, and there are budgets for the scene walk (`SceneBuilder.MAX_WORK`, `MAX_OVERDRAW`), unpacked size (`GdsReader.MAX_UNPACKED_BYTES`, 2 GiB), layer count (`LibraryBuilder.MAX_LAYERS`), ear clipping (`Triangulator.MAX_EAR_CLIP_STEPS`), snapping (`Snapping.MAX_WORK`) and bitmaps (`SceneRaster.MAX_BITMAPS`). Normal layouts stay far below them; the stress files must not hit them.
- **Meshes are checked before they reach the GPU driver** (`ChunkMesh.indicesInRange`).
- **Errors never crash the app:** loading and drawing catch exceptions and out-of-memory errors and show a message.
- **No file names or layout data in the system log.**
- **App data is excluded from backups and device transfers** (`allowBackup`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`). The privacy policy in `docs/privacy/` promises this; update it if that ever changes.

## App ID, signing and releases

- The app ID is `io.github.dormail.gdsviewer` (it was `com.example.gdsviewer` up to 0.7).
- Release builds are signed with the release key when these Gradle properties are set, normally in `~/.gradle/gradle.properties` (never in the repository): `gdsviewer.storeFile`, `gdsviewer.storePassword`, `gdsviewer.keyAlias`, `gdsviewer.keyPassword`. Without them the release build falls back to the debug key. The release certificate's SHA-256 is listed in the README.
- Collect user-visible changes under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), written for users, not developers. Release only when the owner asks for it.
- For a release: move the Unreleased items into a new version section, bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, create an annotated tag `vX.Y`, push it, and create a GitHub Release named "GDS Viewer X.Y" with the changelog section and the SHA-256 of the APK as notes and `gds-viewer-X.Y.apk` (the release build) attached.
- Google Play would additionally need an app bundle (`./gradlew bundleRelease`), a store listing and the privacy policy URL.

## Ideas for next steps

- Draw text labels.
- Read KLayout `.lyp` layer files for layer names and colors.
- Custom layer colors chosen in the app.
- Save the processed index of recent files, so they reopen even faster.
- Reopen the last file when the app starts.
- Measuring along edges, and area measurement.
- Files with about a million empty cells still run out of memory (the app shows a message).

## Android terms in plain language

- **APK**: the installable app file, the Android equivalent of an `.exe` file. An **app bundle** (`.aab`) is the format Google Play wants instead.
- **Gradle**: the build tool. `./gradlew` is the project's own copy of it, so it does not need to be installed separately.
- **JDK**: the Java Development Kit. Kotlin code is compiled with it, so the build needs it even though the code is Kotlin.
- **SDK**: the Android Software Development Kit, the libraries and tools that Android apps are built against.
- **Jetpack Compose**: the library for the user interface. It describes the screen as Kotlin functions.
- **Intent**: a message that starts an app screen, for example "open this file". **Exported** screens can be started by other apps.
- **Content URI** (`content://…`): an address for a file that another app hands over together with a temporary right to read it.
- **applicationId**: the app's unique name on a phone and on Google Play, written as a reverse domain name.

## Learning resources

- Android Basics with Compose (free course): https://developer.android.com/courses/android-basics-compose/course
- Kotlin documentation: https://kotlinlang.org/docs/getting-started.html
