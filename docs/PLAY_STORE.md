# Publier PicPiste sur le Play Store (test fermé)

Ce qui est prêt dans le dépôt, et ce qu'il reste à faire dans la Play Console.

## Déjà prêt

- **Bundle Play Store** : à chaque tag `v*`, la CI joint `PicPiste-<version>-play.aab` à la
  release GitHub (à côté de l'APK). C'est la même version, compilée avec `-Pstore=true` :
  sans le choix du trafic (démo, rejeu) ni l'enregistrement de journée
  (`BuildConfig.DEV_TOOLS`). L'APK GitHub les garde.
  En local : `./gradlew :app:bundleRelease -Pstore=true`.
- **Politique de confidentialité** : `docs/PRIVACY.md`, URL à donner à la Play Console :
  https://github.com/CorentinGrard/PicPiste/blob/main/docs/PRIVACY.md
- **Visuels** : `docs/play/icon-512.png` (icône 512 × 512) et `docs/play/feature-graphic.png`
  (bannière 1024 × 500, en français ; `feature-graphic-en.png` pour la fiche anglaise).

## Étapes dans la Play Console

1. **Compte développeur** (25 $, vérification d'identité). Compte personnel : il faut un
   **test fermé d'au moins 12 testeurs pendant 14 jours** avant de pouvoir publier en public.
2. **Créer l'app** : nom « PicPiste », langue par défaut français, application gratuite.
3. **Signature (important)** : à la première version, choisir *Utiliser une clé existante*
   et envoyer la clé PicPiste (`~/.picpiste/picpiste-release.jks`) avec l'outil PEPK que la
   console propose. Google signe alors avec la **même clé** que les APK GitHub : les
   téléphones déjà installés pourront passer à la version Play sans désinstaller. La clé
   reste aussi la clé d'envoi (upload) des AAB de la CI.
4. **Test fermé** → créer une piste, une liste de testeurs (adresses Gmail des membres du
   club), envoyer l'AAB de la release, puis partager le lien d'inscription aux testeurs.
5. **Fiche du Play Store** : textes ci-dessous, icône, bannière, au moins 2 captures
   d'écran de téléphone (radar avec trafic, liste des arrivées, éditeur de terrain).
6. **Contenu de l'app** : formulaires ci-dessous.

## Fiche du Play Store

**Description courte** (80 caractères max)

- FR : `Traversez les pistes en sécurité : planeurs en direct et alerte de finale.`
- EN : `Cross runways safely: live gliders and tow planes, alerts on final.`

**Description complète** (texte brut, à coller tel quel)

FR :

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

ANDROID AUTO
• Le radar et les alertes sur l'écran de la voiture.

TOUS LES TERRAINS
• Import du terrain depuis OpenStreetMap avec son code OACI.
• Éditeur de pistes et de zones sur fond de carte.
• Partage du terrain avec les autres membres du club par fichier.

GRATUIT, SANS COMPTE, SANS PUBLICITÉ
• Aucune donnée n'est envoyée au développeur. Votre position reste sur le téléphone.

IMPORTANT
PicPiste est une aide à la vigilance : il ne remplace pas l'observation visuelle. Seuls les appareils équipés d'une balise (FLARM, OGN…) et à portée d'une station OGN apparaissent.
```

EN :

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

ANDROID AUTO
• The radar and alerts on the car screen.

ANY AIRFIELD
• Import the airfield from OpenStreetMap with its ICAO code.
• Runway and zone editor on a map.
• Share the airfield with other club members as a file.

FREE, NO ACCOUNT, NO ADS
• No data is sent to the developer. Your location stays on the phone.

IMPORTANT
PicPiste is an aid to vigilance: it does not replace looking outside. Only aircraft with a beacon (FLARM, OGN…) and within range of an OGN station are shown.
```

**Catégorie** : Cartes et navigation (ou Sports). **Contact** : adresse e-mail du compte.

## Contenu de l'app (formulaires)

- **Politique de confidentialité** : URL ci-dessus.
- **Accès à l'app** : tout est accessible sans compte. Pour les vérificateurs : « Au premier
  lancement, saisir LFNL ; le trafic en direct dépend de l'activité du terrain. »
- **Publicités** : non.
- **Sécurité des données** :
  - Données collectées : **aucune** (rien n'est envoyé à un serveur du développeur).
  - Données partagées : aucune. La position reste sur le téléphone ; les appels aux services
    tiers (OGN, OpenStreetMap, Open-Meteo, tuiles) n'envoient que la position du terrain.
  - Chiffrement en transit : oui (HTTPS ; le flux OGN APRS est en clair mais ne contient pas
    de donnée de l'utilisateur).
  - Suppression des données : désinstaller l'app.
- **Service de premier plan, type « location »** (déclaration demandée par Google) :
  « Pendant que le conducteur utilise l'app ou Android Auto, le service garde le GPS actif
  écran éteint pour savoir si la golfette est sur une piste et déclencher l'alerte (finale,
  décollage). Il s'arrête quand l'app est quittée. » Joindre une courte vidéo : ouvrir l'app,
  éteindre l'écran, la notification PicPiste reste visible.
- **Classification du contenu** : questionnaire IARC, rien de sensible → « Tout public ».
- **Public cible** : 18 ans et plus (outil pour conducteurs).
- **App d'actualités / santé / finance** : non.

## Android Auto

L'app se déclare en catégorie **navigation** pour dessiner le radar. Google vérifie les apps
Android Auto avant publication et pourrait refuser une app de navigation qui ne guide pas
vers une destination. Si c'est refusé : publier l'app téléphone seule (retirer le service
voiture de la version Play) et garder la version Android Auto sur GitHub, avec le mode
développeur d'Android Auto.
