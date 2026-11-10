# Publishing PicPiste on the Play Store

What is ready in the repository, and what remains to do in the Play Console.

## Already ready

- **Play Store bundle**: on every `v*` tag, CI attaches `PicPiste-<version>-play.aab` to the
  GitHub release (next to the APK). Same version as the APK, but **without Android Auto**
  (`play` flavor). Locally: `./gradlew :app:bundlePlayRelease`.
- **Privacy policy**: `docs/PRIVACY.md`, URL to give to the Play Console:
  https://github.com/CorentinGrard/PicPiste/blob/main/docs/PRIVACY.md
- **Graphics**: `docs/play/icon-512.png` (512 × 512 icon), `docs/play/feature-graphic-en.png`
  (1024 × 500 banner, English listing) and `docs/play/feature-graphic.png` (French listing).
- **Screenshots** (1080 × 2160, demo traffic): `docs/play/screenshots-en/` (English) and
  `docs/play/screenshots/` (French).

## Steps in the Play Console

1. **Developer account** ($25, identity check). Personal account: you need a **closed test
   with at least 12 testers for 14 days** before publishing publicly.
2. **Create the app**: name "PicPiste", default language French, free app.
3. **Signing (important)**: on the first release, choose *Use an existing key* and upload the
   PicPiste key (`~/.picpiste/picpiste-release.jks`) with the PEPK tool the console offers.
   Google then signs with the **same key** as the GitHub APKs, so phones that already have
   PicPiste can switch to the Play version without uninstalling. The key also stays the upload
   key for the CI bundles.
4. **Closed test** → create a track and a tester list (Gmail addresses of club members),
   upload the release bundle, then share the opt-in link with the testers.
5. **Store listing**: texts below, icon, banner, at least 2 phone screenshots (radar with
   traffic, arrivals list, airfield editor).
6. **App content**: forms below.

## Store listing

**Short description** (80 characters max)

- EN: `Cross runways safely: live gliders and tow planes, alerts on final.`
- FR: `Traversez les pistes en sécurité : planeurs en direct et alerte de finale.`

**Full description** (plain text, paste as is)

EN:

```
Cross and drive along runways safely. PicPiste shows the golf-cart driver the airfield's live traffic and warns them when a glider or an aircraft is coming onto their runway.

AIRFIELD RADAR
• Gliders, tow planes and aircraft around the field, live from the Open Glider Network (OGN).
• Status of each aircraft: takeoff, on tow, circuit, final (with the most likely runway), landed.
• Arrivals list: who is about to land, and on which runway.

RUNWAY ALERTS
• When the cart is on a runway, PicPiste watches the traffic for you.
• Amber: an aircraft is in the circuit.
• Red with a voice call: a final or a takeoff on your runway.
• Alerts keep working with the screen off.

ANY AIRFIELD
• Import the airfield from OpenStreetMap with its ICAO code.
• Runway and zone editor on a map.
• Share the airfield with other club members as a file.

FREE, NO ACCOUNT, NO ADS
• No data is sent to the developer. Your location stays on the phone.

IMPORTANT
PicPiste is an aid to vigilance: it does not replace looking outside. Only aircraft with a beacon (FLARM, OGN…) and within range of an OGN station are shown.
```

FR:

```
Traversez et longez les pistes en sécurité. PicPiste montre au conducteur de la golfette le trafic du terrain en direct et le prévient quand un planeur ou un avion arrive sur sa piste.

RADAR DU TERRAIN
• Planeurs, remorqueurs et avions autour du terrain, en direct grâce à l'Open Glider Network (OGN).
• Statut de chaque appareil : décollage, remorqué, circuit, finale (avec la piste la plus probable), posé.
• Liste des arrivées : qui va se poser, et sur quelle piste.

ALERTES DE PISTE
• Quand la golfette est sur une piste, PicPiste surveille le trafic pour vous.
• Orange : un appareil est dans le circuit.
• Rouge avec annonce vocale : une finale ou un décollage sur votre piste.
• L'alerte fonctionne aussi écran éteint.

TOUS LES TERRAINS
• Import du terrain depuis OpenStreetMap avec son code OACI.
• Éditeur de pistes et de zones sur fond de carte.
• Partage du terrain avec les autres membres du club par fichier.

GRATUIT, SANS COMPTE, SANS PUBLICITÉ
• Aucune donnée n'est envoyée au développeur. Votre position reste sur le téléphone.

IMPORTANT
PicPiste est une aide à la vigilance : il ne remplace pas l'observation visuelle. Seuls les appareils équipés d'une balise (FLARM, OGN…) et à portée d'une station OGN apparaissent.
```

**Category**: Maps & Navigation. **Contact**: the account's email address.

## App content (forms)

- **Privacy policy**: URL above.
- **App access**: everything works without an account. For reviewers:
  - EN: "On first launch, enter LFNL; live traffic depends on activity at the airfield."
  - FR: « Au premier lancement, saisir LFNL ; le trafic en direct dépend de l'activité du
    terrain. »
- **Ads**: no.
- **Data safety**:
  - Data collected: **none** (nothing is sent to a developer server).
  - Data shared: none. The location stays on the phone; calls to third-party services (OGN,
    OpenStreetMap, Open-Meteo, map tiles) only send the airfield's position.
  - Encrypted in transit: yes (HTTPS; the OGN APRS feed is plain text but carries no user
    data).
  - Data deletion: uninstall the app.
- **Foreground service, "location" type** (declaration required by Google):
  - EN: "While the driver uses the app, the service keeps the GPS on with the screen off to
    know whether the golf cart is on a runway and trigger the alert (final, takeoff). It stops
    when the app is closed."
  - FR: « Pendant que le conducteur utilise l'app, le service garde le GPS actif écran éteint
    pour savoir si la golfette est sur une piste et déclencher l'alerte (finale, décollage).
    Il s'arrête quand l'app est quittée. »
  - Attach a short video: open the app, turn the screen off, the PicPiste notification stays
    visible.
- **Content rating**: IARC questionnaire, nothing sensitive → "Everyone".
- **Target audience**: 18 and over (a tool for drivers).
- **News / health / finance app**: no.

## Android Auto

Refused by Google (November 2026): the app declared itself in the **navigation** category to
draw the radar, a category reserved for turn-by-turn guidance to a destination. The Play
version (`play` flavor) is therefore the phone app alone: no car service, no Android Auto
library, and no mention of Android Auto in the listing, the release notes or the app. In the
Play Console, **Test and release → Advanced settings → Form factors** must not list Android
Auto. Android Auto stays in the GitHub APK (`github` flavor); the README lists the options to
get it accepted in the car.

## Automatic publishing

1. **`v*` tag** → CI uploads the Play bundle to the **internal test** (`internal` track): no
   Google review, available within minutes. Release notes:
   `distribution/whatsnew/whatsnew-en-US` and `whatsnew-fr-FR` (500 characters max), to update
   before each tag.
2. **Check on the phone** the version installed from the Play Store (internal test opt-in
   link): it is the Google-signed version, without development tools.
3. **Actions → Promote → Run workflow**: the same build (same version code, same notes) moves
   to the **closed test** (`alpha` track), after Google review. Tick *dry run* to see what
   would be published without changing anything.
4. **Production**: by hand in the Play Console (promote the closed-test release).

Locally: `scripts/play.py --key <file.json> tracks` lists what each track contains.
CI uses the service account `github-play@picpiste-publish.iam.gserviceaccount.com`
(GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`); without that secret, the upload is skipped.
