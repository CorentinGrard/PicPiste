# PicPiste — Développement

Spécifications : [SPECS.md](SPECS.md) · Ce qui reste à faire : [NEXT.md](NEXT.md)

## Structure

- `core/` — Kotlin pur, sans Android, testé : flux OGN (APRS), base OGN DDB, terrain (tout
  terrain : choisi à l'accueil, import OpenStreetMap, édition ; LFNL intégré),
  moteur de statuts (décollage, remorquage, retour, circuit, finale, posé), golfette sur piste,
  alertes, trafic de démo et rejeu d'enregistrements (pour les tests, rien dans l'app).
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
`-Pairfield=mon-terrain.json` (fichier partagé depuis l'app). OGN ne garde pas
l'historique : il faut enregistrer le jour même. Les enregistrements se rejouent dans les
tests du moteur (`Recording`, `ReplayClock`) ; l'app n'a plus de démo ni de rejeu depuis la
1.0.

## Base OGN livrée avec l'app

`app/src/main/assets/ogn-ddb.dat` : copie compacte de la base des appareils OGN, pour avoir
les noms dès l'installation. La CI la met à jour avant chaque construction ; à la main :
`./gradlew :core:updateDdb`. Extension `.dat` et pas `.gz` : la construction Android
décompresse et renomme les `.gz` des assets.

## Terrains du monde entier

Chaque lundi, le workflow **Airfields** (`.github/workflows/airfields.yml`) extrait tous les
terrains du monde d'OpenStreetMap : les extraits Geofabrik de chaque continent (90 Go en tout,
un job par continent), réduits aux pistes, taxiways, aires, hangars et manches à air
(`tools/airfields/airfields.py extract`), puis un fichier par terrain avec une piste
(`build`) : `a/<CODE>.json.gz`, au format de la réponse Overpass que l'app sait déjà lire, et
`index.json` (tous les terrains : nom, position, plus longue piste, codes). Rien n'est publié
si un continent manque : les fichiers de la semaine d'avant restent en ligne.

Publié sur **GitHub Pages** : `https://corentingrard.github.io/PicPiste/` (environ 120 Mo,
limite 1 Go). L'app (`AirfieldDownloader`) prend le fichier publié ; un terrain absent (sans
code, ou ajouté dans OSM depuis) ou Pages injoignable : OpenStreetMap en direct, comme avant.

À la main, avec Docker :

```bash
docker build -t picpiste-airfields-builder tools/airfields
docker run --rm -v "$PWD:/work" picpiste-airfields-builder extract france-latest.osm.pbf france.osm.pbf
docker run --rm -v "$PWD:/work" picpiste-airfields-builder build site france.osm.pbf
```

Mise en place (une fois) : réglages du dépôt → Pages → Source : **GitHub Actions**, puis
Actions → Airfields → Run workflow.

Données © les contributeurs OpenStreetMap, licence ODbL : les fichiers publiés sont une base
dérivée sous la même licence.

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
