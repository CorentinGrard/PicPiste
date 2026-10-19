# PicPiste

App Android / Android Auto pour suivre les planeurs du club depuis la golfette (données FLARM via OGN).

Spécifications : [docs/SPECS.md](docs/SPECS.md)

## Structure

- `core/` — Kotlin pur, sans Android, testé : flux OGN (APRS), base OGN DDB, terrain (tout
  terrain : import OpenStreetMap, édition ; LFNL intégré par défaut),
  moteur de statuts (décollage, remorquage, retour, circuit, finale, posé), golfette sur piste,
  alertes, trafic de démo.
- `app/` — l'app Android : écran téléphone (Compose) et écran Android Auto (catégorie
  navigation, radar dessiné sur la surface).
- `tools/osm_to_airfield.py` — génère `core/src/main/resources/airfields/LFNL.json` (le
  terrain intégré à l'app) depuis un export OpenStreetMap (Overpass).

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
  fichiers dans `recordings/`. Autre terrain : `-Pairfield=LFLE` (téléchargé depuis
  OpenStreetMap) ou `-Pairfield=mon-terrain.json` (fichier partagé depuis l'app). Pour rejouer sur le téléphone :
  `adb push recordings/LFNL-2026-10-25.tsv /sdcard/Android/data/fr.cvvm.picpiste/files/recordings/`
- Réglages → Trafic : **Rejeu** (×1, ×4, ×10) rejoue le dernier enregistrement, avec une
  golfette simulée qui va sur une piste.

## Changer de terrain

Réglages (⋯) → Terrain → **Modifier** :

- ⋯ → **Chercher un terrain par son code** (ex. LFLE) : dessin OpenStreetMap, à vérifier.
- Toucher une piste, un seuil, un hangar… pour le sélectionner ; glisser ses points.
  Sur place : rouler jusqu'au point et **Placer ce point ici** (GPS du téléphone).
- **Enregistrer** : le radar et les alertes utilisent ce terrain.
- ⋯ → **Partager** envoie `PicPiste-XXXX.json` ; l'autre conducteur l'ouvre avec PicPiste
  (ou ⋯ → Importer un fichier), vérifie, puis enregistre.
- ⋯ → **Revenir au terrain intégré** remet LFNL.

## Mode démo

Réglages (⋯) → Trafic : **Démo** : trafic et golfette simulés autour du terrain choisi, pour
essayer l'app sans vol (circuit, finale, remorqué, alertes).

## Reprise du travail

Ce qui reste à faire : [docs/NEXT.md](docs/NEXT.md).

## CI et versions

GitHub Actions (`.github/workflows/ci.yml`) lance les tests du moteur et construit l'APK
signé à chaque push et pull request ; l'APK est dans les artefacts du run.

Publier une version : `git tag v0.2 && git push origin v0.2` → l'APK `PicPiste-0.2.apk`
apparaît dans les **Releases** GitHub, à télécharger et installer sur les téléphones.

### Clé de signature

Tous les APK (CI et local) sont signés avec la même clé PicPiste, pour qu'une version
s'installe par-dessus la précédente. Elle est dans `~/.picpiste/` (`picpiste-release.jks` +
`signing.properties`) et dans les secrets GitHub du dépôt. **En garder une copie à l'abri** :
sans elle, plus de mise à jour possible sans désinstaller l'app sur chaque téléphone.
