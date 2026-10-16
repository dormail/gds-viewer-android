# Changelog

All notable changes to GDS Viewer. Download APKs from the [Releases page](https://github.com/dormail/gds-viewer-android/releases).

## [Unreleased]

### Added (in progress)

- Recent files list on the start screen and in the ⋮ menu.
- Recent files as shortcuts when you long-press the app icon.
- Theme option: System default, Light or Dark.

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

[Unreleased]: https://github.com/dormail/gds-viewer-android/compare/v0.3...HEAD
[0.3]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.3
[0.2]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.2
[0.1]: https://github.com/dormail/gds-viewer-android/releases/tag/v0.1
