# PicPiste — Politique de confidentialité / Privacy policy

*Dernière mise à jour / Last updated: 20 octobre 2026*

[Français](#français) · [English](#english)

---

## Français

PicPiste est une aide à la vigilance pour les conducteurs de golfette sur un terrain de vol
à voile. L'app n'a **pas de compte**, **pas de publicité**, **pas de statistiques d'usage**
et **aucun serveur PicPiste** : rien de ce que vous faites dans l'app ne nous est envoyé.

### Position du téléphone

- PicPiste utilise la position GPS du téléphone pour savoir si la golfette est sur une piste
  et pour l'afficher sur le radar.
- Cette position **reste sur le téléphone** : elle n'est ni enregistrée ni envoyée à qui que
  ce soit.
- Quand l'app ou Android Auto est utilisé, un service au premier plan (avec une notification
  visible) garde la position et le trafic à jour écran éteint. Il s'arrête quand vous
  quittez l'app.
- À la création d'un terrain, la position sert à centrer la carte.

### Données stockées sur le téléphone

- Les terrains (pistes, zones) et vos réglages. Vous pouvez partager un terrain sous forme de
  fichier : c'est vous qui choisissez à qui l'envoyer.
- Une copie de la base des balises OGN et des tuiles de carte déjà vues (cache).

### Services tiers contactés

L'app se connecte directement à ces services ; ils voient l'adresse IP du téléphone, comme
pour toute connexion Internet :

| Service | Pourquoi | Ce qui est envoyé |
|---|---|---|
| Open Glider Network (aprs.glidernet.org, ddb.glidernet.org) | Trafic en direct et identité des balises | La position du **terrain** (pas du téléphone) et un rayon de 6 km |
| OpenStreetMap (Overpass, Nominatim, API OSM) | Télécharger les pistes d'un terrain | Le code OACI ou la zone du terrain |
| Open-Meteo | Altitude d'un terrain | Les coordonnées du terrain |
| IGN Géoplateforme, OpenStreetMap (tuiles) | Fond de carte de l'éditeur | Les tuiles de carte affichées |

Le trafic affiché vient d'OGN, qui respecte les choix des pilotes (pas de suivi, pas
d'identification) ; PicPiste les applique aussi et n'enregistre aucune position d'aéronef.

### Contact

Corentin Grard — corentin.grard@gmail.com — https://github.com/CorentinGrard/PicPiste

---

## English

PicPiste helps golf-cart drivers stay alert on a gliding airfield. The app has **no
account**, **no ads**, **no analytics** and **no PicPiste server**: nothing you do in the app
is sent to us.

### Phone location

- PicPiste uses the phone's GPS position to tell whether the cart is on a runway and to show
  it on the radar.
- This position **stays on the phone**: it is not stored or sent to anyone.
- While the app or Android Auto is in use, a foreground service (with a visible
  notification) keeps the position and traffic up to date with the screen off. It stops when
  you leave the app.
- When you create an airfield, the position centres the map.

### Data stored on the phone

- Airfields (runways, zones) and your settings. You may share an airfield as a file: you
  choose who receives it.
- A copy of the OGN device database and of map tiles already seen (cache).

### Third-party services

The app connects directly to these services; like any Internet connection, they see the
phone's IP address:

| Service | Why | What is sent |
|---|---|---|
| Open Glider Network (aprs.glidernet.org, ddb.glidernet.org) | Live traffic and device identities | The **airfield's** position (not the phone's) and a 6 km radius |
| OpenStreetMap (Overpass, Nominatim, OSM API) | Download an airfield's runways | The airfield's ICAO code or area |
| Open-Meteo | Airfield elevation | The airfield's coordinates |
| IGN Géoplateforme, OpenStreetMap (tiles) | Editor map background | The map tiles shown |

Traffic comes from OGN, which honours pilots' choices (no tracking, no identification);
PicPiste applies them too and stores no aircraft position.

### Contact

Corentin Grard — corentin.grard@gmail.com — https://github.com/CorentinGrard/PicPiste
