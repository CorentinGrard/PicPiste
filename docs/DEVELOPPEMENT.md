# PicPiste — Développement

Spécifications : [SPECS.md](SPECS.md) · Ce qui reste à faire : [NEXT.md](NEXT.md)

## Structure

- `core/` — Kotlin pur, sans Android, testé : flux OGN (APRS), base OGN DDB, terrain (tout
  terrain : choisi à l'accueil, import OpenStreetMap, édition ; LFNL intégré),
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

## Tester Android Auto sur l'ordinateur

1. Sur le téléphone, Android Auto → réglages développeur (voir le README) → ⋮ →
   **Démarrer le serveur de l'unité principale**.
2. `./tools/dhu.sh` : ouvre un écran de voiture (Desktop Head Unit de Google, 1280×720).
   Installation : `sdkmanager "extras;google;auto"` ; la bibliothèque libc++ vient du NDK.

## Enregistrer une journée sans téléphone

Sur un PC ou un serveur : `./gradlew :core:record` (Ctrl-C pour arrêter), fichiers dans
`recordings/`. Autre terrain : `-Pairfield=LFLE` (téléchargé depuis OpenStreetMap) ou
`-Pairfield=mon-terrain.json` (fichier partagé depuis l'app). Pour rejouer sur le téléphone :

```bash
adb push recordings/LFNL-2026-10-25.tsv /sdcard/Android/data/fr.cvvm.picpiste/files/recordings/
```

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
