# Mes parcours

Traqueur GPS Android privé pour activités de plein air : enregistrement de parcours,
carte OSM, statistiques en direct, import/export GPX & KML, fusion de parcours.

Application personnelle (dédiée « à mon père Thierry »), sans compte ni serveur :
toutes les données restent sur l'appareil.

- Nom affiché : « Mes parcours » (`app_name` dans `res/values/strings.xml`, garde-fou
  dans `ExampleRobolectricTest`). Anciennement « Sillage ».
- `applicationId` : `com.toche.mesparcours` — `namespace` Kotlin : `com.example`
- Version courante : `0.10` (`versionCode` 17). Elle n'est écrite qu'une fois,
  dans `app/build.gradle.kts` ; l'écran « À propos » la lit via `BuildConfig.VERSION_NAME`.
  Le suffixe `-thierry` a été abandonné à partir de la `0.9.15`.
- **Journal des nouveautés** : la liste `RELEASES` de `SettingsTab.kt`.
  - Jusqu'à la `1.0-thierry` **exclue** : une seule entrée, celle de la version
    courante, dont on remplace le contenu à chaque version. Les versions de
    développement se succèdent trop vite pour qu'un historique serve.
  - À partir de la `1.0-thierry` : **ajouter** une entrée en tête au lieu de
    remplacer. L'affichage sait déjà présenter plusieurs versions, séparateurs et
    pastille « actuelle » compris — il n'y aura rien d'autre à faire ce jour-là.
  - La mention « actuelle » se déduit de `BuildConfig.VERSION_NAME` et ne s'affiche
    qu'à partir de deux entrées ; elle n'est jamais à déplacer à la main.
- Le projet est né d'un échafaudage Google AI Studio, entièrement retiré depuis
  (métadonnées, plugin `secrets`, `GEMINI_API_KEY`, BOM Firebase, README d'origine,
  ancien `applicationId`). Voir « Identifiant de paquet » plus bas.

## Pile technique

Kotlin 2.2.10 · Jetpack Compose + Material 3 (couleurs dynamiques Material You) ·
Room 2.7 (KSP) · osmdroid · play-services-location · AGP 9.1.1 · Java 11 ·
minSdk 24 / targetSdk 36 / compileSdk 36.1

## Commandes

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Le SDK Android est indiqué par `local.properties` (non versionné). La variante
`release` exige les variables d'environnement `KEYSTORE_PATH`, `STORE_PASSWORD` et
`KEY_PASSWORD` ; la clé (`my-upload-key.jks`) n'est pas dans le dépôt.

Sur Windows, préférer `.\gradlew.bat` ; `--offline` accélère nettement les
itérations une fois les dépendances en cache.

**Ni l'un ni l'autre ne tourne dans Claude Code sur le web.** Le conteneur n'a pas
de SDK Android, et la politique réseau de la session bloque `dl.google.com` — donc
aussi bien `sdkmanager` que le téléchargement de la plateforme d'API 36. Gradle
lui-même fonctionne, et `maven.google.com` est joignable : seul le SDK manque, et
rien ne permet de l'installer depuis là.

Le détour, c'est `.github/workflows/debug-apk.yml` : le runner GitHub a le SDK, il
compile, joint les APK à l'exécution et enchaîne les tests. C'est ce qui permet de
vérifier une modification faite en session web — et d'en récupérer un APK
installable — sans machine de développement sous la main.

**Se lance uniquement à la main** (onglet Actions → « Compiler un APK » → Run
workflow) — plus automatiquement à chaque poussée, à la demande de l'auteur : chaque
compilation verse un APK dans l'historique git (poids détaillé plus bas), et il ne
veut payer ce coût que lorsqu'un APK est réellement demandé, pas à chaque commit
poussé en cours de route. En session Claude Code, ça veut dire pousser le code
normalement, mais **ne déclencher ce workflow que si l'auteur le demande
explicitement** (« compile », « fais-moi un APK »…) — jamais après une simple
implémentation.

Toujours un APK de debug ; **et un APK de release signé en plus dès que le secret
`KEYSTORE_BASE64` existe.** L'étape s'allume d'elle-même le jour où le trousseau est
créé, il n'y aura pas à retoucher le workflow. Le contexte `secrets` n'étant lisible
dans aucun `if`, la présence du secret transite par une sortie d'étape — c'est la
seule voie qui permette de sauter proprement la compilation signée quand il manque.

Ce workflow ne pose ni tag ni publication GitHub : il sert à essayer une version.
La vraie publication reste `release.yml`, déclenchée par un tag annoté.

**Les APK sont aussi reversés sur `main`**, à leur emplacement de compilation
(`app/build/outputs/apk/debug/app-debug.apk`, et l'équivalent en release), à la
demande de l'auteur qui veut les récupérer depuis l'arborescence GitHub sans passer
par les artefacts. Trois conséquences à connaître :

- `app/build` reste ignoré par git ; c'est `git add --force` qui verse le seul
  fichier voulu. Percer le `.gitignore` ferait au contraire remonter toutes les
  compilations locales de l'auteur à chaque `git status`.
- Le message de commit porte toujours `[skip ci]`, par habitude prudente — ce n'est
  plus strictement nécessaire depuis que le workflow ne se déclenche plus tout seul
  sur une poussée, mais ça ne coûte rien et protège si ce déclencheur revenait.
- Ces APK sont **tracés** : après une compilation locale, `git status` les
  signalera modifiés. `git update-index --skip-worktree <chemin>` les fait taire
  sur une machine donnée.

Le poids s'accumule dans l'historique — une vingtaine de Mio par version, que git
ne saura plus oublier sans réécriture. C'est le prix accepté du téléchargement
direct depuis l'arborescence.

**Le build type `debug` porte son propre `applicationId`** (suffixe `.debug`, donc
`com.toche.mesparcours.debug`) **et son propre `versionName`** (suffixe `-debug`,
donc par exemple `0.9.16-debug`). Deux conséquences voulues :

- Un APK de debug s'installe **à côté** de la version release, jamais par-dessus :
  Android les traite comme deux applications distinctes. Plus besoin de désinstaller
  la release pour essayer un debug, ni l'inverse.
- L'écran « À propos » d'un APK de debug affiche sans ambiguïté qu'il n'est pas la
  version release, même une fois installé.

**Attention à la clé de debug de l'APK produit en CI.** `debug.keystore` n'étant pas
versionné, le runner n'en a pas et AGP en fabrique un neuf **à chaque exécution**.
Deux APK de debug issus de deux exécutions ne portent donc pas la même signature, et
aucun ne porte celle de la machine de l'auteur : les installer l'un par-dessus
l'autre échoue avec « package signatures do not match », et il faut désinstaller —
donc perdre les parcours enregistrés **du debug** (la release, application distincte,
n'est jamais concernée). Un APK de CI sert à essayer une version, pas à mettre à jour
une installation existante. Verser un `debug.keystore` fixe au dépôt lèverait la
limite (une clé de debug n'est pas un secret), au prix de la règle actuelle qui le
garde dehors.

## Carte du code

```
MainActivity ──> MainScreen ──> 4 onglets : TrackingTab · HistoryTab · ToolsTab · SettingsTab
                     └────────> DetailView (recouvre les onglets quand un parcours est ouvert)

TrackViewModel (unique, créé par TrackViewModel.Factory)
     │
     ▼
TrackRepository (singleton) ◄──── TrackingService (service de premier plan GPS)
     │                       ◄──── BootReceiver (reprise après redémarrage/crash)
     ▼
AppDatabase / TrackDao  ──  entités Track + TrackPoint
```

- `data/` — modèles, `TrackDao`, `AppDatabase`, `TrackRepository`
- `service/TrackingService` — acquisition GPS, accumulateurs de statistiques, notification
- `ui/component/MapViewContainer` — pont osmdroid ↔ Compose (le plus gros fichier)
- `util/` — `Importer`, `Exporter`, `MediaStoreExporter`, `AutoBackupManager`,
  `SolarTimes`, `TunnelDetector`, `Altitude`, `ElevationAccumulator`, `FormatUtils`,
  `KmlColor`, préférences de style

## Couleurs des parcours importés

Un KML porte la couleur de chaque tracé ; l'application peut la conserver plutôt que
d'appliquer une couleur unique. Le réglage est un interrupteur dans
« Paramètres → Tracés », doublé d'un raccourci par appui long sur l'onglet
« Importés » de l'historique.

Trois pièges, chacun visible à l'écran s'il n'était pas traité :

- **KML note ses couleurs en AABBGGRR**, Android attend ARGB. Recopier la valeur
  échange le rouge et le bleu. `KmlColor.parse` convertit, `KmlColorTest` verrouille.
- **La couleur appartient au point, pas au parcours** (`TrackPoint.segmentColor`). Un
  export Google Earth réunit couramment des dizaines de voyages dans un seul fichier :
  une couleur par parcours les peindrait tous pareil.
- **Le style se résout, il ne se devine pas.** Un `<Placemark>` renvoie à un `<Style>`
  par identifiant, éventuellement via un `<StyleMap>` qui distingue l'apparence au
  repos de celle au survol. Prendre le premier `<LineStyle>` venu ramène parfois la
  couleur de survol, que l'utilisateur ne voit jamais. `KmlStyleTable` fait la
  résolution, avec une profondeur bornée contre les renvois circulaires.

Le répertoire des styles est relevé par une **première lecture du fichier**, avant les
points : un style déclaré après le trajet qui l'utilise arriverait sinon trop tard,
les points étant déjà écrits en base. Cette passe ignore les `<coordinates>`, son
empreinte reste donc de quelques dizaines d'entrées quelle que soit la taille du
fichier.

Un tracé sans couleur — tous les GPX, et les KML sans style de ligne — retombe sur
la couleur de la palette. Sans ce repli il serait dessiné en noir, donc invisible sur
le fond de carte sombre. Une couleur totalement transparente est refusée pour la même
raison.

Corollaire à l'affichage : `buildSegmentsFromPoints` coupe aussi sur **changement de
couleur**, une polyligne osmdroid n'en portant qu'une.

`TrackRepository` est la **source de vérité** de l'état en direct (`isTracking`,
`livePoints`, `liveStats`, `gpsStatus`…). Le service écrit, l'interface lit.

## Outils

`ui/screen/ToolsTab.kt` centralise les opérations qui portent sur un ou plusieurs
parcours déjà enregistrés — fusion, nettoyage. Chaque outil suit le même gabarit :
une carte de sélection de parcours (`SingleTrackRow` pour un seul, `MergeTrackRow`
pour plusieurs), un ou deux réglages, un bouton de confirmation.

- **Supprimer les points immobiles** écarte tout point à moins d'une distance
  choisie (par défaut 1 m) du dernier point conservé, sauf les marqueurs de rupture
  de tronçon (`TrackPoint.isDiscontinuous`), toujours gardés pour ne pas recoller
  deux tronçons distincts.
  **Le résultat est toujours une copie** : `TrackRepository.removeStationaryPoints`
  ne modifie jamais la trace d'origine, qui reste intacte dans l'historique — un
  choix délibéré, la suppression de points étant irréversible contrairement à la
  fusion (qui ne fait que recombiner des données, sans en perdre). Les statistiques
  de la copie (distance, vitesses, dénivelé) sont recalculées à partir des points
  effectivement conservés, avec le même seuil que `loadResumeState`
  (`statsRecomputeLimit`, 200 000 points) : au-delà, celles de la trace d'origine
  sont reprises telles quelles plutôt que de charger des millions de points en
  mémoire pour le recalcul.

## Invariants à ne pas casser

Ces points ont chacun corrigé un bug réel ; les commentaires du code expliquent
pourquoi. À relire avant d'y toucher.

1. **Singletons à double vérification** (`AppDatabase`, `TrackRepository`). Deux
   instances = deux jeux de `StateFlow` et de suivis d'invalidation Room : le
   service alimente l'une pendant que l'écran observe l'autre, et l'interface cesse
   silencieusement de se mettre à jour.
2. **Toutes les migrations Room sont obligatoires.** La base est construite avec
   `fallbackToDestructiveMigration()` : retirer l'une des migrations déclarées —
   `MIGRATION_4_5` (index de fenêtre de vue), `MIGRATION_5_6` (`Track.sourceColor`),
   `MIGRATION_6_7` (`TrackPoint.segmentColor`) — effacerait tous les parcours des
   utilisateurs existants. Toute nouvelle version de schéma doit venir avec la sienne.
   **Vital** depuis le passage aux mises à jour sur place : plus rien ne rattrape un
   oubli, voir « Identité de l'application ».
   Sur `track_points`, préférer une colonne **nullable et sans valeur par défaut** :
   SQLite se contente alors de modifier le schéma, là où une valeur par défaut
   réécrirait toutes les lignes — plusieurs millions sur une trace importée.
3. **Affichage par fenêtre de vue.** `getDisplayPoints()` renvoie une silhouette
   sous-échantillonnée + le détail de la zone visible. Ces points servent
   **uniquement à dessiner**. Ne jamais exporter depuis eux : l'export passe par
   `forEachPoint()`, qui relit 100 % des points page par page.
   Corollaire : **tout déplacement programmatique de la carte doit republier la zone
   visible.** osmdroid ne produit d'événement de défilement exploitable que pour les
   gestes de l'utilisateur ; un `controller.animateTo()` laissait donc le ViewModel
   sur l'ancienne zone, et la partie nouvellement visible d'un tracé dense restait
   réduite à sa silhouette jusqu'à ce qu'on fasse glisser la carte à la main. Pendant
   le suivi automatique, la republication est périodique et non accrochée au
   recentrage : les positions arrivent environ une fois par seconde et annuleraient un
   effet qui en dépendrait avant la fin de l'animation.
4. **Import et export en flux.** `Importer.PointSink` et `Exporter.GpxWriter` /
   `KmlWriter` ne gardent jamais plus d'un lot en mémoire — une trace de plusieurs
   millions de points doit passer. Le KML est parsé en SAX, pas en `XmlPullParser`
   (qui reconstruirait tout le bloc `<coordinates>` en mémoire).
5. **La fusion se fait dans SQLite** (`INSERT … SELECT`), aucun point ne transite
   par la mémoire. Le `ORDER BY id` de `copyPointsInto` est indispensable : sans
   lui, le planificateur peut recopier dans l'ordre des latitudes.
6. **`lastLocation == null` ⇒ nouveau tronçon.** C'est le seul mécanisme de
   discontinuité (démarrage, sortie de pause, reprise). Ne jamais l'initialiser
   avec le dernier point d'une trace reprise : cela ajouterait la distance à vol
   d'oiseau entre l'ancien tronçon et le point de reprise.
7. **Traitement des points GPS sérialisé** sur `pointExecutor` (un seul thread).
   `LocationResult` livre plusieurs positions d'un coup ; en parallèle, les
   accumulateurs partagés se corrompent.
8. **La simulation GPS ne doit jamais s'activer en release.** Dans
   `TrackingService`, elle est gardée par `BuildConfig.DEBUG` — sinon l'application
   enregistrerait un trajet inventé que l'utilisateur croirait réel.
9. **Ne jamais appeler `isSystemInDarkTheme()` ailleurs que dans `NightMode.kt`.**
   Le thème sombre peut être piloté par le capteur de luminosité, pas seulement par
   le réglage d'Android. `MyApplicationTheme` résout l'état une fois et le publie
   via `LocalIsDarkTheme` : c'est ce que doit lire tout ce qui échappe à Material,
   le filtre de tuiles de la carte en particulier.

## Mode nuit

`ui/theme/NightMode.kt` décide du thème sombre, selon `pref_night_mode_source` :

- `system` — le réglage d'Android (comportement par défaut).
- `solar` — les heures réelles du soleil, plus une bascule temporaire dans les
  tunnels (`pref_night_mode_tunnel`, active par défaut).

Deux briques pures, sans dépendance Android, donc directement testables :

**`util/SolarTimes`** calcule le lever et le coucher du soleil (équation du lever du
soleil, formulation NOAA) à moins de deux minutes des heures publiées. Ce calcul
remplace délibérément un appel à un service météo : il fonctionne hors ligne — en
tunnel, en montagne — n'exige aucune clé d'API et ne transmet la position de
l'utilisateur à personne. Les cas polaires (`PolarDay` / `PolarNight`) doivent rester
traités explicitement : sans eux, `acos` renvoie `NaN`.

La position vient de `pref_last_lat` / `pref_last_lng`, déjà écrits par le ViewModel
à chaque déplacement de carte. Le GPS n'est pas allumé pour choisir une couleur de
fond : un degré de longitude ne décale les heures que de quatre minutes, une
précision de quelques dizaines de kilomètres suffit largement.

**`util/TunnelDetector`** distingue un tunnel d'un intérieur. Un seuil absolu — ce
qu'utilisait la version précédente — confond « il fait sombre » et « je suis à
l'intérieur en pleine journée » : un salon éclairé tourne autour de 150 lux et
faisait basculer l'application en sombre en plein jour. Le critère retenu cumule
donc trois conditions à l'entrée : obscurité absolue (≤ 20 lux), effondrement
relatif à la luminosité ambiante des instants précédents (≤ 8 %), et persistance
pendant une seconde.

La référence ambiante est **gelée tant que l'on est à l'intérieur**. Sans ce gel,
elle rejoindrait le niveau du parking où l'on stationne, l'effondrement relatif
disparaîtrait et l'affichage repasserait au clair dans le noir. Corollaire heureux :
la tombée de la nuit, lente, est suivie par la référence et n'est jamais prise pour
un tunnel — c'est le calcul solaire qui la traite.

Le capteur n'est écouté que de jour (la nuit, le thème est déjà sombre) et l'écoute
est liée au cycle de vie.

## Altitude

`Location.getAltitude()` renvoie une hauteur au-dessus de l'**ellipsoïde WGS84**,
alors que toute altitude lue sur une carte ou un panneau se réfère au **géoïde**, le
niveau moyen des mers. L'écart — de 45 à 50 m sur la France métropolitaine — est un
biais systématique, pas du bruit. Afficher la valeur brute rendait l'altitude
constamment trop haute d'une cinquantaine de mètres.

`util/Altitude.kt` regroupe toute la chaîne :

- **`AltitudeResolver`** écarte les relevés sans altitude (`getAltitude()` renvoie
  `0.0` dans ce cas, ce qui était enregistré comme une altitude réelle), convertit
  vers le niveau de la mer via `AltitudeConverter` — modèle de géoïde embarqué dans
  Android 14+, hors ligne — et rejette les altitudes dont l'incertitude verticale
  dépasse 20 m. `resolve()` fait des entrées-sorties : **jamais sur le thread
  principal**. En deçà d'Android 14, il ne renvoie rien : mieux vaut afficher « — »
  qu'une valeur fausse de 50 m. C'est le seul endroit où brancher un géoïde embarqué
  si l'on veut couvrir les versions antérieures.
- **`AltitudeSmoother`** enchaîne une médiane glissante — qui écarte une aberration
  isolée au lieu de la moyenner — puis une moyenne exponentielle.

`util/ElevationAccumulator.kt` cumule le dénivelé **par rapport à une altitude de
référence** qui ne se déplace qu'au-delà de 3 m. Le critère précédent, un seuil de
0,80 m entre points consécutifs, se trompait deux fois : il ratait les montées
régulières (à 1 Hz sur une pente de 10 %, l'altitude ne progresse que de 0,14 m par
seconde, donc rien n'était compté) et gonflait le dénivelé à l'arrêt, où le bruit
franchit le seuil dans un sens puis dans l'autre.

L'altitude **enregistrée** est celle qui a été convertie et lissée : les fichiers GPX
exportés portent donc une balise `<ele>` au-dessus du niveau de la mer, comme le veut
le format. L'affichage lit `TrackRepository.currentAltitude`, un flux séparé de
`currentUserLocation` parce que `TrackPoint.altitude` n'est pas nullable et ne peut
pas distinguer « inconnue » de « nulle ».

La rupture de tronçon du dénivelé se raccroche au **même signal que la discontinuité
du tracé** (`lastLocation == null`, invariant 6), et non à un drapeau écrit depuis le
thread principal.

## Thème sombre de la carte

Les tuiles Mapnik sont assombries par `DARK_TILES_COLOR_FILTER` dans
`MapViewContainer` : **inversion, puis rotation des teintes de 180°**. On obtient les
couleurs de Mapnik assombries — vert foncé pour le gazon, bleu profond pour l'eau,
fond sombre à la place du papier crème, libellés clairs donc lisibles.

Deux approches ont précédé celle-ci, et chacune a échoué pour une raison différente
qu'il faut garder en tête avant d'y retoucher :

- `TilesOverlay.INVERT_COLORS`, une inversion nue. Inverser retourne la teinte autant
  que la clarté : les forêts devenaient magenta et l'eau brune.
- Désaturer puis inverser. La dominante disparaissait, mais toute couleur avec elle :
  la carte devenait un dégradé de gris, sans le repère immédiat du vert et du bleu.

La rotation de teinte d'un demi-tour après l'inversion remet les teintes à leur place
et ne laisse inversée que la clarté. Chaque ligne de `HUE_ROTATE_180_MATRIX` somme
à 1, ce qui garantit que les gris — routes, libellés — ne prennent pas de dominante.

Limite connue : le blanc pur ressort en noir pur, plus sombre que le fond (`#13100A`).
Les petites routes blanches de Mapnik sont donc peu contrastées. C'était déjà le cas
avec les deux approches précédentes ; corriger demanderait de comprimer la plage de
sortie, au prix du contraste général.

La vue satellite n'est jamais filtrée : une photo aérienne n'a pas de fond clair à
inverser, et l'assombrir la rendrait illisible.

## Conventions

- Commentaires et interface **en français**. Les commentaires expliquent *pourquoi*,
  pas *quoi* — souvent le bug qu'ils préviennent. Garder ce registre.
- Compose partout, pas de XML de layout. Pas de bibliothèque de navigation : la
  navigation est un `when` sur une `String` d'onglet dans `MainScreen`.
- Préférences via `android.preference.PreferenceManager` (déprécié mais uniforme
  dans tout le projet — ne pas migrer à moitié).
- `testTag` posés sur les éléments d'interface pour les tests Compose.
- **Écran des réglages** : un groupe par sujet (`SettingsGroupHeader`), une carte par
  réglage (`SettingsCard`), et jamais deux réglages sans lien dans la même carte.
  Les briques `SettingsChoiceList` (options exclusives), `SettingsToggleRow`
  (interrupteur) et `SettingsHint` (remarque) sont là pour ça : un réglage de plus ne
  doit coûter qu'une liste de données, pas un bloc recopié. L'ordre des groupes suit
  la fréquence d'usage, du plus courant au plus rare.

## État actuel

- `assembleDebug` et `testDebugUnitTest` passent.
- 72 tests unitaires : `SolarTimesTest` (9), `KmlColorTest` (8), `UpdateManifestTest`
  (7), `TrackSegmentsTest` (7), `KmlStyleTableTest` (7), `AltitudeSmootherTest` (7),
  `TunnelDetectorTest` (6), `ElevationAccumulatorTest` (6), `DarkTilesColorFilterTest`
  (6), `MergeTracksTest` (4), `RemoveStationaryPointsTest` (2), plus trois tests
  d'échafaudage hérités (`ExampleUnitTest`, `ExampleRobolectricTest`,
  `GreetingScreenshotTest` avec Roborazzi).
- Sous git depuis le premier commit de l'état `0.9.8-thierry`, branche `main`.

## Où en est le projet

### Ce qui est en place

- Le dépôt existe et il est **public** : `https://github.com/ToftMalone/Mes-parcours`,
  branche par défaut `main`. Sa visibilité n'est pas un détail — `update.json` est
  téléchargé sans jeton, et un dépôt privé répondait 404, ce qui rendait toute la
  recherche de mise à jour inopérante en silence.
- **Le trousseau de signature existe** et les quatre secrets du dépôt sont renseignés.
  `assembleRelease` produit donc un APK signé, en local comme en intégration continue.
- **Deux versions ont été publiées** (`v0.9.9-thierry`, `v0.9.10-thierry`) : la chaîne
  de publication a tourné en vrai, `update.json` répond à l'URL attendue, et une
  application installée détecte désormais les versions suivantes toute seule.

### En attente d'une action de l'auteur

- Rien ne bloque plus la chaîne de publication.

### À vérifier sur le terrain, rien n'a été modifié

- **Le mode 3D pivote sans arrêt.** Le cap est calculé entre deux positions
  consécutives avec un seuil de 1,5 m — sous le plancher de bruit du GPS — puis
  appliqué sans lissage : à faible vitesse, l'orientation suit le bruit. Prédiction à
  confirmer : le défaut doit se calmer nettement en voiture et s'affoler à l'arrêt. Si
  c'est le cas, conditionner la mise à jour du cap à la vitesse (`TrackPoint.speed`
  existe) et lisser par interpolation sur l'arc le plus court, ce qui règle du même
  coup le passage par le nord.

### En attente d'arbitrage

- `VIEWPORT_MARGIN` est passé de 0,35 à 0,60 à la demande de l'auteur. À évaluer.
  Surveiller l'effet indirect documenté au-dessus de la constante : la zone élargie
  sert aussi à `coversMostOfTrack`, donc l'affichage se rabat plus tôt sur la seule
  silhouette. Si le tracé paraît plus grossier, mesurer cette couverture sur la zone
  réellement visible plutôt que sur la zone élargie.
- Le correctif de republication de la zone visible pendant le suivi automatique
  n'agit que sur les traces de plus de 60 000 points ; en deçà, tout est chargé d'un
  coup. Reste à savoir si le symptôme rapporté concerne bien ce cas, et si « les
  traits » désignent le tracé ou le fond de carte.
- **Deuxième levier disponible** pour le même sujet : ne plus réinterroger la base
  quand la zone visible reste incluse dans ce qui est déjà chargé. Gratuit en
  performance. Subtilité : ne pas sauter le rechargement lors d'un zoom avant, sous
  peine de perdre le gain de détail attendu.
- `Importer` cumule le dénivelé **sans seuil**, alors que l'enregistrement et la
  reprise passent par `ElevationAccumulator`. À harmoniser, mais cela changerait les
  statistiques des futurs imports.
- `FormatUtils` sait tout formater en miles et en pieds, `MainActivity` lit
  `pref_is_metric` au démarrage, mais **aucun écran n'écrit cette préférence** : le
  support impérial est inatteignable. Soit ajouter un réglage, soit retirer le code.
- Aucune coupure automatique sur saut de distance anormal. Une trace importée dont le
  fichier ne sépare pas ses tronçons apparaît donc d'un seul tenant, sans que
  l'application puisse le deviner.

## Poids mort : traité

Le ménage a été fait. Ce qui a été retiré, et qui ne doit pas revenir par
inadvertance :

- Dépendances déclarées et jamais appelées, vestiges d'une sauvegarde Drive
  abandonnée : `retrofit`, `converter-moshi`, `moshi-kotlin` (+ son processeur KSP),
  `okhttp`, `logging-interceptor`, `work-runtime-ktx`, `credentials`,
  `credentials-play-services-auth`, `googleid`. Elles voyageaient dans chaque APK
  installé sans qu'une ligne de code les touche.
- Code mort : `Exporter.exportToGPX` / `exportToKML` (variantes en mémoire, que les
  writers incrémentaux remplacent), `MediaStoreExporter.saveToLocalDownloads`,
  `TrackRepository.insertPoints`, `getPointsForTrackFlow`,
  `getSelectedImportedPointsFlow` et leurs requêtes DAO,
  `AutoBackupPreferences.isDestLocal` / `setDestLocal`.
- Asset jamais chargé : `france_boundary.geojson`.
- La simulation GPS de `MainScreen` n'était pas gardée par `BuildConfig.DEBUG`,
  contrairement à celle de `TrackingService` : sur un appareil réel dont la détection
  heuristique d'émulateur se déclenchait à tort, ou simplement sans fournisseur de
  position actif, un point bleu fictif tournait dans Paris. Corrigé — l'invariant 8
  vaut pour les deux.

`Track.isMerged` n'est plus du poids mort : la colonne porte de nouveau l'onglet
« Fusionnés » de l'historique.

### Reste à arbitrer

- **`TrackRepository._livePoints` grandit sans borne pendant un enregistrement**, et
  chaque point recopie toute la liste (`_livePoints.value + point`). Le coût est donc
  quadratique sur la durée, et la liste finit par tenir plusieurs mégaoctets. Le
  plafond existe déjà pour la reprise (`liveTailLimit`, 20 000 points) ; l'appliquer
  ici bornerait le coût, au prix du début du tracé qui disparaîtrait de l'écran sur un
  enregistrement de plus de cinq heures. À trancher avec l'auteur, l'effet étant
  visible.
- Les entrées de `libs.versions.toml` correspondant aux dépendances retirées sont
  restées : inertes à la compilation, elles documentent ce qui a existé.

## Mise à jour de l'application

L'application se met à jour depuis **GitHub Releases**, sans magasin d'applications.

`util/update/AppUpdate.kt` lit un fichier `update.json` joint à chaque publication,
via l'URL `https://github.com/OWNER/REPO/releases/latest/download/update.json`. Cette
forme d'URL est stable : GitHub la redirige toujours vers la publication la plus
récente, ce qui évite l'API GitHub, ses quotas et son jeton.

```json
{
  "versionCode": 9,
  "versionName": "0.9.9-thierry",
  "apkUrl": "https://github.com/…/releases/download/v0.9.9-thierry/mes-parcours.apk",
  "notes": ["Première nouveauté", "Deuxième nouveauté"]
}
```

- La comparaison porte sur **`versionCode`**, un entier. Comparer « 0.9.10 » et
  « 0.9.9 » comme des chaînes conclurait l'inverse.
- `apkUrl` **doit être en HTTPS** : ce fichier va être installé, son transport doit
  être authentifié. `UpdateManifest.parse` rejette le reste.
- Tant que `UpdateConfig.GITHUB_OWNER` ou `GITHUB_REPO` est vide, **aucune requête
  n'est émise** et l'application se comporte comme avant.
- Un échec de recherche est silencieux : ne pas joindre GitHub ne concerne pas
  l'utilisateur.
- `util/update/UpdateDownloader` télécharge dans un `.part` renommé à la fin, pour ne
  jamais présenter un APK tronqué à l'installateur, et vérifie la taille annoncée.
- L'installation silencieuse est impossible pour une application ordinaire : le
  système affiche toujours son écran de confirmation.

## Publier une version

`.github/workflows/release.yml` fait tout : tests, compilation signée, `update.json`,
publication GitHub. Deux façons de le déclencher, pour un résultat identique.

**Par un tag annoté**, depuis un poste de développement :

```bash
git tag -a v0.9.9-thierry -m "Titre" -m "Première nouveauté" -m "Deuxième"
git push origin v0.9.9-thierry
```

Le corps du tag devient les notes affichées dans la boîte de dialogue de mise à jour,
une puce par ligne. Le workflow **refuse de publier** si le tag ne correspond pas au
`versionName` de `app/build.gradle.kts` : sans ce garde-fou, on publierait une version
que personne ne pourrait installer par-dessus la précédente.

**À la main** (onglet Actions → « Publier une version » → Run workflow), en saisissant
les notes, une par ligne. Le tag est alors déduit du `versionName` et créé par la
publication elle-même.

Cette seconde voie n'est pas un confort : **créer un tag exige des droits d'écriture
sur les références qu'une session Claude Code sur le web n'a pas.** Elle peut pousser
du code sur une branche, mais `git push origin <tag>` lui revient en HTTP 403. Sans ce
déclenchement manuel, publier exigerait une machine de développement sous la main — ce
qui vide de son sens la possibilité de développer depuis le web.

Le garde-fou tag ↔ `versionName` n'a rien à vérifier sur cette voie, le tag étant
déduit de la version. Il est remplacé par un refus de republier une version déjà
publiée, qui joue le même rôle : forcer l'incrément avant de repartir.

### Secrets du dépôt à renseigner

| Secret | Contenu |
| --- | --- |
| `KEYSTORE_BASE64` | Le trousseau `.jks` encodé en base64 |
| `STORE_PASSWORD` | Mot de passe du trousseau |
| `KEY_PASSWORD` | Mot de passe de la clé |
| `KEY_ALIAS` | Alias de la clé (`upload` par défaut) |

```bash
base64 -w 0 my-upload-key.jks > keystore.base64.txt
```

### Compiler une version signée en local

Créer à la racine un fichier `keystore.properties`, **jamais versionné** :

```properties
storeFile=C:/chemin/vers/my-upload-key.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

`app/build.gradle.kts` le lit en priorité, puis retombe sur les variables
d'environnement — c'est cette seconde voie qu'emprunte l'intégration continue. Aucun
mot de passe n'apparaît donc jamais dans le code ni dans l'historique git.

### Créer le trousseau, une fois pour toutes

```bash
keytool -genkeypair -v -keystore my-upload-key.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

**À sauvegarder ailleurs que sur la machine de développement.** Le perdre rend toute
mise à jour ultérieure impossible à installer : il faudrait repartir d'une
désinstallation, donc de la perte des parcours des utilisateurs.

## Identité de l'application : gelée

Le projet est passé aux **mises à jour sur place**, qui conservent les parcours de
l'utilisateur. Trois points en découlent, et ils ne sont plus négociables :

1. **`applicationId = "com.toche.mesparcours"` est définitif pour la release.** Le
   changer ferait échouer toutes les mises à jour : Android installerait une seconde
   application à côté, et la base de la première deviendrait inaccessible. (Il a
   successivement valu `com.aistudio.mytracks.eaddfb`, hérité de l'échafaudage AI
   Studio, puis `com.toche.sillage` — c'est terminé.) Le build type `debug`, lui,
   porte volontairement un `applicationIdSuffix` différent : voir « Commandes »
   plus haut pour la raison (installation côte à côte avec la release, jamais une
   mise à jour l'une de l'autre).
2. **La clé de signature est définitive.** Un APK signé par une autre clé est refusé
   avec « package signatures do not match » et impose une désinstallation. Les
   versions distribuées doivent donc toutes être signées avec la clé de `release`,
   jamais avec `debug.keystore`.
3. **Chaque changement de schéma Room exige sa migration** (invariant 2). C'était
   théorique tant qu'on désinstallait à chaque fois ; c'est désormais vital, car
   `fallbackToDestructiveMigration()` effacerait les parcours sans prévenir.

Rien ne référence l'`applicationId` en dur : le manifeste passe par
`${applicationId}.fileprovider` et le code Kotlin par `context.packageName`.

Le `namespace` et le paquet Kotlin `com.example` restent, eux, ceux de
l'échafaudage. Sans effet à l'exécution, mais les renommer toucherait chaque
fichier source ainsi que les constantes d'action de `TrackingService`
(`"com.example.service.action.START"`, etc.).
