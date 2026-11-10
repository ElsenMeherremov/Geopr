# Geogr (Android)

Geogr is a simple proximity alarm. Pick a point on the map (or search for a place), set a radius in metres, and start monitoring. When the phone's reported GPS location enters that radius, Geogr plays the device alarm sound until you stop it in the app or from the persistent notification.

## Mapping and search

- MapLibre Native Android (OpenGL ES renderer) displays the map.
- Map style/tiles: OpenFreeMap Liberty (`https://tiles.openfreemap.org/styles/liberty`). No Google Maps SDK, Google API key, or Google billing account is used.
- Map data attribution is displayed in the app: © OpenStreetMap contributors.
- Place search is a manual, one-shot search using the OpenStreetMap Foundation's public Nominatim service. There is no autocomplete. The app waits at least 1.1 seconds between searches on this installation and sends an identifying User-Agent. Nominatim's public server is a limited community service, not a guaranteed production API; its policy requires no more than one request per second across an application, no autocomplete, and visible attribution. Search may occasionally be unavailable. The map and tap-to-select work independently of search.

OpenFreeMap describes its public map service as free to use without registration or API keys. Public services are provided as-is and may change; internet access is required for live map tiles and search.

## Requirements

- Android 8.0 (API 26) or newer.
- Internet connection for map tiles and place search.
- Location permission for current-position display and monitoring.
- Notifications permission on Android 13+ is recommended so the monitoring notification is visible.

## Build with GitHub Actions

1. Create a GitHub repository.
2. Extract `Geogr.zip` and upload the **contents** of its `geogr_project` folder to the repository root (do not create an extra `geogr_project` folder inside the repository).
3. Confirm this file exists in the repository: `.github/workflows/buildap.yml`.
4. Open the repository's **Actions** tab.
5. Select **Build Geogr APK** and click **Run workflow**. A push to `main` or `master` also starts a build.
6. When the run is green, open it and download the `Geogr-debug-apk` artifact.
7. Extract the artifact ZIP and install `app-debug.apk` on the phone. Android may ask you to allow installation from the browser or file manager used to open it.

No Maps API key or billing secret is required.

## First use

1. Enable internet and Location on the phone.
2. Open Geogr and grant precise location if possible. Approximate location can work but is less accurate.
3. Wait for the map to load. The map initially centers on the first GPS fix.
4. Tap a point on the map, or search for a place/street and select one result.
5. Enter the alarm radius in metres and tap **İzləməni başlat**.
6. Keep the persistent Geogr notification active. When the phone enters the radius, the alarm sounds.
7. Tap **Dayandır** in Geogr or in its notification to stop monitoring and silence the alarm.

## Poco / Xiaomi battery settings

For more reliable background tracking, set Geogr battery usage to **No restrictions** (Settings → Apps → Geogr → Battery saver, wording may differ by HyperOS version). Enable Autostart if your phone exposes that setting. Keep Location enabled. Android and manufacturer power management can still affect background GPS delivery; test the app on a real journey before relying on it.

## Important limitations

- GPS accuracy varies with buildings, tunnels, indoor use, and device settings. The alarm triggers when the reported location is at or inside the configured radius; it cannot guarantee a precise physical boundary.
- Background location is monitored by a location foreground service started while Geogr is open. Do not force-stop the app. Xiaomi/HyperOS may require the battery settings above.
- The app has been prepared for a GitHub Actions build, but this workspace does not contain an Android SDK/emulator or a Poco F6 Pro. A successful CI build and real-device test are still required before treating it as verified on your phone.
