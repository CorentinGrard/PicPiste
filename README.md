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

## Mode démo

Réglages (⋯) → **Mode démo** : trafic et golfette simulés autour de LFNL, pour essayer
l'app sans vol (circuit, finale 30 herbe, remorqué depuis la 30 dur gauche, alertes).
