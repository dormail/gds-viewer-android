# Security

## Reporting a problem

If you find a security problem in GDS Viewer, please report it privately: open the [Security tab](https://github.com/dormail/gds-viewer-android/security) of this repository and click **Report a vulnerability**. Only the maintainer can see these reports. Please don't open a public issue for security problems.

Helpful details are the app version (⋮ → About), your Android version, what happens, and if possible a file or steps that show the problem.

This is a one-person hobby project, so there is no fixed response time, but security reports are taken seriously and fixed first.

## What counts

The app opens files from untrusted sources, such as e-mail attachments and downloads. Examples of what is worth reporting:

- a file that makes the app run code, read other files or leak data;
- a way for another app to read the viewer's private files or the recent files list;
- anything that sends data off the phone (the app has no internet access and asks for no permissions, so this should be impossible).

A file that makes the app slow or show "Not enough memory" is still worth a normal issue, but it is usually not a security problem.

## Supported versions

Only the newest release gets fixes. The APKs on the [Releases page](https://github.com/dormail/gds-viewer-android/releases) are signed with the release key whose fingerprint is listed in the [README](README.md#checking-a-download).
