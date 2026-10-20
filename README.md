# PicPiste

**Le radar des planeurs pour la golfette du club.**

PicPiste montre en direct où sont les planeurs autour du terrain, qui va se poser et où,
et prévient le conducteur de la golfette quand il est sur une piste alors qu'un planeur
arrive. Sur l'écran Android Auto de la golfette, ou simplement sur le téléphone.

> PicPiste est une **aide à l'information**, pas un outil de contrôle aérien.
> Regarder dehors reste la règle.

## Ce que fait PicPiste

- **Radar en direct** : tous les planeurs, remorqueurs et autres aéronefs équipés FLARM
  autour du terrain, mis à jour toutes les quelques secondes.
- **Statut de chaque planeur** : décollage, remorqué, en vol, retour, circuit, finale, posé —
  on sait qui arrive et dans combien de temps.
- **Qui s'est posé où** : touchez un planeur posé pour le voir sur le radar et aller le
  chercher au bon endroit.
- **Alerte piste** : si la golfette est sur une piste pendant qu'un planeur est en finale,
  en circuit ou au décollage, un son et un bandeau rouge préviennent (« Planeur en finale —
  40 s »). Peu d'alertes, donc des alertes qu'on écoute.
- **N'importe quel terrain** : choisissez votre terrain par son code OACI, ajustez pistes et
  bâtiments sur la carte (ou sur place avec le GPS), puis partagez-le aux autres conducteurs.
- **Mode jour / nuit** automatique, en **français ou en anglais** selon la langue du téléphone.
- **Respect de la vie privée** : les aéronefs qui ont demandé à ne pas être suivis ne sont
  pas affichés, ceux qui ont demandé l'anonymat sont affichés sans nom.

Les données viennent du réseau **OGN (Open Glider Network)**, qui relaie les positions
FLARM des aéronefs. Il faut un récepteur OGN près du terrain pour voir les planeurs jusqu'au
sol, et une connexion internet (4G) sur le téléphone.

## Installer

1. Télécharger le dernier `PicPiste-x.y.apk` depuis les
   [Releases](https://github.com/CorentinGrard/PicPiste/releases).
2. L'ouvrir sur le téléphone Android et autoriser l'installation (« sources inconnues »).
3. Les mises à jour s'installent de la même façon, par-dessus l'ancienne version.

### Activer PicPiste dans Android Auto

L'app n'étant pas sur le Play Store, il faut l'autoriser une fois :

1. Sur le téléphone : Android Auto → Réglages → taper 10 fois sur « Version » → menu
   ⋮ → Paramètres pour les développeurs → cocher **Sources inconnues**.
2. Brancher le téléphone sur la golfette : PicPiste apparaît dans le lanceur Android Auto.

## Premiers pas

1. **Choisir le terrain** : au premier lancement, entrez le code OACI (ex. LFNL, LFLE) ou
   ouvrez un fichier terrain envoyé par un autre conducteur.
2. **Vérifier le terrain** : Réglages (⋯) → Terrain → **Modifier**. Touchez une piste, un
   seuil ou un hangar pour le sélectionner et glissez ses points. Sur place, roulez jusqu'au
   point et touchez **Placer ce point ici**. Puis **Enregistrer**.
3. **Partager le terrain** : dans l'éditeur, ⋯ → **Partager** envoie un fichier
   `PicPiste-XXXX.json`. L'autre conducteur l'ouvre avec PicPiste, vérifie, puis enregistre.

## Essayer sans vol

- **Démo** — Réglages (⋯) → Trafic → **Démo** : des planeurs et une golfette simulés autour
  de votre terrain (circuit, finale, remorqué, alertes).
- **Enregistrer une journée** — Réglages (⋯) → **Enregistrer la journée** pendant que ça
  vole. OGN ne garde pas l'historique : il faut enregistrer le jour même.
- **Rejeu** — Réglages (⋯) → Trafic → **Rejeu** (×1, ×4, ×10) rejoue le dernier
  enregistrement, avec une golfette simulée.

## Pour les développeurs

Construction, tests, CI et clé de signature : [docs/DEVELOPPEMENT.md](docs/DEVELOPPEMENT.md).
Spécifications : [docs/SPECS.md](docs/SPECS.md).
