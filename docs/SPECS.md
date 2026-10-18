# PicPiste — Spécifications (v0.1, brouillon pour review)

> App Android + Android Auto pour la golfette du club : voir où sont les planeurs,
> qui va se poser, et dégager la piste à temps.

Statut : **brouillon** — les points marqués ❓ attendent une réponse (voir §11).

---

## 1. Contexte et objectifs

La golfette sert à aller chercher les planeurs après l'atterrissage et à les ramener
au point de départ. Son conducteur a besoin de savoir, sans sortir son téléphone :

1. **Qui va se poser, et quand** → anticiper le prochain dépannage.
2. **Si un planeur est en finale** → ne pas être sur (ou près de) la piste à ce moment-là.
3. **Où sont les planeurs du club** → vue d'ensemble de l'activité.
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
| Admin club | Configure terrain, pistes, flotte |

## 3. Source des données : FLARM via OGN

Les planeurs émettent leur position via **FLARM**. Le réseau **OGN (Open Glider Network)**
capte ces trames et les republie gratuitement.

- **Flux temps réel** : serveur APRS OGN `aprs.glidernet.org:14580`, connexion TCP en
  lecture seule (passcode `-1`) avec filtre géographique `r/<lat>/<lon>/<rayon km>`.
  Mise à jour ≈ 1 à 5 s par aéronef.
- **Identification** : base OGN DDB (`ddb.glidernet.org`) → ID FLARM ⇒ immatriculation,
  numéro de concours, type. Complétée par la **liste de la flotte du club** (config locale).
- **Respect de la vie privée** : obligation de respecter les drapeaux OGN `no-track`
  (ne pas afficher) et `no-ident` (afficher anonymisé). Pas d'historique conservé
  au-delà de la journée.

### Point d'attention : couverture basse altitude
Les récepteurs OGN distants perdent souvent les planeurs **sous 200–300 m**, c'est-à-dire
justement en circuit et en finale. Vérifier la couverture sur notre terrain (❓ Q5).
Si elle est insuffisante : installer **notre propre récepteur OGN** au club
(Raspberry Pi + clé RTL-SDR + antenne 868 MHz, ~100 €) — il alimente aussi le réseau OGN
pour tout le monde.

## 4. Fonctionnalités

### 4.1 Statut de chaque aéronef (cœur de l'app)

Chaque aéronef suivi reçoit un statut calculé à partir de position, altitude sol (AGL),
vitesse, vario et cap :

| Statut | Règle indicative (paramétrable) |
|---|---|
| 🛫 **Remorquage / treuil** | Monte, < 1 500 m AGL, accompagné du remorqueur ou sortie de piste récente |
| ✈️ **En vol local** | En l'air, < 10 km du terrain |
| 🌍 **En vol (loin)** | En l'air, > 10 km |
| ↩️ **Retour** | < 5 km, se rapproche, < 600 m AGL |
| 🔄 **Dans le circuit** | < 3 km, < 350 m AGL (vent arrière / étape de base) |
| 🔴 **En finale** | Aligné sur l'axe d'une piste (±30°), < 2,5 km du seuil, < 200 m AGL, descend |
| 🟢 **Posé** | Vitesse sol < 10 km/h, altitude ≈ terrain, sur l'emprise du terrain |
| 🐄 **Vache** | Posé hors du terrain → position + lien navigation pour le dépannage |
| ❔ **Signal perdu** | Pas de trame depuis > 60 s (dernière position affichée, grisée) |

Pour « En finale » et « Dans le circuit » : **ETA estimée** (distance / vitesse sol) et
**piste probable** (selon l'axe d'approche).

### 4.2 Écran Android Auto (golfette)

Priorité absolue : **lisible en 1 seconde**, gros éléments, aucune saisie.

- **Liste « Arrivées »** triée par imminence :
  `🔴 F-CXYZ (AB) — Finale 28 — 40 s`
  `🔄 F-CKLM (12) — Circuit — ~2 min`
  `↩️ F-CABC — Retour — 4 km, 450 m`
- **Bandeau d'alerte + son** quand un planeur passe en finale :
  « Planeur en finale piste 28 — dégagez la piste ».
- **Liste « Posés »** : derniers planeurs posés, avec position (début/milieu/fin de piste,
  ou vache) → tap = guidage vers lui (ouverture Google Maps/Waze pour les vaches).
- **Vue radar** (v2, voir §6) : terrain et pistes dessinés, planeurs en icônes colorées
  par statut, rayon 5 km.

### 4.3 App téléphone

- Carte (OpenStreetMap) avec tous les aéronefs, étiquettes, traînée des 2 dernières minutes.
- Même liste « Arrivées / Posés ».
- Fiche aéronef : immat, CN, altitude, vario, vitesse, dernier contact.
- Réglages : terrain, pistes et piste en service, seuils d'alertes, son on/off,
  filtre « club seulement / tous ».

### 4.4 Configuration terrain & flotte

Fichier de config (JSON, éditable dans l'app, partageable par QR code) :
- Terrain : code OACI, position, altitude, contour de l'emprise.
- Pistes : seuils (coordonnées), QFU, longueur ; piste en service (manuel ou
  **auto-détectée** d'après les derniers atterrissages).
- Flotte club : immat, CN, ID FLARM, type (planeur / remorqueur / ULM / motoplaneur).

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
- **Pas de backend en v1.** Une petite passerelle serveur pourra venir en v3
  (écran au club-house, historique de la journée).

### Contrainte Android Auto importante
Android Auto n'autorise que des **catégories d'apps** précises, chacune avec ses écrans
imposés (« templates ») :

| Option | Ce qu'on peut afficher | Distribution |
|---|---|---|
| **A. Catégorie « Navigation »** | Dessin libre → **vrai radar** avec pistes et planeurs | Pas acceptable sur le Play Store (on n'est pas un GPS) → **installation directe (APK)** + mode développeur Android Auto « sources inconnues » sur chaque téléphone |
| B. Catégorie « POI » | Listes + carte Google simple avec marqueurs (pas de pistes, rafraîchissement limité) | Play Store possible mais validation incertaine |
| C. Listes seulement | Listes texte + alertes | Idem B |

**Recommandation : option A**, l'app étant réservée au club (1 golfette, quelques
téléphones). Coût : une manip unique par téléphone pour activer les sources inconnues.

## 7. Découpage en versions

| Version | Contenu |
|---|---|
| **v0.1 – prototype** | Client OGN + moteur de statuts, app téléphone (liste + carte). Validation sur le terrain un week-end de vol. |
| **v1 – MVP golfette** | Android Auto : listes Arrivées / Posés, alerte finale (bandeau + son), config terrain/flotte. |
| **v2** | Radar Android Auto, piste en service auto-détectée, vaches avec guidage. |
| **v3 (option)** | Récepteur OGN du club, écran club-house, lien avec la planche de vol. |

## 8. Tests

- **Rejeu** : enregistrer une journée de trames OGN réelles, la rejouer pour tester le
  moteur de statuts sans voler (tests unitaires + mode « démo » dans l'app).
- **Émulateur Android Auto** : Desktop Head Unit (DHU) de Google.
- **Terrain** : un week-end de vol avec un observateur au bord de la piste pour comparer
  « vu » vs « affiché » (finales manquées, fausses alertes).

## 9. Risques

| Risque | Impact | Parade |
|---|---|---|
| Trou de couverture OGN en finale | Alerte finale manquée | Récepteur OGN au club ; afficher clairement « signal perdu » |
| Planeur sans FLARM / FLARM off / `no-track` | Invisible | Message permanent « information seulement » |
| Écran golfette pas compatible Android Auto standard | App inutilisable dessus | Vérifier le modèle (❓ Q1) |
| Mises à jour Android Auto cassant le mode sources inconnues | App bloquée | Garder l'app téléphone utilisable seule |
| Fausses alertes (planeur qui passe bas sans se poser) | Lassitude | Seuils réglables, tests par rejeu |

## 10. Mentions légales / usage

- Respect de la politique de données OGN (opt-out `no-track`/`no-ident`, pas de stockage
  long terme).
- Bandeau au démarrage : « Aide à la vigilance, ne remplace pas l'observation visuelle. »

## 11. Questions ouvertes

1. ❓ **L'écran de la golfette** : marque/modèle ? C'est bien de la *projection* Android Auto
   depuis un téléphone (filaire ou sans fil) ? Ou l'écran tourne lui-même sous Android
   (on pourrait alors installer l'app directement dessus) ?
2. ❓ **Quel terrain** (code OACI) et quelles pistes (QFU, herbe/dur, pistes parallèles) ?
3. ❓ **Lancement** : remorqueur, treuil, ou les deux ?
4. ❓ **Flotte** : tous les planeurs du club et les privés basés ont-ils un FLARM ?
   Liste immat + CN dispo ?
5. ❓ **Couverture OGN** : voyez-vous vos planeurs jusqu'au sol sur glidertracker.org /
   live.glidernet.org ? Y a-t-il déjà un récepteur OGN sur le terrain ?
6. ❓ **Distribution** : OK pour installer l'APK à la main (mode développeur Android Auto),
   ou il faut absolument passer par le Play Store ?
7. ❓ **Quels téléphones** seront branchés dans la golfette (téléphone du club dédié, ou
   celui de chaque conducteur) ?
8. ❓ **Planche de vol** : vous utilisez un logiciel (GESASSO, Click'n Glide, OpenFlyers…) ?
   Intérêt pour une intégration plus tard ?
9. ❓ **Autres usages** souhaités : vaches lointaines, écran au club-house, remorqueur
   (savoir quand il redescend) ?
