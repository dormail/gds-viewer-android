# Changelog

All notable changes to GDS Viewer. Download APKs from the [Releases page](https://github.com/dormail/gds-viewer-android/releases).

## [Unreleased]

### Added

- The project is now under the MIT license, and the README credits [KLayout](https://github.com/KLayout/klayout), whose design the large-file support follows, and the icons and libraries it uses.
- A privacy policy, published with GitHub Pages from the `docs/` folder.

### Changed

- The app's data (recent files and their copies, settings) is no longer included in Android backups, so confidential layouts do not end up in a cloud backup.

## [0.7] - 2026-10-16

### Added

- Large files: layouts of 100-200 MB now open and show completely. A 150 MB standard-cell layout (100 million shapes when expanded) opens in about 2 seconds. The app works like [KLayout](https://github.com/KLayout/klayout): the cell hierarchy is kept instead of expanded, only what is on screen is drawn, and shapes smaller than a few pixels are drawn as dots.
- While a file is read, the progress is shown in MB.
- A thin progress bar appears at the top when preparing a new view takes a moment.

### Changed

- The APK is now an optimized release build (gds-viewer-0.7.apk instead of …-debug.apk). It installs over earlier versions as usual and is much faster with big layouts.
- The app now needs OpenGL ES 3.0, which every phone from the last ten years supports.

### Removed

- The "Layout too large — only part of it is shown" warning, since layouts are no longer cut off.

## [0.6] - 2026-10-16

### Changed

- One-finger zoom (double-tap and hold, then slide) now keeps the spot you touched under your finger. It moves with your finger while the layout zooms around it, instead of zooming around the point of the first tap.

## [0.5] - 2026-10-16

### Added

- Theme option under ⋮ → Theme: System default (the default), Light or Dark. The layout canvas follows the theme; in light mode it has a white background and darker layer colors that stay readable on white.

### Fixed

- The "remove" (×) button in the recent files list was hard to see on the dark background.

## [0.4] - 2026-10-16

### Added

- Recent files: your last 8 files are listed on the start screen and under ⋮ → Recent files. Tap one to reopen it, or × to remove it.
- Long-press the app icon on the home screen to open one of your 4 most recent files directly.
- Files opened via "Open with" or "Share" (where Android only grants temporary access) are kept as a private copy (up to 100 MB each, 300 MB in total) so they can be reopened later.

## [0.3] - 2026-10-16

### Added

- Double-tap to zoom in, and two-finger tap to zoom out. The zoom is animated.
- Double-tap and hold, then slide down or up to zoom in or out, like in Google Maps.
- Rotation snaps to 0°, 90°, 180° or 270°. You need to twist past about 20° to rotate away from a snap point. The view snaps back when you are close to it and settles there when you let go nearby, so pinching no longer rotates the view by accident.

### Changed

- Panning with one finger starts only after a small movement, so taps no longer nudge the view.
- In ruler mode, a single tap no longer clears the ruler. Start a new drag to replace it, or turn ruler mode off.

## [0.2] - 2026-10-16

### Added

- Open .gds and .gds.gz files directly from file managers ("Open with → GDS Viewer"), from e-mail attachments and from "Share".

### Fixed

- A code-quality (lint) issue in how numbers are formatted in the layer list.

## [0.1] - 2026-10-16

First version.

### Added

- Open GDSII files (.gds and .gds.gz) with the system file picker.
- A built-in sample layout to try the app without a file of your own.
- Fast OpenGL rendering with semi-transparent layer fills and outlines.
- Layer list: tap to show or hide a layer, long-press to show only that layer, and All / None buttons.
- Pinch to zoom, pan, two-finger rotate, fit to screen, a compass to reset the rotation, and a rotation lock.
- Ruler that snaps to shape corners and edges and shows the length, dx and dy.
- Scale bar.
- Cell picker with top cells listed first and a search box.
- Support for cell hierarchies (SREF and AREF, with rotation, mirroring and magnification), all path types, and boxes.

### Fixed

- A crash when opening the layer list.

[Unreleased]: https://github.com/dormail/gds-viewer-android/compare/v0.7...HEAD
[0.7]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.7
[0.6]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.6
[0.5]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.5
[0.4]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.4
[0.3]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.3
[0.2]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.2
[0.1]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.1
