# PicPiste — Development

Design rules: [DESIGN.md](DESIGN.md) · What is left to do: [GitHub issues](https://github.com/CorentinGrard/PicPiste/issues)

## Structure

- `core/` — pure Kotlin, no Android, tested: OGN feed (APRS), OGN device database, airfield
  (any airfield: picked on the welcome screen, OpenStreetMap import, editing; LFNL bundled),
  status engine (takeoff, tow, return, circuit, final, landed), cart on runway, alerts, demo
  traffic and recording replay (for tests, nothing in the app).
- `app/` — the Android app: phone screen (Compose) and Android Auto screen (navigation
  category, radar drawn on the surface). Two flavors:
  - `github`: the full app, with Android Auto (`app/src/github/`), APK of the releases;
  - `play`: the phone app alone, for the Play Store, which refuses our Android Auto screen.
- `tools/osm_to_airfield.py` — generates `core/src/main/resources/airfields/LFNL.json` (the
  airfield bundled with the app) from an OpenStreetMap export (Overpass).

## Build and install

```bash
./gradlew :core:test                # engine tests
./gradlew :app:installGithubDebug   # installs on the phone plugged in over USB (with Android Auto)
./gradlew :app:installPlayDebug     # the Play Store flavor, without Android Auto
```

Or open the folder in Android Studio.

## Test Android Auto on the computer

1. On the phone, Android Auto → developer settings (tap Version 10 times) → ⋮ →
   **Start head unit server**.
2. `./tools/dhu.sh`: opens a car screen (Google's Desktop Head Unit, 1280×720).
   Install: `sdkmanager "extras;google;auto"`; the libc++ library comes from the NDK.

If PicPiste does not show up in a real car: Android Auto asks the Play Store where the app
was installed from, during the car session only. Check `initiatingPackageName` in
`adb shell dumpsys package fr.cvvm.picpiste` (`com.android.shell` = sideloaded), and the
validator details in `adb shell dumpsys activity service com.google.android.projection.gearhead`.
The README lists the options to get a sideloaded build accepted.

## Record a day without a phone

On a PC or a server: `./gradlew :core:record` (Ctrl-C to stop), files in `recordings/`.
Another airfield: `-Pairfield=LFLE` (downloaded from OpenStreetMap) or
`-Pairfield=my-airfield.json` (file shared from the app). OGN keeps no history: record on
the day itself. Recordings are replayed in the engine tests (`Recording`, `ReplayClock`);
the app has had no demo or replay since 1.0.

## OGN database shipped with the app

`app/src/main/assets/ogn-ddb.dat`: a compact copy of the OGN device database, so names show
right after install. CI updates it before each build; by hand: `./gradlew :core:updateDdb`.
Extension `.dat`, not `.gz`: the Android build unpacks and renames `.gz` assets.

## Airfields worldwide

Every Monday, the **Airfields** workflow (`.github/workflows/airfields.yml`) extracts every
airfield in the world from OpenStreetMap: the Geofabrik extracts of each continent (90 GB in
all, one job per continent), reduced to runways, taxiways, aprons, hangars and windsocks
(`tools/airfields/airfields.py extract`), then one file per airfield with a runway
(`build`): `a/<CODE>.json.gz`, in the Overpass response format the app already reads, and
`index.json` (every airfield: name, position, longest runway, codes). Nothing is published
if a continent is missing: the previous week's files stay online.

Published on **GitHub Pages**: `https://corentingrard.github.io/PicPiste/` (about 120 MB,
1 GB limit). The app (`AirfieldDownloader`) takes the published file; for an airfield that is
missing (no code, or added to OSM since) or when Pages cannot be reached: live
OpenStreetMap, as before.

By hand, with Docker:

```bash
docker build -t picpiste-airfields-builder tools/airfields
docker run --rm -v "$PWD:/work" picpiste-airfields-builder extract france-latest.osm.pbf france.osm.pbf
docker run --rm -v "$PWD:/work" picpiste-airfields-builder build site france.osm.pbf
```

Setup (once): repository settings → Pages → Source: **GitHub Actions**, then
Actions → Airfields → Run workflow.

Data © OpenStreetMap contributors, ODbL license: the published files are a derived database
under the same license.

## CI and versions

GitHub Actions (`.github/workflows/ci.yml`) runs the engine tests and builds the signed APK
on every push and pull request; the APK is in the run's artifacts.

Publish a version: `git tag v1.3 && git push origin v1.3` → CI attaches `PicPiste-1.3.apk`
(with Android Auto) and `PicPiste-1.3-play.aab` to the GitHub **Releases**, and sends the
bundle to the Play Store internal test (see [PLAY_STORE.md](PLAY_STORE.md)). Update the
release notes in `distribution/whatsnew/` first.

### Signing key

Every APK (CI and local) is signed with the same PicPiste key, so a version installs over
the previous one. It is in `~/.picpiste/` (`picpiste-release.jks` + `signing.properties`) and
in the repository's GitHub secrets. **Keep a safe copy**: without it, no update is possible
without uninstalling the app on every phone.
