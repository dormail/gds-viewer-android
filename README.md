# GDS Viewer for Android

GDS Viewer is a read-only GDSII layout viewer for Android phones and tablets. Open a chip layout file from your phone's storage, look at it, zoom and rotate around, measure distances, and switch layers on and off. It does not edit layouts.

## Features

- Open `.gds` and `.gds.gz` files with the system file picker.
- A built-in sample layout, so you can try the app right away.
- Layer list: tap a layer to show or hide it, long-press to show only that layer, and use All / None to switch everything at once.
- Pinch to zoom, drag to pan, and twist with two fingers to rotate.
  - Rotation can be locked in the ⋮ menu.
  - The compass button resets the rotation to zero.
- "Fit to screen" button to frame the whole layout.
- Ruler: tap the ruler icon, then drag to measure.
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

## Running the app on your phone (with Android Studio)

This is the recommended path if you are new to Android development.

1. Install Android Studio from https://developer.android.com/studio. On Linux, download the tarball, extract it, and run `bin/studio.sh` from the extracted folder.
   - The JDK and Android SDK used for command-line builds are already installed in `~/Android/jdk` and `~/Android/Sdk`.
   - Android Studio should detect and reuse `~/Android/Sdk`. If it asks for an SDK location, point it there.
2. In Android Studio, choose **File → Open** and select this project folder.
3. Wait for "Gradle sync" to finish. The first time it downloads dependencies, which can take a few minutes.
4. Prepare your phone:
   1. Go to **Settings → About phone** and tap "Build number" seven times. This enables Developer options.
   2. Go to **Settings → System → Developer options** and turn on "USB debugging".
   3. Connect the phone to your computer with a USB cable and accept the "Allow USB debugging?" prompt on the phone.
5. In the toolbar of Android Studio, select your phone in the device dropdown, then press the green ▶ **Run** button.

**Alternative:** you can debug over Wi-Fi instead of USB. Turn on **Developer options → Wireless debugging** on the phone, then use "Pair devices using Wi-Fi" in Android Studio.

## Building from the command line (without Android Studio)

Run these from the project folder:

```
export JAVA_HOME=~/Android/jdk ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # runs the unit tests
```

The `adb install` line needs the phone connected over USB with USB debugging enabled, as above. If `adb` cannot see the phone, run `~/Android/Sdk/platform-tools/adb devices` and check that the phone is listed as `device` (not `unauthorized`). If it says `unauthorized`, unlock the phone and accept the debugging prompt.

You can also skip `adb`: copy the APK file to the phone (by USB, cloud storage or e-mail) and open it there. Android will ask you to allow "install unknown apps" for the app you opened it from.

## How it works

For the curious, here is the pipeline in four steps:

1. `GdsParser` reads the binary GDSII records and turns them into cells, polygons, paths and references.
2. `LayoutFlattener` walks the cell hierarchy and applies each instance's transform. It converts paths to polygons and splits every polygon into triangles using `Triangulator` (ear clipping). A cell that is used many times is triangulated once and copied for each use.
3. The triangles and outlines are uploaded to the GPU once. `LayoutRenderer` (OpenGL ES 2.0) redraws them using a single transformation matrix, so zoom, pan and rotate stay smooth even for large layouts. Fills are semi-transparent and drawn once per layer using the stencil buffer, so overlapping shapes do not get darker.
4. The user interface is built with Jetpack Compose, in the `ui/` package.

## Project structure

```
core/                      plain Kotlin, no Android code
  src/main/kotlin/.../
    gds/GdsModel.kt        data classes for the layout (cells, shapes, references)
    gds/GdsParser.kt       reads GDSII binary records
    geometry/Affine.kt     transforms (translate, rotate, mirror, scale)
    geometry/Triangulator.kt  polygon to triangles
    geometry/PathGeometry.kt  converts GDS paths to polygons
    geometry/LayoutFlattener.kt  flattens the hierarchy
    geometry/Snapper.kt    ruler snapping
    geometry/Buffers.kt    helpers for GPU-ready float buffers
  src/test/                unit tests
app/                       the Android app
  src/main/
    AndroidManifest.xml
    java/.../MainActivity.kt  entry point
    java/.../render/LayoutRenderer.kt  OpenGL drawing
    java/.../ui/ViewerViewModel.kt   app state and logic (survives screen rotation)
    java/.../ui/ViewerScreen.kt      top bar and screens
    java/.../ui/LayoutViewer.kt      layout view, gestures, ruler, scale bar
    java/.../ui/LayerSheet.kt        layer list
    java/.../ui/CellPickerDialog.kt  cell chooser
    java/.../ui/theme/               colors and fonts
    res/                     icons and strings
    assets/sample.gds        built-in sample layout
tools/make_sample_gds.py   generates the sample layout
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
- **adb**: the Android Debug Bridge, a command-line tool that talks to the phone over USB or Wi-Fi.
- **Jetpack Compose**: the library for building the user interface. It describes the screen as Kotlin functions, which is similar in spirit to describing a layout in code.
- **Gradle sync**: Android Studio reading the build files and downloading libraries. Run it again after changing `build.gradle.kts` or `gradle/libs.versions.toml`.
- **applicationId**: the app's unique name on a phone, written as a reverse domain name (see the note below).

## Limits

- Very large layouts: the app stops converting after about 32 million floats (roughly 128 MB) and shows a warning that only part of the layout is shown. The limit is the constant `DEFAULT_MAX_FLOATS` in `LayoutFlattener`.
- Coordinates are drawn as 32-bit floats. This is precise enough down to nanometer scale for chips up to a few centimeters across.

## Ideas for next steps

- Draw text labels.
- Read KLayout `.lyp` layer files for layer names and colors.
- Custom layer colors chosen in the app.
- "Open with" from file managers and e-mail attachments.
- Remember the last opened file.
- Double-tap to zoom.
- Measuring along edges, and area measurement.

## Note on the app ID

The app's ID is `com.example.gdsviewer`. It is fine for development, but change it in `app/build.gradle.kts` before you ever publish the app to Google Play, because the ID must be unique.

## Learning resources

- Android Basics with Compose (free course): https://developer.android.com/courses/android-basics-compose/course
- Kotlin documentation: https://kotlinlang.org/docs/getting-started.html
