# Maps Coordinator

Offline, private GPS map app for Android. No internet permission, no Google services.

- Maps come from an OpenStreetMap `.osm.pbf` file you import (for example from Geofabrik).
- Position comes from Android's own GPS.
- Installs only through GitHub Releases. Settings has an "Open GitHub Releases" button.

Map data © OpenStreetMap contributors (ODbL).

## Build
GitHub Actions builds and signs the APK. Push a tag like `v0.1.0` to publish a release
(notes come from `release-notes/<tag>.md`). Signing needs these repository secrets:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`.
