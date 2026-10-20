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
  (bannière 1024 × 500).

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

- FR : `Le trafic OGN du terrain de vol à voile, pour le conducteur de la golfette.`
- EN : `Live OGN traffic of your gliding airfield, for the golf-cart driver.`

**Description complète**

FR :

> PicPiste aide le conducteur de la golfette à traverser et longer les pistes en sécurité.
>
> • Radar du terrain : planeurs, remorqueurs et avions autour du terrain, en direct (Open
>   Glider Network).
> • Statut de chaque appareil : décollage, remorqué, circuit, finale (avec la piste la plus
>   probable), posé.
> • Alerte quand la golfette est sur une piste : orange si quelqu'un est dans le circuit,
>   rouge et vocale pour une finale ou un décollage sur cette piste.
> • Android Auto : le radar et les alertes sur l'écran de la voiture.
> • Tout terrain : import depuis OpenStreetMap par code OACI, éditeur de pistes et de zones,
>   partage du terrain par fichier.
>
> Aide à la vigilance : ne remplace pas l'observation visuelle. Les données dépendent de la
> couverture OGN et des balises des appareils.

EN :

> PicPiste helps the golf-cart driver cross and drive along runways safely.
>
> • Airfield radar: gliders, tow planes and aircraft around the field, live (Open Glider
>   Network).
> • Status of each aircraft: takeoff, tow, circuit, final (with the most likely runway),
>   landed.
> • Alert when the cart is on a runway: amber when someone is in the circuit, red with voice
>   for a final or a takeoff on that runway.
> • Android Auto: radar and alerts on the car screen.
> • Any airfield: import from OpenStreetMap by ICAO code, runway and zone editor, share the
>   airfield as a file.
>
> An aid to vigilance: it does not replace looking outside. Data depends on OGN coverage and
> on the aircraft's beacons.

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
