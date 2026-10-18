# PicPiste — Spécifications (v0.4, brouillon pour review)

> App Android + Android Auto pour la golfette du club : voir où sont les planeurs,
> qui va se poser, et dégager la piste à temps.

Statut : **brouillon validé sur le fond** — plus de question bloquante (voir §11).

---

## 1. Contexte et objectifs

La golfette sert à aller chercher les planeurs après l'atterrissage et à les ramener
au point de départ. Son conducteur a besoin de savoir, sans sortir son téléphone :

1. **Qui va se poser, et quand** → anticiper le prochain dépannage.
2. **Si un planeur est en finale** → ne pas être sur (ou près de) la piste à ce moment-là.
3. **Où est tout le trafic** autour du terrain, en direct → vue d'ensemble de l'activité.
4. **Qui s'est posé où** → aller chercher le bon planeur au bon endroit (y compris
   une vache proche).

### Hors objectifs (v1)
- Pas d'outil de contrôle aérien ni d'aide à la séparation : **information seulement**,
  la vigilance visuelle reste la règle.
- Pas de gestion de la planche de vol / facturation (intégration éventuelle plus tard).
- Pas d'iPhone / CarPlay.

## 2. Utilisateurs

| Rôle | Usage |
|---|---|
| Conducteur de golfette | Écran Android Auto, coup d'œil rapide, alertes sonores |
| Chef de piste / pilotes au sol | App téléphone, vue carte détaillée |
| Admin club | Configure terrain et pistes |

## 3. Source des données : FLARM via OGN

Les planeurs émettent leur position via **FLARM**. Le réseau **OGN (Open Glider Network)**
capte ces trames et les republie gratuitement.

- **Flux temps réel** : serveur APRS OGN `aprs.glidernet.org:14580`, connexion TCP en
  lecture seule (passcode `-1`) avec filtre géographique `r/<lat>/<lon>/<rayon km>`.
  Mise à jour ≈ 1 à 5 s par aéronef.
  **C'est du push, pas du polling** : une seule connexion TCP ouverte en permanence,
  le serveur envoie chaque position dès qu'elle arrive. L'app envoie juste une ligne
  « keepalive » toutes les quelques minutes et se reconnecte automatiquement si la
  connexion tombe (perte 4G, changement d'antenne) ou si plus rien n'arrive.
- **Identification — pas de liste de flotte** : tout vient d'OGN.
  - La trame APRS porte déjà l'ID de l'appareil et son **type** (planeur, remorqueur,
    ULM, hélico…) et les drapeaux `no-track` / `no-ident`.
  - La base OGN DDB (`ddb.glidernet.org`, téléchargée 1×/jour et mise en cache) donne
    l'immatriculation, le numéro de concours (CN) et le modèle.
  - Appareil absent de la DDB : affiché quand même, sans nom (ex. « Planeur ?? »).
- **Respect de la vie privée** : obligation de respecter les drapeaux OGN `no-track`
  (ne pas afficher) et `no-ident` (afficher anonymisé). Pas d'historique conservé
  au-delà de la journée.

### Couverture
Le club a **son propre récepteur OGN** à LFNL : les planeurs sont vus jusqu'au sol,
circuit et finale compris. Tous les planeurs du club ont un FLARM ; les données OGN
suffisent à savoir ce qui se passe (pas besoin de la planche de vol pour les statuts).

### Exigence clé : fraîcheur des données
Les données doivent arriver **vite** : une position vieille de 30 s ne sert à rien pour
dégager la piste. Seul le **flux APRS temps réel** convient. Les API HTTP d'OGN
(FlightBook, interrogé toutes les 60 s par PicBoard au départ) sont trop lentes.

> Retour d'expérience PicBoard : le poller FlightBook (60 s) était trop lent. Il a été
> remplacé par une chaîne « live » hébergée sur le serveur PicBoard :
> `APRS-IS → ogn-flightbook (ogn-aprs + ogn-cons) → Redis stream SEVT → ogn-bridge →
> API PicBoard → WebSocket`. **Mais** cette chaîne ne produit que des **évènements**
> (décollage / atterrissage), pas les **positions** : elle ne suffit pas telle quelle
> pour PicPiste (voir §6, choix de la source).

## 4. Fonctionnalités

### 4.1 Statut de chaque aéronef (cœur de l'app)

Chaque aéronef suivi reçoit un statut calculé à partir de position, altitude sol (AGL),
vitesse, vario et cap :

| Statut | Règle indicative (paramétrable) |
|---|---|
| 🟠 **Décollage** | Au sol sur une piste, accélère (> 30 km/h) → **piste occupée** |
| 🛫 **Remorquage** | Monte, < 1 500 m AGL, avec le remorqueur à proximité (lancement au remorqueur uniquement à LFNL) |
| 🛩️ **Remorqueur en descente** | Remorqueur largué, descend vers le terrain (affiché comme une arrivée) |
| ✈️ **En vol local** | En l'air, < 10 km du terrain |
| 🌍 **En vol (loin)** | En l'air, > 10 km |
| ↩️ **Retour** | < 5 km, se rapproche, < 600 m AGL |
| 🔄 **Dans le circuit** | < 3 km, < 350 m AGL (vent arrière / étape de base) |
| 🔴 **En finale** | Aligné sur l'axe d'une piste (±30°), < 2,5 km du seuil, < 200 m AGL, descend |
| 🟢 **Posé** | Vitesse sol < 10 km/h, altitude ≈ terrain, sur l'emprise du terrain |
| 🐄 **Vache** | Posé hors du terrain → position + lien navigation pour le dépannage |
| ❔ **Signal perdu** | Pas de trame depuis > 60 s (dernière position affichée, grisée) |

Pour « En finale » et « Dans le circuit » : **ETA estimée** (distance / vitesse sol) et
**piste probable** (selon l'axe d'approche ; pistes parallèles herbe / dur à distinguer).

### 4.1a Trafic affiché
**Tout le trafic OGN** autour du terrain, pas seulement le club : planeurs, remorqueurs,
visiteurs, ULM… chacun avec une icône par type et sa **position en direct**.
- Filtre par défaut : aéronefs **sous 300 m AGL** dans le rayon du radar, plus tout ce
  qui est au sol sur le terrain. Plafond réglable. Le terrain étant à ~200 m d'altitude,
  cela correspond à **~500 m QNH** — valeur validée (couvre les retours et le circuit).
- AGL = altitude GPS de la trame − altitude du terrain (terrain quasi plat à l'échelle
  du circuit, pas besoin de modèle de relief en v1).

### 4.1b Golfette sur piste : indication et alerte
La golfette circule **partout** sur le terrain. Les deux pistes (herbe et dur) sont des
**zones** (rectangles : seuils + largeur + petite marge). Le GPS du téléphone situe la
golfette par rapport à ces zones. Deux niveaux, pas plus :

| Situation | Réaction |
|---|---|
| Golfette **sur une piste**, rien dans le circuit | **Indication visuelle seulement** : bordure rouge autour de l'écran + nom de la piste (« Sur piste herbe »). Pas de son, pas de bandeau. |
| Golfette **sur une piste** **et** un aéronef dans le circuit, en finale ou en décollage | **Alerte** : son + bandeau rouge « Planeur en finale — 40 s » (ou « Circuit — ~2 min », « Décollage en cours »). Répétée tant que les deux conditions restent vraies ; s'arrête dès que la golfette sort de la piste. |
| Golfette **hors piste** | Rien de particulier : l'info reste sur le radar et la liste « Arrivées ». |

- Pas de pré-alerte avant l'entrée sur piste : le conducteur voit le radar en
  permanence, et la bordure rouge apparaît dès l'entrée.
- Une seule alerte à la fois : si plusieurs aéronefs, on annonce le plus imminent.
- Hystérésis (quelques mètres / secondes) sur l'entrée/sortie de zone pour éviter que la
  bordure clignote quand la golfette longe le bord de piste (précision GPS ~5 m).
→ Peu d'alertes, donc des alertes qu'on écoute.

### 4.2 Écran Android Auto (golfette)

Priorité absolue : **lisible en 1 seconde**, gros éléments, aucune saisie.

- **Liste « Arrivées »** triée par imminence :
  `🔴 F-CXYZ (AB) — Finale 28 — 40 s`
  `🔄 F-CKLM (12) — Circuit — ~2 min`
  `↩️ F-CABC — Retour — 4 km, 450 m`
- **Bordure rouge** quand la golfette est sur une piste ; **bandeau + son** seulement si
  en plus un aéronef est dans le circuit, en finale ou en décollage (§4.1b).
- **Liste « Posés »** : derniers planeurs posés, avec position (début/milieu/fin de piste,
  ou vache) → tap = guidage vers lui (ouverture Google Maps/Waze pour les vaches).
- **Vue radar** (écran principal, v1) : vue de dessus **fixe**, **orientée selon l'axe des
  pistes** (pas de rotation, pas de zoom pendant la conduite). Pistes herbe et dur
  dessinées, golfette au centre ou position réelle, aéronefs en icônes colorées par
  statut avec étiquette (CN). Rayon ≈ 5 km, 2 ou 3 niveaux de zoom fixes au maximum.

### 4.3 App téléphone

- Carte (OpenStreetMap) avec tous les aéronefs, étiquettes, traînée des 2 dernières minutes.
- Même liste « Arrivées / Posés ».
- Fiche aéronef : immat, CN, altitude, vario, vitesse, dernier contact.
- Réglages : terrain, pistes et piste en service, seuils d'alertes, son on/off,
  plafond d'affichage (AGL).

### 4.4 Configuration terrain

Fichier de config (JSON, éditable dans l'app, partageable par QR code) :
- Terrain : **LFNL** (Saint-Martin-de-Londres, CVVM), position, altitude, contour de
  l'emprise.
- Pistes : **deux pistes parallèles, une en herbe et une en dur**. Seuils (coordonnées),
  QFU, longueur, largeur, revêtement ; piste en service (manuel ou **auto-détectée**
  d'après les derniers atterrissages).
  - **Source des coordonnées** : pré-remplies depuis OpenStreetMap (contours des pistes
    LFNL), puis **vérifiées sur place** avec un mode « relevé » : on roule jusqu'à chaque
    coin de piste avec la golfette et on appuie sur « enregistrer ce point » (GPS du
    téléphone). Le résultat est exporté dans le fichier de config.
- Altitude terrain : ~200 m (valeur exacte à prendre sur la carte VAC).
- Pas de liste de flotte : l'identification vient d'OGN (§3).

## 5. Exigences non fonctionnelles

- **Latence** : position affichée < 5 s après réception OGN.
- **Réseau** : 4G du téléphone ; reconnexion automatique ; message clair si hors ligne.
- **Batterie** : service au premier plan uniquement quand l'app ou Android Auto est actif.
- **Sécurité routière** : respect des contraintes Android Auto (templates, pas de saisie
  en roulant, listes limitées).
- **Langue** : français.
- **Coût** : 0 € d'infra en v1 (pas de serveur ; OGN gratuit).

## 6. Architecture technique proposée

```
[FLARM planeurs] → [récepteurs OGN] → aprs.glidernet.org
                                            │ TCP APRS (filtre 30 km)
                                            ▼
                         ┌──────── Téléphone Android ────────┐
                         │ Service premier plan (Kotlin)      │
                         │  • client APRS + parseur OGN       │
                         │  • moteur de statuts (§4.1)        │
                         │  • cache DDB + config club         │
                         │        │                │          │
                         │   UI téléphone     Car App Library │
                         │   (Compose+carte)  (Android Auto)  │
                         └───────────────────────────┬───────┘
                                                     ▼
                                          Écran golfette (projection AA)
```

- **Langage** : Kotlin, Jetpack Compose, coroutines.
- **Android Auto** : `androidx.car.app` (Car App Library).
### Choix de la source de données — **décidé : option 1 (direct APRS)**

| Option | Principe | + | − |
|---|---|---|---|
| **1. Direct APRS (recommandé v1)** | Le téléphone ouvre lui-même la connexion `aprs.glidernet.org:14580`, filtre `r/LFNL/30` | Latence minimale (~1–2 s après le récepteur), **0 serveur**, marche même si le serveur PicBoard est en panne | Statuts calculés sur chaque téléphone ; 4G nécessaire |
| 2. Via le serveur PicBoard | Nouveau service sur le serveur PicBoard qui relaie les **positions** de la zone LFNL en WebSocket (la chaîne actuelle ne transporte que des évènements) | Un seul « cerveau » partagé avec PicBoard ; possibilité d'afficher le nom du pilote depuis la planche | Développement côté PicBoard, auth à prévoir, un saut réseau de plus, dépendance au serveur |

Recommandation : **option 1 en v1** (le flux APRS filtré sur 30 km est léger, c'est
exactement la source que la chaîne live de PicBoard consomme). L'option 2 reste possible
plus tard pour enrichir (pilote, planche) sans changer le moteur de statuts, qui sera
écrit comme une bibliothèque indépendante de la source.

### Contrainte Android Auto importante
Android Auto n'autorise que des **catégories d'apps** précises, chacune avec ses écrans
imposés (« templates ») :

| Option | Ce qu'on peut afficher | Distribution |
|---|---|---|
| **A. Catégorie « Navigation »** | Dessin libre → **vrai radar** avec pistes et planeurs | Pas acceptable sur le Play Store (on n'est pas un GPS) → **installation directe (APK)** + mode développeur Android Auto « sources inconnues » sur chaque téléphone |
| B. Catégorie « POI » | Listes + carte Google simple avec marqueurs (pas de pistes, rafraîchissement limité) | Play Store possible mais validation incertaine |
| C. Listes seulement | Listes texte + alertes | Idem B |

**Décidé : option A.** La golfette a un écran Android Auto standard intégré (Bluetooth +
Wi-Fi, projection depuis le téléphone). L'app n'ira **pas** sur le Play Store : APK
installé à la main + « sources inconnues » activé dans les options développeur
d'Android Auto, une fois par téléphone.

## 7. Découpage en versions

| Version | Contenu |
|---|---|
| **v0.1 – prototype** | Client OGN + moteur de statuts, app téléphone (liste + carte). Validation sur le terrain un week-end de vol. |
| **v1 – MVP golfette** | Android Auto : radar fixe orienté pistes, listes Arrivées / Posés, alerte à l’entrée sur piste, config terrain. |
| **v2** | Piste en service auto-détectée, vaches avec guidage. |
| **v3 (option)** | Passerelle via le serveur PicBoard (nom du pilote, écran club-house). |

Projet perso (développeur seul) : périmètre v1 volontairement réduit.

## 8. Tests

- **Rejeu** : enregistrer une journée de trames OGN réelles, la rejouer pour tester le
  moteur de statuts sans voler (tests unitaires + mode « démo » dans l'app).
- **Émulateur Android Auto** : Desktop Head Unit (DHU) de Google.
- **Terrain** : un week-end de vol avec un observateur au bord de la piste pour comparer
  « vu » vs « affiché » (finales manquées, fausses alertes).

## 9. Risques

| Risque | Impact | Parade |
|---|---|---|
| Récepteur OGN du club en panne / 4G absente | Plus de données | Afficher clairement « signal perdu » / « hors ligne », jamais un écran figé qui a l'air à jour |
| Avion visiteur sans FLARM / FLARM off / `no-track` | Invisible | Message permanent « information seulement » |
| Mises à jour Android Auto cassant le mode sources inconnues | App bloquée | Garder l'app téléphone utilisable seule |
| Fausses alertes (planeur qui passe bas sans se poser) | Lassitude | Seuils réglables, tests par rejeu |

## 10. Mentions légales / usage

- Respect de la politique de données OGN (opt-out `no-track`/`no-ident`, pas de stockage
  long terme).
- Bandeau au démarrage : « Aide à la vigilance, ne remplace pas l'observation visuelle. »

## 11. Questions ouvertes

Réglées en v0.2 : écran golfette (Android Auto intégré), distribution (APK hors Play
Store), terrain (LFNL, pistes parallèles herbe + dur), lancement (remorqueur seul),
FLARM (tous les planeurs), couverture (récepteur OGN du club), planche (PicBoard).

Réglées en v0.3 : source (direct APRS, push), alerte à l'entrée sur piste (la golfette
circule partout), trafic (tout OGN, < 300 m AGL par défaut), téléphones (celui de chaque
conducteur ; app non diffusée avant d'être prête), pas de liste de flotte (OGN seul).

Réglées en v0.4 : plafond 300 m AGL (~500 m QNH), bordure rouge sur piste sans son,
alerte uniquement golfette sur piste + trafic dans le circuit, pas de pré-alerte,
coordonnées des pistes OSM + relevé GPS sur place.

Plus de question bloquante : la spec est prête pour démarrer le prototype v0.1.
