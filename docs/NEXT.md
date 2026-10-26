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
  (`StatusEngine.finalApproach`) ; texte « Finale 23D » avec la piste la plus probable (moyenne sur 20 s), lettres G/D en
  français (`Texts.runwayRef`).
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
  (`Prefs.mapBackground`). Photo : IGN (orthophotos, en France et un peu au-delà), le plan
  ailleurs (Esri retiré le 20 oct. : ses conditions excluent une app diffusée) ; Plan :
  OpenStreetMap. Tuiles gardées sur le téléphone 30 jours
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

## Version 1.0 (22 oct. 2026)

- Outils de développement retirés de l'app : choix du trafic (démo, rejeu) et enregistrement
  de la journée. `core` garde la démo et le rejeu pour les tests, et l'outil PC
  `./gradlew :core:record`.
- Base OGN livrée avec l'app, mise à jour chaque jour en Wi-Fi (WorkManager) ; indicatif ADS-B
  pour les appareils absents de la base ; état de la base dans les réglages.
- Réglage altitude (QNH) / hauteur (QFE). Le flux continue 2 min hors de l'app, et revenir sur
  le même terrain garde le radar et la liste « Posés ».
- Licence GPL v3, crédits des données dans Réglages → À propos.

## Version 1.1 (26 oct. 2026) — après le premier essai terrain

- **Android Auto** : plus de bouton « Posés » ; la liste est dans le panneau de gauche, toujours
  affichée, sous les arrivées (« +N autres » si elle déborde). Plus de « Circuit libre » : une
  liste vide suffit (la carte « Pas de données OGN » reste). Zoom −/+ dans la barre du haut,
  animé ; la carte passe sous la bande réservée aux boutons (ils se cachent seuls). Écrans
  portrait : carte en haut, listes dessous. Vérifié au DHU en 800×480, 6", 720p, 1080p
  (160 et 240 dpi), large, portrait 1080×1920.
- **Téléphone en paysage** : menu et listes à gauche, carte pleine hauteur à droite, comme
  dans la voiture.
- **Noms des appareils personnels** : messages d'état `Reg=` / `Name=` / `Pilot=` (traqueurs
  OGN, FANET, Meshtastic) pour les appareils absents de la base OGN ; jamais si no-ident.
  Positions Naviter (SeeYou Navigator, Oudie, identifiant sur 10 chiffres) lues.
- **Retard OGN** (mesuré : 2 s en médiane, 5 s au pire ; une position toutes les 3 s, jusqu'à
  13 s) : l'âge des positions et les ETA partent de l'heure du balisage, pas de sa réception.
  `Prediction` avance chaque aircraft en vol (vitesse, taux de virage `rot` = 3°/s par unité,
  10 s au plus), à chaque image (téléphone à la fréquence de l'écran, voiture 30 i/s).
  Affichage seulement : statuts et alertes restent sur les positions reçues.
- Traînées en lignes qui s'estompent (`TRAIL_ALPHA`, `TRAIL_PIECES`).
- 1.1.1 : cercles du radar à des distances rondes, trois au plus (`RING_STEPS_M`).

### Points ouverts 1.1

- **Plantage voiture** si l'app est mise à jour pendant une session Android Auto : le nouveau
  processus reçoit l'ancienne surface (`getFrame() called on a context with no surface`,
  dans `lockHardwareCanvas`). Arrive aussi avec une mise à jour Play pendant que le téléphone
  est branché.
- Mesurer le retard réel des stations autour du terrain avec `./gradlew :core:record`.
- Plus tard : récepteur FLARM dans la golfette (moins de 1 s de retard).

## Reste à faire

- **Mode clair en plein soleil** : à essayer dehors, ajuster les couleurs si besoin
  (surtout le jaune « Circuit », foncé en ocre pour rester lisible sur blanc).

## Autres points ouverts

- **Essai terrain** (SPECS §8) : enregistrer une vraie journée de vol, la rejouer, régler
  le moteur ; week-end avec un observateur au bord de piste.
- **Overpass souvent saturé** : le repli Nominatim + API OSM marche, mais l'API OSM est
  surtout faite pour l'édition ; usage ponctuel seulement (un téléchargement par terrain).
- Photos hors de France : aucune source gratuite utilisable dans une app diffusée. Si besoin,
  compte ArcGIS ou MapTiler (clé) derrière un petit relais sur notre serveur.
- Installation : les APK sont signés avec la clé PicPiste (`~/.picpiste/`) ; un téléphone
  qui a encore un ancien build signé autrement doit désinstaller une fois.
