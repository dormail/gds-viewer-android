# GDS Viewer for Android

Look at GDSII chip layouts on your Android phone or tablet. Open a `.gds` or `.gds.gz` file, zoom and rotate around, switch layers on and off, and measure distances. Big layouts of 100–200 MB open in a few seconds. The app only views layouts; it does not edit them.

**Download:** get the latest APK from the [Releases page](https://github.com/dormail/gds-viewer-android/releases). What changed in each version: [CHANGELOG.md](CHANGELOG.md).

> **About this project.** This app was built entirely by Claude, Anthropic's AI coding assistant, with me giving directions and testing. I am not an Android developer and have no background in app development. I work with GDS files a lot in my research and wanted a quick way to look at them on my phone, and the project was also an experiment to see what coding agents can do today. It works well for me, but use it at your own risk: it comes without any warranty (see the [license](LICENSE)).

## Installing

1. On your phone, open the [Releases page](https://github.com/dormail/gds-viewer-android/releases) and download the newest `gds-viewer-X.Y.apk`.
2. Open the downloaded file. Android asks you once to allow "Install unknown apps" for your browser or file manager. Allow it, then tap Install.
3. Updates install the same way, over the old version. Your recent files and settings stay.

If you still have version 0.7 or older installed, uninstall it first: since 0.8 the app installs under a new name, and the old one would otherwise stay on your phone next to it.

The app needs Android 8 or newer.

## What it can do

- **Open files** with the folder button, or from a file manager, an e-mail or another app with "Open with" or "Share". Both `.gds` and gzip-packed `.gds.gz` files work.
- **Recent files:** your last 8 files are on the start screen and under ⋮ → Recent files. Long-press the app icon on your home screen to reopen one of the 4 newest directly.
- **Move around:** drag to pan, pinch to zoom, twist two fingers to rotate.
  - Double-tap to zoom in, tap with two fingers to zoom out.
  - Double-tap and hold, then slide down or up to zoom in or out with one finger.
  - Rotation clicks into place at 0°, 90°, 180° and 270°. The compass button turns the layout back to 0°, and ⋮ → Lock rotation turns rotating off.
  - The "fit to screen" button shows the whole layout again.
- **Layers:** open the layer list to show or hide a layer with a tap. Long-press a layer to show only that one, or use All / None.
- **Cells:** choose which cell to look at. Top cells are listed first, and you can search by name.
- **Ruler:** tap the ruler button and drag between two points to read the length, dx and dy in µm. The ends snap to corners and edges of shapes (you can turn snapping off in the ⋮ menu), and you can drag an end to adjust it.
- **Scale bar** in the corner, and a **light and dark theme** (⋮ → Theme).
- A **built-in sample layout**, so you can try the app without a file of your own.
- ⋮ → **About** shows the version, the license, the credits and links to the source code and the privacy policy.

The app reads the full cell hierarchy: references and arrays (with rotation, mirroring and magnification), polygons, paths and boxes.

## First steps

1. Open a file with the folder button, or try the built-in sample.
2. Tap "fit to screen" to see the whole chip.
3. Open the layer list and long-press a layer to look at it alone.
4. If the file has several cells, open the cell list and pick the top cell or a block you want to inspect.
5. Turn on the ruler and drag between two points to measure.

## Good to know

- **Text labels** are read but not drawn yet.
- **Layer names and colors** from KLayout `.lyp` files are not used yet; layers are shown by number with automatic colors.
- **Large files:** layouts of 100–150 MB have been tested, including one with 100 million shapes when fully expanded. Much bigger files can run out of memory; the app then shows a message.
- **Files from "Open with" or "Share"** are kept as a private copy inside the app (up to 100 MB each, 300 MB in total), because Android only lets the app read them briefly. Removing a file from the recent list deletes its copy.
- The app needs OpenGL ES 3.0, which every phone from the last ten years has.

## Privacy

The app collects no data, shows no ads and has no internet access; it doesn't even ask for any permissions. Your recent files list, the private copies and your settings stay on your phone and are left out of Android backups. Details are in the [privacy policy](https://dormail.github.io/gds-viewer-android/privacy/).

## Checking a download

The APKs on the Releases page are signed with the project's release key. If you want to check that an APK really comes from this project, its signing certificate must have this SHA-256 fingerprint:

```
B2:81:8B:81:CF:BA:3C:43:ED:D7:81:8C:83:18:62:10:29:21:7C:39:B4:1D:69:DE:20:05:49:19:FC:8F:CA:0E
```

Each release also lists the SHA-256 checksum of its APK file.

## Building it yourself

You need a JDK (version 17 or newer) and the Android SDK, with `ANDROID_HOME` set to the SDK folder. Build the APK from the project folder:

```
./gradlew assembleRelease
```

The APK is then at `app/build/outputs/apk/release/app-release.apk`. Move it to your phone yourself, for example with a USB cable or through cloud storage, and open it there to install it. Android asks you once to allow "Install unknown apps" for the app you opened it from, such as Files or Drive.

Your own build is signed with your computer's development key, not with the release key. Android only installs an update signed with the same key, so uninstall the downloaded app before installing your own build, and the other way round.

How the app works inside, the project layout, tests and the release steps are described in [DEVELOPMENT.md](DEVELOPMENT.md).

## Credits

- [KLayout](https://github.com/KLayout/klayout) by Matthias Köfferlein ([klayout.de](https://www.klayout.de)). The way this app handles large layouts follows KLayout's design: the cell hierarchy is kept instead of flattened, only what is on screen is drawn, and shapes smaller than a few pixels are drawn as dots. KLayout was also used to check that the app draws the test layouts correctly. No KLayout code is used; this app was written from scratch.
- Icons: [Material Symbols](https://github.com/google/material-design-icons) by Google, under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- Built with Kotlin, Jetpack Compose, the AndroidX libraries and Gradle, all under the Apache License 2.0.
- Written by [Claude](https://www.anthropic.com/claude) (Anthropic), directed by Matthias Maile.

## License

MIT, see [LICENSE](LICENSE). The icons and libraries listed under Credits keep their own licenses.
