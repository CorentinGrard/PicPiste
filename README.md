# PicPiste

App Android / Android Auto pour suivre les planeurs du club depuis la golfette (données FLARM via OGN).

Spécifications : [docs/SPECS.md](docs/SPECS.md)

## Structure

- `core/` — Kotlin pur, sans Android, testé : flux OGN (APRS), base OGN DDB, terrain LFNL,
  moteur de statuts (décollage, remorquage, retour, circuit, finale, posé), golfette sur piste,
  alertes, trafic de démo.
- `app/` — l'app Android : écran téléphone (Compose) et écran Android Auto (catégorie
  navigation, radar dessiné sur la surface).
- `tools/osm_to_airfield.py` — génère `core/src/main/resources/airfields/LFNL.json` depuis
  un export OpenStreetMap (Overpass).

## Construire et installer

```bash
./gradlew :core:test            # tests du moteur
./gradlew :app:installDebug     # installe sur le téléphone branché en USB
```

Ou ouvrir le dossier dans Android Studio.

## Android Auto (app installée hors Play Store)

1. Sur le téléphone : Android Auto → Réglages → taper 10 fois sur « Version » → menu
   ⋮ → Paramètres pour les développeurs → cocher **Sources inconnues**.
2. Brancher le téléphone sur la golfette : PicPiste apparaît dans le lanceur Android Auto.

## Tester Android Auto sur l'ordinateur

1. Sur le téléphone, Android Auto → réglages développeur (voir ci-dessus) → ⋮ →
   **Démarrer le serveur de l'unité principale**.
2. `./tools/dhu.sh` : ouvre un écran de voiture (Desktop Head Unit de Google, 1280×720).
   Installation : `sdkmanager "extras;google;auto"` ; la bibliothèque libc++ vient du NDK.

## Enregistrer et rejouer une journée

OGN ne garde pas l'historique des positions : il faut enregistrer le jour même.

- Sur le téléphone : Réglages (⋯) → **Enregistrer la journée** (en direct). Le fichier va
  dans `Android/data/fr.cvvm.picpiste/files/recordings/`.
- Sur un PC ou un serveur, sans téléphone : `./gradlew :core:record` (Ctrl-C pour arrêter),
  fichiers dans `recordings/`. Pour rejouer sur le téléphone :
  `adb push recordings/LFNL-2026-10-25.tsv /sdcard/Android/data/fr.cvvm.picpiste/files/recordings/`
- Réglages → Trafic : **Rejeu** (×1, ×4, ×10) rejoue le dernier enregistrement, avec une
  golfette simulée qui va sur la piste en herbe.

## Mode démo

Réglages (⋯) → Trafic : **Démo** : trafic et golfette simulés autour de LFNL, pour essayer
l'app sans vol (circuit, finale 30 herbe, remorqué depuis la 30 dur gauche, alertes).
