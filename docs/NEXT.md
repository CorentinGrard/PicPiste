# PicPiste — prochaine session

État au 19 oct. 2026 et travail restant. Règles de fond (voir `SPECS.md`, en tête) :

- **Tout terrain** : autant de pistes que nécessaire, orientations et numéros quelconques
  (parallèles, croisées, en V ; « 04/22 », « 14L/32R »). **Aucune référence à 12/30, ni aux
  noms de piste de LFNL** (« herbe », « dur droite », « dur gauche »), ni à LFNL, dans le
  code, l'interface ou la doc, sauf le fichier terrain intégré
  (`core/src/main/resources/airfields/LFNL.json`), `tools/osm_to_airfield.py` qui le produit,
  et les tests qui le chargent.
- **Mode clair** à ajouter (SPECS §4.5).

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

## À faire : retirer ce qui reste propre à LFNL

Chaque point : fichier, ce qui suppose LFNL, ce qu'il faut à la place.

1. **Démo** — `core/.../demo/DemoTraffic.kt`
   - Cherche les pistes par leurs ids LFNL (`runway("herbe")`, `"dur-droite"`,
     `"dur-gauche"`), sinon prend la piste principale pour tout.
   - Circuit dessiné en coordonnées fixes pour des pistes de ~500 m autour de l'origine,
     toujours vers l'extrémité B (`circuitTo30`), côté +y seulement.
   - À la place : scénario construit depuis la géométrie (piste la plus longue = atterrissages,
     une autre piste si elle existe = remorqués ; sens et côté du circuit calculés ; taille
     du circuit proportionnelle à la piste). Doit marcher sur une piste seule, des pistes
     parallèles, des pistes croisées. Renommer `circuitTo30`.
   - Le CN « 12 » d'un planeur de démo (`DdbDevice(..., "12")`) : prendre un CN en lettres.
2. **Golfette simulée** (démo et rejeu) — `DemoTraffic.cartAt` : roule vers « herbe » à
   60 % depuis l'extrémité B. À la place : vers la piste principale. Texte du réglage
   Rejeu dans `ui/PhoneApp.kt` (« …sur la piste en herbe ») à rendre générique.
3. **Enregistrement sur PC** — `core/.../replay/RecordMain.kt` et `core/build.gradle.kts`
   (tâche `record`) : `Airfield.bundled("LFNL")` en dur. À la place : code terrain en
   argument (`./gradlew :core:record -Pairfield=LFLE`) ou fichier terrain JSON ; LFNL par
   défaut.
4. **Terrain par défaut** — `data/AirfieldStore.kt` (`BUNDLED = "LFNL"`) : garder LFNL comme
   défaut, mais seulement via cette constante (c'est déjà le cas) ; le menu « Revenir au
   terrain intégré (LFNL) » affiche le code depuis la constante — OK.
5. **Commentaires et exemples** citant 12/30, « herbe », « dur gauche », LFNL comme si
   c'était la règle — à reformuler avec des exemples neutres :
   `Geo.kt` (doc de `LocalFrame`), `Airfield.kt` (`RunwayConfig.name`, `RunwayEnd`,
   `runwayZone`, `ref`, `label`), `AirfieldEdit.kt` (`label`, `slug`), `Cart.kt`
   (`CartAlert.title`), `Model.kt` (`direction`, `statusText`), `OsmImport.kt`
   (`assignEnds`, `parseRefs` : garder « 12-30 » seulement comme exemple de format OSM),
   `RadarRenderer.kt` (`runwayLabels`), `AprsParser.kt` / `AprsFormatter.kt` (récepteur
   « LFNL » par défaut dans le formateur de démo : prendre le code du terrain),
   `AndroidManifest.xml` (commentaire `PicPiste-LFNL.json`), éditeur
   (`AirfieldEditor.kt` : exemples « LFNL », « herbe, dur gauche »).
6. **Tests** : les tests moteur (`EngineScenarioTest`) ne tournent que sur LFNL. Ajouter
   les mêmes scénarios (finale, décollage, golfette sur piste, posé) sur LFPT (pistes
   croisées) et LFLE (14L/32R) à partir des fixtures OSM, pour prouver « tout terrain ».

## À faire : mode clair (SPECS §4.5)

- Aujourd'hui toutes les couleurs sont des constantes sombres dans `render/Palette.kt`,
  utilisées par `RadarRenderer`, `CarOverlay`, `PhoneApp.kt`, `ui/editor/*` ; le thème
  Compose est `darkColorScheme` en dur (`PhoneApp.kt`) et `themes.xml` a un fond sombre.
- Plan : deux palettes (sombre = l'actuelle, claire = nouvelle, contrastes plein soleil)
  derrière une même interface ; le renderer reçoit la palette à chaque dessin.
- Réglage `Prefs.theme` : Auto / Clair / Sombre (Auto = `isSystemInDarkTheme()`), dans la
  boîte Réglages ; `lightColorScheme` / `darkColorScheme` selon le choix.
- Android Auto : `carContext.isDarkMode` choisit la palette du radar voiture (et se met à
  jour quand la voiture passe jour / nuit).
- Vérifier sur le téléphone en plein jour, et avec le DHU (`tools/dhu.sh`) en jour et nuit.

## Autres points ouverts

- **Essai terrain** (SPECS §8) : enregistrer une vraie journée de vol, la rejouer, régler
  le moteur ; week-end avec un observateur au bord de piste.
- **Overpass souvent saturé** : le repli Nominatim + API OSM marche, mais l'API OSM est
  surtout faite pour l'édition ; usage ponctuel seulement (un téléchargement par terrain).
- Éditeur sans fond de carte (pas d'image satellite) : le relevé GPS sur place sert à
  corriger les positions.
- Installation : les APK sont signés avec la clé PicPiste (`~/.picpiste/`) ; un téléphone
  qui a encore un ancien build signé autrement doit désinstaller une fois.
