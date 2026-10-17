# GDS Viewer for Android

GDS Viewer is a read-only GDSII layout viewer for Android phones and tablets. Open a chip layout file from your phone's storage, look at it, zoom and rotate around, measure distances, and switch layers on and off. It does not edit layouts.

**Download:** the latest APK is on the [Releases page](https://github.com/dormail/gds-viewer-android/releases). What changed in each version: [CHANGELOG.md](CHANGELOG.md).

## Features

- Open `.gds` and `.gds.gz` files with the system file picker.
- Recent files: the last 8 files are listed on the start screen and under ⋮ > Recent files. Files opened from other apps ("Open with" / "Share") are kept as a private copy (up to 100 MB each).
- Launcher shortcuts: long-press the app icon to reopen one of your 4 newest files.
- Theme: ⋮ > Theme switches between System default, Light and Dark. The layout canvas and layer colors follow it.
- A built-in sample layout, so you can try the app right away.
- Layer list: tap a layer to show or hide it, long-press to show only that layer, and use All / None to switch everything at once.
- Pinch to zoom, drag to pan, and twist with two fingers to rotate.
  - Double-tap to zoom in, two-finger tap to zoom out, or double-tap and hold, then slide down/up to zoom in/out (the spot under your finger moves with it).
  - Rotation snaps to 0°, 90°, 180° and 270°: push a twist past about 20° to break out, and it snaps back when you get close or release near an angle.
  - Rotation can be locked in the ⋮ menu.
  - The compass button resets the rotation to zero.
- "Fit to screen" button to frame the whole layout.
- Ruler: tap the ruler icon, then drag to measure. A tap does not remove the ruler.
  - Drag an endpoint to adjust it.
  - Endpoints snap to shape corners and edges. Snapping can be turned off in the ⋮ menu.
  - The ruler shows length, dx and dy in µm.
- Scale bar.
- Choose which cell to view. Top cells are listed first, and the list is searchable.
- Reads the cell hierarchy: SREF and AREF references (with rotation, mirroring and magnification), all path types, and BOX elements.

**Not yet:** text labels are read from the file but not drawn, there is no editing, and layer names and colors from `.lyp` files are not used.

### First steps in the app

1. Start the app and open a file with the system file picker, or just explore the built-in sample layout.
2. Use "fit to screen" to see the whole chip.
3. Open the layer list and long-press a layer to isolate it.
4. If the file has several cells, open the cell picker and choose the top cell (or any sub-block you want to inspect).
5. Turn on the ruler, drag between two points, and read the length in µm.

## Building and installing

You need a JDK (version 17 or newer) and the Android SDK, with `ANDROID_HOME` set to the SDK folder. Build the APK from the project folder:

```
./gradlew assembleRelease
```

The APK is then at `app/build/outputs/apk/release/app-release.apk`. Move it to your phone yourself, for example with a USB cable or through cloud storage, and open it there to install it. Android asks you once to allow "Install unknown apps" for the app you opened it from, such as Files or Drive. Developer options are only needed if you install over USB with `adb install`.

## How it works

For the curious, here is the pipeline. It works like [KLayout](https://github.com/KLayout/klayout): the cell hierarchy is never flattened, so a layout of a few hundred MB fits in a phone's memory even if it would have billions of shapes when expanded.

1. `GdsReader` reads the binary GDSII records into a compact database (`db/`): shapes are stored in packed number arrays per cell, not as one object each. Rectangles, including straight wires, take 20 bytes.
2. `LibraryIndex` computes every cell's bounding box (per layer too) and splits cells with many shapes into tiles ("chunks") of about 2000 shapes.
3. `MeshBuilder` turns each chunk into triangles and outlines once (`Triangulator`, `PathGeometry`). The meshes are uploaded to the GPU in batches.
4. Whenever the view changes a lot, `SceneBuilder` walks the hierarchy in the background. It skips everything off screen, works out directly which elements of an array are visible, and draws anything smaller than about 4 pixels into a per-layer bitmap instead. The result is a "draw list": which chunks to draw, and where.
5. `SceneRenderer` (OpenGL ES 3.0) draws each chunk once per placement with GPU instancing, so panning and zooming stay smooth. Fills are semi-transparent and drawn once per layer using the stencil buffer, so overlapping shapes do not get darker.
6. The user interface is built with Jetpack Compose, in the `ui/` package.

## Project structure

```
core/                      plain Kotlin, no Android code
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
  src/test/                unit tests (StressBenchmark times big files, see tools/README.md)
app/                       the Android app
  src/main/
    AndroidManifest.xml
    java/.../MainActivity.kt  entry point
    java/.../render/SceneRenderer.kt  OpenGL drawing
    java/.../ui/ViewerViewModel.kt   app state and logic (survives screen rotation)
    java/.../ui/ViewerScreen.kt      top bar and screens
    java/.../ui/LayoutViewer.kt      layout view, gestures, ruler, scale bar
    java/.../ui/LayerSheet.kt        layer list
    java/.../ui/CellPickerDialog.kt  cell chooser
    java/.../ui/theme/               colors and fonts
    res/                     icons and strings
    assets/sample.gds        built-in sample layout
tools/make_sample_gds.py   generates the sample layout
tools/make_stress_gds.py   generates big test layouts (100-200 MB)
gradle/libs.versions.toml  library versions
build.gradle.kts           build configuration (root and app)
settings.gradle.kts        project configuration
```

The `...` stands for the package path (for example `com/example/gdsviewer`). Look at the actual folders to see the exact layout.

To regenerate the sample layout:

```
python3 tools/make_sample_gds.py          # normal sample
python3 tools/make_sample_gds.py --big N  # stress-test file with many shapes
```

## Android terms in plain language

Some words you will see along the way:

- **APK**: the installable app file, the Android equivalent of an `.exe` file.
- **Gradle**: the build tool. `./gradlew` is the project's own copy of it, so you do not need to install it separately.
- **JDK**: the Java Development Kit. Kotlin code is compiled with it, so the build needs it even though you write Kotlin.
- **SDK**: the Android Software Development Kit, the libraries and tools that Android apps are built against.
- **Jetpack Compose**: the library for building the user interface. It describes the screen as Kotlin functions, which is similar in spirit to describing a layout in code.
- **applicationId**: the app's unique name on a phone, written as a reverse domain name (see the note below).

## Limits

- File size: tested with files of 100-150 MB, including a standard-cell layout with 100 million shapes when expanded. Much bigger files may run out of memory; the app then shows an error.
- Text labels are not drawn.
- Coordinates are drawn as 32-bit floats relative to a point near the screen, so they stay precise when zoomed in.
- The app needs OpenGL ES 3.0, which every phone from the last ten years has.

## Ideas for next steps

- Draw text labels.
- Read [KLayout](https://github.com/KLayout/klayout) `.lyp` layer files for layer names and colors.
- Custom layer colors chosen in the app.
- Save the processed index of recent files, so they reopen even faster.
- Remember the last opened file.
- Double-tap to zoom.
- Measuring along edges, and area measurement.

## Note on the app ID

The app's ID is `com.example.gdsviewer`. It is fine for development, but change it in `app/build.gradle.kts` before you ever publish the app to Google Play, because the ID must be unique.

## Learning resources

- Android Basics with Compose (free course): https://developer.android.com/courses/android-basics-compose/course
- Kotlin documentation: https://kotlinlang.org/docs/getting-started.html

## Credits

- [KLayout](https://github.com/KLayout/klayout) by Matthias Köfferlein ([klayout.de](https://www.klayout.de)). The way this app handles large layouts follows KLayout's design: the cell hierarchy is kept instead of flattened, only what is on screen is drawn, and shapes smaller than a few pixels are drawn as dots. KLayout was also used to check that the app draws the test layouts correctly. No KLayout code is used; this app was written from scratch.
- Icons: [Material Symbols](https://github.com/google/material-design-icons) by Google, under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- Built with Kotlin, Jetpack Compose, the AndroidX libraries and Gradle, all under the Apache License 2.0.

## Privacy

The app collects no data and has no internet access. The privacy policy (needed for Google Play) is in [docs/privacy/index.html](docs/privacy/index.html) and is published with GitHub Pages at https://dormail.github.io/gds-viewer-android/privacy/ once Pages is turned on for the repository (Settings → Pages → Deploy from a branch → `main`, folder `/docs`).

## License

MIT, see [LICENSE](LICENSE). The icons and libraries listed under Credits keep their own licenses.
