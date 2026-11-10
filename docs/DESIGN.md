# PicPiste — Design rules

How PicPiste decides what to show and when to warn. The code and its tests are the
reference for exact values; this page explains the rules and why they are what they are.
Building and releasing: [DEVELOPMENT.md](DEVELOPMENT.md).

## Ground rules

- **Information only.** PicPiste is an awareness aid for the golf-cart driver, not an air
  traffic control tool. Looking outside remains the rule.
- **Any airfield.** Any number of runways, any orientation (parallel, crossing, V-shaped),
  any numbers ("04/22", "14L/32R"). Nothing in the code, the interface or the docs may assume
  a runway orientation, a runway number or a specific airfield. The only exceptions are the
  bundled LFNL file (`core/src/main/resources/airfields/LFNL.json`), the script that produces
  it (`tools/osm_to_airfield.py`) and the tests that load it.
- **No default airfield.** On first launch, the welcome screen asks for an ICAO code, a
  hand-made airfield or a shared file. LFNL is bundled only to avoid an OpenStreetMap request
  when that code is typed.
- **Few alerts, so alerts people listen to.** An alert that asks for nothing ends up ignored.
- **Never a screen that looks up to date when it is not.** Without data, say so.

## Data

- **OGN (Open Glider Network)** relays the FLARM positions of aircraft. The phone opens a
  read-only TCP connection to `aprs.glidernet.org:14580` with a 6 km filter around the
  airfield (`AprsClient.FEED_RADIUS_KM`). It is push, not polling: positions arrive within
  seconds. HTTP APIs polled every minute are too slow to clear a runway.
- **Identity** comes from OGN only, no fleet list: the frame gives the device type and the
  privacy flags; the OGN device database (shipped with the app, refreshed daily) gives the
  registration, competition number and model. Personal trackers' status messages
  (`Reg=`, `Name=`) name devices missing from the database.
- **Privacy**: `no-track` aircraft are not shown, `no-ident` ones are shown without a name.
  No aircraft position is stored.
- **adsb.lol** (optional, on by default) adds ADS-B traffic, mostly useful near big airports
  where OGN relays almost none. An aircraft heard by OGN in the last 15 s keeps its OGN
  positions (`PicPisteEngine.OGN_PRECEDENCE_MS`).
- **Delay**: OGN positions are a few seconds old. Ages and ETAs start from the beacon's
  time, and the display moves airborne aircraft forward (`Prediction`, 10 s at most).
  Statuses and alerts use the received positions only.

## Aircraft statuses

Computed for every aircraft from its position, height above the airfield, ground speed,
climb rate and track (`StatusEngine`). Thresholds are deliberately simple, to be tuned by
replaying real days.

| Status | Rule |
|---|---|
| **Takeoff** | On a runway, on the ground, accelerating past 20 km/h |
| **On tow** | Climbing, with the tow plane within 300 m and 120 m of height (aerotow only; no winch yet) |
| **Tow plane descending** | Tow plane after release, coming back (shown as an arrival) |
| **Circuit** | Within 3 km and below 350 m above the airfield |
| **Final** | Aligned with a runway axis (±30°), within 2.5 km of the threshold, below 200 m, descending |
| **Landed** | Below 25 m and under 25 km/h, after having flown |
| **Signal lost** | No position for 60 s: last position greyed; hidden after 5 min |

- **Runway and direction** come from each aircraft's own track: it is compared with every
  runway in both directions and the best-aligned threshold wins. There is **no "runway in
  use"**: the tow plane and gliders often use different runways or directions a few minutes
  apart.
- **Parallel runways**: the most likely one is shown with its letter ("Final 23R"), averaged
  over the last 20 s so GPS noise does not make it jump. Alerts still cover the whole group,
  since the guess may be wrong. Letters are G/D/C in French, L/R/C in English, L/C/R in files.
- **On a runway, on the ground** = inside its outline, with a 15 m GPS margin for narrow
  runways only. Within 60 m of an end, only when rolling along the axis (taxiways often go
  round runway ends).
- **ETA** = distance / ground speed, shown for circuit and final.
- **Airfield elevation** is calibrated on aircraft stopped on the airfield (median), because
  OGN altitudes and the file's often differ by tens of metres.

## What is shown

- **All traffic** around the airfield, not only the club's, with an icon per aircraft type.
- **Display ceiling**: by default aircraft below 400 m above the airfield, plus everything on
  the ground; adjustable, up to "none". Below this ceiling near the airfield there is only
  circuit traffic, so there is no "local flight" or "outlanding" status.
- The ceiling only hides aircraft from the screen: **alerts look at everything tracked.**
- **Arrivals** list sorted by imminence; **Landed** list with the position on the runway
  (tap to see it on the radar).
- **Radar**: a fixed top view, by default with the main (longest) runway across the screen
  and the top as close to north as possible; it can be turned per airfield in the editor
  (`mapUpDeg`). It never rotates while driving.
- **No OGN data for 60 s** (the server sends at least a line every 20 s): never "circuit
  clear"; instead "No OGN data — look at the sky" and an "OGN · nothing for …" badge.

## Cart alerts

The cart drives everywhere on the airfield. The phone's GPS tells whether it is in an
**alert zone** (`Airfield.alertZones`, `RunwayPresenceDetector`):

- Zones can be drawn in the editor and linked to runways, with the circuit warning on or off.
- Without drawn zones: one zone per runway (its rectangle plus 150 m before each threshold),
  plus the grass between neighbouring parallel runways less than 150 m apart, so crossing a
  group of parallel runways is a single crossing and a single alert.
- Parallel runways less than 300 m apart count as one for alerts; an unknown runway always
  alerts.
- Hysteresis on entering and leaving a zone, so the border does not flicker when driving
  along a runway edge (phone GPS is good to about 5 m). Positions less accurate than 30 m are
  ignored.

Two levels, no more (`AlertEngine`):

| Situation | Reaction |
|---|---|
| Cart off every zone | Nothing |
| Cart in a zone, nothing coming | Nothing |
| Cart in a zone with the circuit warning on, and an aircraft in the circuit | **Amber** border and banner, **no sound**: we do not know yet which runway it will land on |
| Cart in a zone, and a final or a takeoff on one of its runways | **Red** border and banner **and voice** ("Glider on final 23R, 40 seconds"), repeated every 30 s while it lasts |

- One alert at a time: the most imminent (final, then takeoff, then circuit, then by ETA).
- No pre-alert before entering a runway: the driver sees the radar all the time.
- A glider **lost by OGN** while flying in the circuit or on final still counts for 3 min
  (`AlertEngine.LOST_THREAT_MS`): low on final, it often drops below the receiver's horizon.

## Android Auto

- Readable in one second: large elements, no typing.
- Landscape: lists on the left (arrivals, then landed), radar on the right; portrait: radar
  on top, lists below. Zoom buttons in the top bar.
- The car's day / night decides the theme (`CarContext.isDarkMode`), not the phone's setting.
- The radar is drawn in software (`Surface.lockCanvas`), about 16 ms a frame. After an update
  during a car session the host hands the new process a surface still tied to the old one; the
  GPU paths abort the app there, the software path fails cleanly and the car app closes, so
  Android Auto reopens it on a fresh surface instead of showing a frozen radar.
- A tracking service started with the phone locked gets no location; it is started again
  whenever a screen opens or the phone is unlocked.
- Navigation category, so the radar can be drawn freely. Google refuses it on the Play Store,
  so Android Auto only exists in the GitHub version (`github` flavor); the Play version is the
  phone app alone. See the README for installing it in the car.

## Theme and language

- **Theme: Auto / Light / Dark.** Auto = light from sunrise to sunset **at the airfield**,
  dark at night. The light mode is made for full sun: strong contrast, status colours still
  distinct on white, the red border as visible as in dark mode.
- **Language**: English by default, French on a phone set to French (or with Android's
  per-app language). Screens: `values/`, `values-fr/`; statuses, alerts, voice and engine
  messages: `core/i18n/Texts.kt`. Adding a language: a `values-xx/strings.xml` and an object
  in `Texts.kt`.

## Airfields

- An airfield is a JSON file (format v2): code, name, elevation, reference point, outline,
  runways (each with two ends A/B, A = the smaller number, free numbers, threshold
  positions, surface), taxiways, aprons, buildings, windsock, alert zones, map orientation.
- **Source**: the files published weekly on GitHub Pages from OpenStreetMap, otherwise live
  OpenStreetMap (Overpass, then Nominatim + OSM API); elevation from OSM or Open-Meteo.
  OpenStreetMap only gives a first outline: the editor corrects it, on the map or on site
  with the GPS ("Put this point here", refused beyond ±15 m).
- **Sharing**: as a `PicPiste-<code>.json` file through the Android share sheet. An imported
  airfield only replaces the phone's after "Save". No QR code: an airfield is 10 to 30 kB.
