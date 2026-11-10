# PicPiste — Privacy policy

*Last updated: 10 November 2026*

PicPiste helps golf-cart drivers stay alert on a gliding airfield. The app has **no
account**, **no ads**, **no analytics** and **no PicPiste server**: nothing you do in the app
is sent to us.

## Phone location

- PicPiste uses the phone's GPS position to tell whether the cart is on a runway and to show
  it on the radar.
- This position **stays on the phone**: it is not stored or sent to anyone.
- While the app (or, in the GitHub version, Android Auto) is in use, a foreground service
  with a visible notification keeps the position and traffic up to date with the screen off.
  It stops when you leave the app.
- When you create an airfield, the position centres the map.

## Data stored on the phone

- Airfields (runways, zones) and your settings. You may share an airfield as a file: you
  choose who receives it.
- A copy of the OGN device database and of map tiles already seen (cache).

## Third-party services

The app connects directly to these services; like any Internet connection, they see the
phone's IP address:

| Service | Why | What is sent |
|---|---|---|
| Open Glider Network (aprs.glidernet.org, ddb.glidernet.org) | Live traffic and device identities | The **airfield's** position (not the phone's) and a 6 km radius |
| adsb.lol (api.adsb.lol), if "ADS-B traffic" is on | Live ADS-B traffic (airliners, helicopters…) | The **airfield's** position and a 6 km radius |
| GitHub Pages (corentingrard.github.io) | Download an airfield prepared from OpenStreetMap | The airfield's code |
| OpenStreetMap (Overpass, Nominatim, OSM API) | Download an airfield's runways when GitHub Pages does not have it | The airfield's ICAO code or area |
| Open-Meteo | Airfield elevation | The airfield's coordinates |
| IGN Géoplateforme, OpenStreetMap (tiles) | Editor map background | The map tiles shown |

Traffic comes from OGN, which honours pilots' choices (no tracking, no identification);
PicPiste applies them too and stores no aircraft position.

## Contact

Corentin Grard — corentin.grard@gmail.com — https://github.com/CorentinGrard/PicPiste
