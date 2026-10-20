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

- **Fond de carte dans l'éditeur** (20 oct.) : bouton sur la carte Photo → Plan → Sans fond
  (`Prefs.mapBackground`). Photo : IGN (orthophotos, en France et un peu au-delà), Esri
  ailleurs, tuile par tuile ; Plan : OpenStreetMap. Tuiles gardées sur le téléphone 30 jours
  (cache de 200 Mo, `ui/editor/MapTiles.kt`) : une carte vue à la maison s'affiche sans
  réseau au terrain. Calcul des tuiles : `core/geo/WebMercator.kt`.

- **Plusieurs terrains** (20 oct.) : Réglages → liste des terrains chargés sur le téléphone
  (toucher pour changer, « Modifier » sur celui en cours, « + Ajouter un terrain » par code OACI
  ou fichier ; un code déjà chargé est juste sélectionné). Un fichier par terrain
  (`files/airfields/<OACI>.json`), l'ancien `airfield.json` est repris tout seul. Dans
  l'éditeur : plus de recherche par code ni de « terrain intégré » ; code et nom ne se
  modifient plus, seule l'altitude ; « Supprimer ce terrain » (jamais le dernier).
  LFNL reste dans l'app, seulement pour éviter OpenStreetMap quand on tape ce code.

- **Terrain sans code OACI** (20 oct.) : « Créer un terrain à la main » (accueil et « Ajouter
  un terrain ») : nom, code facultatif (sinon tiré du nom : « Les Mées » → LESMEES), croix sur la
  position du téléphone (GPS, ou wifi / réseau à l'intérieur), qui suit le GPS tant qu'on n'a
  pas déplacé la carte ; bouton « Ma position ». Crée une piste de 600 m est-ouest à cet endroit, altitude
  Open-Meteo, puis ouvre l'éditeur pour placer la piste (`newAirfield`, `codeFromName`).

- **Éditeur sur la carte** (20 oct.) : les pistes sont toujours des rectangles entre leurs deux
  seuils (largeur = aire / longueur pour les anciens contours OSM, appliqué au chargement).
  Piste sélectionnée : seuils (allonger / tourner), centre (déplacer), côté (largeur), poignée
  ronde (tourner) ; les numéros suivent le cap s'ils lui correspondaient. Autres formes : un
  « + » au milieu de chaque côté ajoute un point, le centre déplace tout. Plus de ◀ ▶,
  « Ajouter un point », « Déplacer tout ».
- **Orientation des cartes** par terrain (`mapUpDeg` : cap en haut de la carte, enregistré dans le fichier ; par défaut
  la piste principale en travers de l'écran, haut au plus près du nord, et la carte suit la piste
  tant qu'on ne l'a pas tournée à la main) : dans l'éditeur, tourner à deux doigts fait
  pivoter la carte (seuil de 10° pour ne pas tourner en zoomant ; aimantée à moins de 5° au nord et à chaque
  piste verticale ou horizontale, `mapUpSnaps`) ;
  la boussole en haut à gauche montre le nord, la toucher remet le nord en haut. Le radar du
  téléphone et d'Android Auto suit.
- Réglages : la fenêtre défile (le curseur du plafond était écrasé sous son titre).

- **Zones d'alerte** (20 oct.) : la golfette n'est alertée en rouge que pour une finale ou un
  décollage sur une piste liée à la zone où elle est (pistes parallèles à moins de 300 m = une
  seule, piste inconnue = alerte). Bandeau orange du circuit réglable par zone. Sans zone
  dessinée : une zone par piste (rectangle + 150 m avant chaque seuil) et les bandes entre
  pistes parallèles. Éditeur : Ajouter → Zone d'alerte (autour de la piste la plus proche,
  liée à elle), Propriétés = pistes + circuit ; zones auto affichées en pointillé.
  `Airfield.alertZones`, `Airfield.protects`, `AlertEngine.evaluate(zones, …)`, `ZoneAlertTest`.
- **Plafond d'affichage** : ne cache plus les menaces (il masquait aussi les alertes).
- Ajouter : plus de starter ; « Hangar » devient « Bâtiment ».

## Version GA (grand public) : à supprimer avant

Ces fonctions servent au développement et au réglage, pas aux conducteurs :

- **Choix du trafic** dans les réglages (En direct / Démo / Rejeu) : ne garder que le direct.
  Supprimer `Source`, `Prefs.source` et `replaySpeed`, le mode démo (`DemoTraffic` côté app)
  et le rejeu (`ReplayClock` côté app, bandeau de rejeu, vitesse ×1/×4/×10).
- **Enregistrer la journée** (`Prefs.record`, écriture des trames OGN dans l'app).
- Garder dans `core` ce qui sert aux tests et à l'outil PC (`./gradlew :core:record`,
  rejeu dans les tests), sans rien de visible dans l'app.

## Reste à faire

- **Mode clair en plein soleil** : à essayer dehors, ajuster les couleurs si besoin
  (surtout le jaune « Circuit », foncé en ocre pour rester lisible sur blanc).

## Autres points ouverts

- **Essai terrain** (SPECS §8) : enregistrer une vraie journée de vol, la rejouer, régler
  le moteur ; week-end avec un observateur au bord de piste.
- **Overpass souvent saturé** : le repli Nominatim + API OSM marche, mais l'API OSM est
  surtout faite pour l'édition ; usage ponctuel seulement (un téléchargement par terrain).
- Fond de carte : les conditions d'Esri (World Imagery) visent un usage non commercial
  avec attribution ; à revoir si l'app est diffusée largement.
- Installation : les APK sont signés avec la clé PicPiste (`~/.picpiste/`) ; un téléphone
  qui a encore un ancien build signé autrement doit désinstaller une fois.
