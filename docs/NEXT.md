# PicPiste — prochaine session

État au 19 oct. 2026 et travail restant. Règles de fond (voir `SPECS.md`, en tête) :

- **Tout terrain** : autant de pistes que nécessaire, orientations et numéros quelconques
  (parallèles, croisées, en V ; « 04/22 », « 14L/32R »). **Aucune référence à 12/30, ni aux
  noms de piste de LFNL** (« herbe », « dur droite », « dur gauche »), ni à LFNL, dans le
  code, l'interface ou la doc, sauf le fichier terrain intégré
  (`core/src/main/resources/airfields/LFNL.json`), `tools/osm_to_airfield.py` qui le produit,
  et les tests qui le chargent.
- **Mode clair** (SPECS §4.5) : fait, reste l'essai en plein soleil et sur Android Auto.

## Déjà fait (v2 terrain)

- Modèle de terrain générique : `RunwayEnd` A/B (A = plus petit numéro), numéros libres par
  extrémité (`RunwayConfig.ends`), format JSON v2 (`formatVersion`).
- Statuts et alertes : chaque aéronef est comparé à toutes les pistes et aux deux sens
  (`StatusEngine.finalApproach`) ; texte « Finale <sens> » sans L/C/R (`Runway.direction`).
- Zone piste : bandes d'herbe seulement entre pistes **parallèles à l'axe principal** et à
  moins de 150 m (`Airfield.runwayZone`).
- Radar : numéros par groupe de pistes parallèles (`RadarRenderer.runwayLabels`).
- Import OSM par code OACI (`OsmImport`, `AirfieldDownloader` : Overpass, sinon Nominatim
  + API OSM ; altitude Open-Meteo si absente), éditeur avec relevé GPS, partage / import
  de fichier JSON (`ui/editor/`). Testé sur LFNL, LFNG, LFLE (pistes parallèles avec
  lettres L/R), LFPT (pistes croisées) : `core/src/test/resources/osm/`.

## Fait le 19 oct. 2026 (soir) : tout terrain + mode clair

- **Démo** (`DemoTraffic`) construite depuis la géométrie : planeurs sur la piste principale
  (la plus longue dans l'axe du radar), vers son extrémité B ; circuit du côté opposé aux
  autres pistes parallèles et au starter ; remorqué depuis une autre piste (parallèle la plus
  éloignée du circuit, sinon piste croisée, sinon la principale), le remorqueur s'y pose.
  Golfette simulée : vers la piste principale. CN de démo en lettres (« KL »).
- **Enregistrement PC** : `./gradlew :core:record -Pairfield=LFLE` (téléchargé depuis OSM) ou
  `-Pairfield=terrain.json` ; défaut `Airfield.BUNDLED`.
- Commentaires et exemples neutres (04/22, 14L/32R) ; récepteur des balises de démo = code
  du terrain.
- Tests `AnyAirfieldScenarioTest` : circuit/finale/posé, remorqué, alerte golfette, plafond,
  sur LFNL, LFNG, LFLE (14L/32R) et LFPT (pistes croisées).
- **Mode clair** : `Colors.DARK` / `Colors.LIGHT` (`render/Palette.kt`), réglage Thème
  Auto / Clair / Sombre (`Prefs.theme`), radar et Android Auto reçoivent leurs couleurs ;
  la voiture suit `carContext.isDarkMode`. Vérifié sur le téléphone (clair, sombre, alerte)
  et avec le DHU (`day` / `night` dans sa console : bascule immédiate, alerte lisible en jour).

- **Langues** : anglais par défaut, français sur un téléphone en français (écrans, Android
  Auto, alertes écrites et vocales, messages d'import et de l'éditeur). Ajouter une langue :
  un `values-xx/strings.xml` et un objet dans `core/i18n/Texts.kt`.

- **Accueil et alertes** : écran d'accueil au premier lancement (code OACI ; LFNL intégré, sans
  OSM ; ou fichier) ; plus de terrain par défaut. Alerte graduée (orange sans son si le posé est
  à plus de 90 s, rouge + voix sinon, voix toutes les 30 s). Planeur perdu en finale ou dans le
  circuit : alerte encore 3 min. Plus jamais « Circuit libre » sans données OGN. Pastille
  « OGN · rien depuis … ». Thème Auto selon le soleil au terrain. Cibles tactiles ≥ 48 dp.

## Reste à faire

- **Mode clair en plein soleil** : à essayer dehors, ajuster les couleurs si besoin
  (surtout le jaune « Circuit », foncé en ocre pour rester lisible sur blanc).

## Autres points ouverts

- **Essai terrain** (SPECS §8) : enregistrer une vraie journée de vol, la rejouer, régler
  le moteur ; week-end avec un observateur au bord de piste.
- **Overpass souvent saturé** : le repli Nominatim + API OSM marche, mais l'API OSM est
  surtout faite pour l'édition ; usage ponctuel seulement (un téléchargement par terrain).
- Éditeur sans fond de carte (pas d'image satellite) : le relevé GPS sur place sert à
  corriger les positions.
- Installation : les APK sont signés avec la clé PicPiste (`~/.picpiste/`) ; un téléphone
  qui a encore un ancien build signé autrement doit désinstaller une fois.
