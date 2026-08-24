# Mes parcours

Traqueur GPS Android privé pour activités de plein air : enregistrement de parcours,
carte OSM, statistiques en direct, import/export GPX & KML, fusion de parcours.

Application personnelle (dédiée « à mon père Thierry »), sans compte ni serveur :
toutes les données restent sur l'appareil.

- Nom affiché : « Mes parcours » (`app_name` dans `res/values/strings.xml`, garde-fou
  dans `ExampleRobolectricTest`). Anciennement « Sillage ».
- `applicationId` : `com.toche.mesparcours` — `namespace` Kotlin : `com.example`
- Licence : GPL-3.0 (`LICENSE`, texte officiel complet de la Free Software Foundation).
- Version courante : `0.12.0` (`versionCode` 28). Elle n'est écrite qu'une fois,
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
- Ces commits ne portent **pas** l'identité `github-actions[bot]`, à la demande de
  l'auteur qui ne voulait pas le voir dans la liste des contributeurs du dépôt.
  L'adresse utilisée (`ci@mes-parcours.invalid`, domaine réservé par la RFC 2606)
  ne correspond à aucun compte GitHub : ces commits n'y sont donc rattachés à
  personne. Ne pas revenir à l'adresse officielle du bot
  (`…@users.noreply.github.com`) sans le vouloir explicitement.

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
2. **Toutes les migrations Room sont obligatoires.** Retirer l'une des migrations
   déclarées — `MIGRATION_4_5` (index de fenêtre de vue), `MIGRATION_5_6`
   (`Track.sourceColor`), `MIGRATION_6_7` (`TrackPoint.segmentColor`) — rendrait la
   base illisible pour tout utilisateur venant d'une version antérieure. Toute
   nouvelle version de schéma doit venir avec la sienne.
   Deux garde-fous, depuis la `0.11.2`, là où l'invariant ne tenait qu'à la vigilance :
   la base n'est plus construite avec `fallbackToDestructiveMigration()` mais avec
   `fallbackToDestructiveMigrationOnDowngrade()` — une migration oubliée fait
   désormais échouer l'ouverture au lieu d'effacer l'historique sans un mot ; et
   `MigrationChainTest` vérifie que les migrations déclarées forment bien une chaîne
   continue de la plus ancienne version rattrapable jusqu'à `DATABASE_VERSION`.
   La version du schéma est une constante à part (`DATABASE_VERSION`) et non un
   nombre écrit dans l'annotation : c'est ce qui la rend lisible par le test.
   `exportSchema = true` fait écrire par Room le schéma de chaque version dans
   `app/schemas/` (chemin fixé par `room.schemaLocation` dans `app/build.gradle.kts`,
   sans quoi la compilation échoue). Ces fichiers **sont à verser au dépôt** : ils
   apparaissent après la première compilation et sont ce dont aurait besoin un futur
   test de migration réel, qui ouvre une base d'ancienne version et y applique les
   migrations.
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
   **L'analyseur SAX est durci** (`newSaxParser`) : un fichier importé vient de
   l'extérieur et ne doit pas pouvoir déclarer d'entités XML. Sans cela, quelques
   kilo-octets d'entités imbriquées suffisent à saturer la mémoire. Les drapeaux sont
   posés au mieux — une implémentation qui n'en connaîtrait pas un ne doit pas
   empêcher tout import.
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
- 113 tests unitaires : `Iso8601Test` (16), `UpdateManifestTest` (11),
  `BearingTest` (10),
  `SolarTimesTest` (9), `KmlColorTest` (8), `TrackSegmentsTest` (7),
  `KmlStyleTableTest` (7), `AltitudeSmootherTest` (7), `KmlExportTest` (7),
  `TunnelDetectorTest` (6), `ElevationAccumulatorTest` (6), `DarkTilesColorFilterTest`
  (6), `MergeTracksTest` (5), `MigrationChainTest` (3), `RemoveStationaryPointsTest`
  (2), plus trois tests d'échafaudage hérités (`ExampleUnitTest`,
  `ExampleRobolectricTest`, `GreetingScreenshotTest` avec Roborazzi).
  Ce décompte s'était mis à mentir : il annonçait 72 tests pour 11 suites alors que le
  dépôt en portait 80 pour 15, `KmlExportTest` n'y ayant jamais été ajouté. À tenir à
  jour en même temps que les tests eux-mêmes.
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

### Audit du 0.11.2

Une relecture complète du code a produit une liste de trente-deux points, dont
vingt-deux ont été traités dans cette version (voir le journal des nouveautés et
« Poids mort »). Les dix restants ont été écartés par l'auteur, sciemment :

- **La vue satellite passe par les serveurs de tuiles de Google** (`mt0-3.google.com`,
  `MapViewContainer`). La préférence porte encore le nom `usgs_sat`, hérité d'une
  imagerie publique qui n'est plus celle utilisée. Deux conséquences : c'est un usage
  hors conditions d'utilisation, coupable d'être coupé sans préavis, et la zone
  consultée part chez Google — ce que la promesse « rien ne quitte l'appareil » ne
  laisse pas attendre.
- Le cache de tuiles osmdroid est dans `externalCacheDir` : lisible par une autre
  application sur Android 9 et antérieur.
- Le filtre anti-dérive `dist > 1.0` écarte les intervalles de moins d'un mètre : à
  1 Hz, la marche lente (moins de 3,6 km/h) est structurellement sous-comptée.
- `FLAG_KEEP_SCREEN_ON` est posé sans condition dans `MainActivity` : l'écran ne
  s'éteint jamais tant que l'application est ouverte, même hors enregistrement.
- L'export vers Téléchargements est inopérant sur Android 9 et antérieur :
  `MediaStoreExporter` emprunte alors le stockage public sans que
  `WRITE_EXTERNAL_STORAGE` soit déclarée. Échec silencieux.
- Le tracé en diagonale (voir ci-dessous) reste non corrigé, à la demande de l'auteur.

### Le mode focus zoomait tout seul : corrigé en 0.12.0

Rapporté ainsi : « quand je mets le mode focus parfois ça zoom ou dézoom tout seul ».

**La cause** : le `LaunchedEffect(recenterTrigger, isAutoFollowActive)` de
`MapViewContainer` qui recentre la caméra et réinitialise le zoom à
`pref_default_zoom` avait pour garde `recenterTrigger > 0 || isAutoFollowActive`.
`recenterTrigger` ne fait qu'augmenter (jamais remis à zéro) : dès le premier appui
sur le bouton de recentrage dans une session, `recenterTrigger > 0` reste vrai pour
toujours. Or `LaunchedEffect` réexécute tout son corps à **chaque changement** de
l'une ou l'autre de ses clés — y compris quand `isAutoFollowActive` passe de `true`
à `false`. Un simple toucher de l'écran pour désengager le mode focus (l'invariant
existant : tout toucher appelle `onAutoFollowChanged(false)`) déclenchait donc, lui
aussi, ce recentrage-rezoom — juste après, voire pendant, le geste manuel de
l'utilisateur. La carte semblait alors annuler son propre zoom ou son propre
recentrage tout seule.

**Le correctif** : la garde ne porte plus que sur la valeur actuelle de
`isAutoFollowActive`. `recenterTrigger` reste dans les clés (pour forcer une
nouvelle exécution quand on retape sur le bouton alors que le mode focus est déjà
actif, cas où sa valeur seule ne changerait pas), mais ne fait plus partie de la
condition d'exécution. Le recentrage-rezoom ne se déclenche donc plus que lorsque le
mode focus est effectivement actif — jamais au moment où il vient de s'éteindre.

**Ce correctif n'a pas suffi**, l'essai sur le terrain l'a montré : la vraie cause
était ailleurs, et celle-ci n'en était qu'une aggravation. Voir la section suivante.

### La cause réelle du zoom intempestif : le contrôleur multi-touch, recréé sans cesse

Symptôme inchangé après le correctif ci-dessus : « ça zoom ou dézoom tout seul »,
y compris **après** avoir lâché l'écran, et seulement en mode focus.

`MapViewContainer` appelait `map.setMultiTouchControls(isInteractivityEnabled)` dans
le bloc `update` de l'`AndroidView`, donc à **chaque recomposition**. Or, dans
osmdroid, ce n'est pas un réglage idempotent :

```java
public void setMultiTouchControls(final boolean on) {
    mMultiTouchController = on ? new MultiTouchController<Object>(this, false) : null;
}
```

Chaque appel **jette le contrôleur en cours et en fabrique un neuf**. Pendant un
enregistrement en mode focus, chaque position GPS change `points` et
`currentUserLocation`, donc recompose : le contrôleur était remplacé une à deux fois
par seconde, forcément au milieu d'un pincement, qui dure plus longtemps que ça.

Pourquoi c'est le zoom qui trinque : osmdroid pilote le **vrai** niveau de zoom
pendant un pincement, relativement au zoom relevé à l'ouverture du geste —
`setMultiTouchScale(échelle)` fait `setZoomLevel(log2(échelle) + mStartAnimationZoom)`.
Et il ne remet le point d'ancrage à null que dans le `selectObject` de **fin** de
geste. Un contrôleur remplacé en cours de route ne reçoit jamais cette fin :
`mMultiTouchScaleCurrentPoint` reste renseigné pour toujours, et `getProjection()`
continue d'y recaler chaque projection (`adjustOffsets`) à chaque image dessinée.
D'où un déréglage qui **survit au geste** et se voit tout seul, sans que l'on touche
l'écran.

Le mode focus aggravait encore : `getDraggableObjectAtPoint` renvoie `null` tant que
`isAnimating()` est vrai, et `animateTo` est relancé à chaque position GPS pour une
animation d'une seconde — les pincements y étaient donc souvent refusés puis
raccrochés à contretemps, sur une référence de zoom périmée.

Le correctif tient en une garde : n'appeler `setMultiTouchControls` que lorsque la
valeur change réellement (`MapState.appliedMultiTouch`, volontairement nullable pour
distinguer « jamais appelé » de « appelé avec false »). C'est la discipline que le
fichier appliquait déjà juste au-dessus à la source de tuiles et à `mapOrientation` ;
elle avait seulement été oubliée ici.

**À retenir pour tout le bloc `update`** : il s'exécute à chaque recomposition, donc
plusieurs fois par seconde pendant un enregistrement. Tout ce qu'on y appelle doit
être soit idempotent et gratuit, soit gardé par une comparaison. Un setter qui
*alloue* quelque chose n'a rien à y faire sans garde.

Ce correctif est réel et gardé — mais **ce n'était toujours pas le défaut rapporté**.
Voir la section suivante.

### Le zoom sautait à l'appui sur « localiser » : la vraie cause, enfin

Précision décisive de l'auteur, après deux diagnostics à côté : « quand j'appuie sur
le bouton localiser pour passer en focus, ça zoome tout seul ou dézoome — **au moment
où je clique** ». Pas pendant un pincement, pas pendant le trajet : à l'appui.

Le recentrage faisait simplement, en plus de centrer la carte :

```kotlin
val zoomLevelToSet = prefs.getFloat("pref_default_zoom", 16.5f).toDouble()
mapView.controller.setZoom(zoomLevelToSet)
```

Deux erreurs superposées :

- **`pref_default_zoom` n'est pas un réglage**, malgré son nom. Aucun écran ne l'écrit
  (voir « En attente d'arbitrage » : la préférence impériale a le même travers) ; il
  est alimenté par `TrackViewModel.lastMapZoom`, donc par le dernier zoom mémorisé de
  la carte. Or `persistMapStateThrottled` borne cette mémorisation à une fois par
  seconde **sans rattrapage de la dernière valeur** : le zoom final d'un geste n'est
  quasiment jamais celui qui finit en préférence, c'est un zoom relevé au milieu du
  geste précédent. Le bouton y ramenait la carte, d'où un saut en avant ou en arrière
  sans rapport avec quoi que ce soit de visible.
- **Même exacte, cette valeur n'avait pas à s'imposer.** « Localiser » veut dire
  « centre-toi sur moi », pas « change mon échelle ». Recentrer et rezoomer sont deux
  actions distinctes ; une seule était demandée.

Le zoom courant est désormais conservé. Seule exception gardée : si la carte est
dézoomée au point que les tracés ne sont plus dessinés (`ZOOM_THRESHOLD`), on
rapproche — sans quoi « localiser » laisserait l'utilisateur centré sur lui-même mais
à l'échelle d'un pays.

**Leçon de méthode, celle qui vaut le plus ici.** Trois versions ont visé à côté sur
ce seul défaut, et les deux premières ont été poussées en affirmant « c'est la vraie
cause ». Les deux correctifs sont justes en eux-mêmes et sont conservés — mais ils
répondaient à un symptôme que l'auteur n'avait jamais décrit. La formulation initiale
(« parfois ça zoom ou dézoom tout seul ») avait été lue comme « à un moment
imprévisible », alors qu'elle voulait dire « sans que je le demande ». **Demander
quand exactement le défaut se produit coûte une question ; le deviner a coûté trois
compilations et deux diagnostics faux.**

### Le mode 3D pivotait sans arrêt : corrigé en 0.11.6

Mesuré sur une vidéo d'un trajet réel : la carte basculait d'environ 100°, revenait,
et recommençait deux à trois fois par seconde — trente-quatre changements de sens en
quatorze secondes.

**La cause n'était pas le bruit du GPS**, contrairement à ce que cette section
supposait. `computeCurrentBearing` se rabattait sur les points du tracé affiché dès
que la position GPS n'avait pas changé depuis le passage précédent. Or l'écran était
consulté **avec l'enregistrement en pause** : `livePoints` ne bouge plus dans ce cas
et conserve indéfiniment le cap qu'on avait au moment de la pause. La carte alternait
donc entre le cap réel et ce cap fossilisé, à la cadence des recompositions.

Le repli sur le tracé ne vaut désormais que **faute de position connue**. Quand une
position existe mais n'a pas assez bougé, la fonction renvoie null et l'appelant garde
le dernier cap : mieux vaut conserver le précédent qu'en inventer un autre.

Le lissage recommandé ici a été ajouté dans la foulée (`smoothedBearing`,
`BEARING_SMOOTHING`) : l'orientation se rapproche de la cible sur **l'arc le plus
court**, ce qui supprime le tour complet au passage par le nord et remplace les
à-coups par une rotation continue. `BearingTest` verrouille les deux points.

Leçon à garder : ce défaut a été rendu visible par le passage à `sample` en 0.11.5,
qui fait réévaluer l'affichage deux fois par seconde au lieu d'une fois par position
GPS. Le piège était là depuis toujours ; il ne se déclenchait simplement pas assez
souvent pour se voir.

### Chargement des tracés pendant le suivi automatique : la cause, trouvée en 0.11.5

**Symptôme exact, tel que rapporté** : en voiture, mode focus actif, les points déjà
à l'écran au moment de l'activation restent corrects, mais tout ce que l'on découvre
en avançant s'affiche en **grandes lignes droites** — la silhouette sous-échantillonnée
au lieu du détail. Toucher la carte, zoomer ou dézoomer remet tout d'aplomb.

**La cause.** `onScroll` republie la zone visible, et le ViewModel la filtrait avec
`debounce`, qui n'émet qu'après un silence. Or osmdroid notifie `onScroll` **à chaque
image de ses animations** : `MapController.onAnimationUpdate` appelle `setMapCenter`
une soixantaine de fois par seconde, ce qui descend dans `MapView.scrollTo`, qui
prévient les écouteurs — le commentaire d'osmdroid le dit lui-même. En suivi
automatique, `animateTo` est relancé à chaque position GPS pour une animation d'une
seconde : les animations s'enchaînent sans interruption, le silence n'arrive jamais,
et `debounce` n'émet plus rien du tout. Aucun rechargement n'était déclenché tant que
le mode restait actif. Un geste coupe le suivi (`setOnTouchListener` →
`onAutoFollowChanged(false)`), les animations cessent, le silence passe, tout se
recharge d'un coup.

`sample` remplace `debounce` : il émet la zone la plus récente à intervalle régulier
tant qu'il en arrive, et laisse passer la dernière une fois le flux tari. Un flux
continu ne peut plus l'affamer. **Ne jamais remettre `debounce` sur ce flux** : la
source peut être continue par construction.

Corollaire traité en même temps : `onScroll` faisait aussi mémoriser la position de
carte à chaque image, soit quatre écritures de préférences par image et plus de deux
cents accès disque par seconde. Borné à une fois par seconde (`MAP_STATE_PERSIST_MS`).

**Trois versions ont visé à côté avant celle-ci**, et il vaut la peine de savoir
pourquoi pour ne pas y revenir :

- **0.11** supposait que la caméra prenait du retard sur la position et que la zone
  publiée n'était donc pas la bonne (`reportAutoFollowViewport`). Juste en soi, gardé,
  sans effet sur ce défaut.
- **0.11.3** a remplacé `flatMapLatest` par `conflate` (une lecture commencée n'est
  plus annulée) et espacé les republications du sondage. Deux améliorations réelles,
  mais qui portaient sur la boucle de sondage alors que le déluge venait de
  `onScroll`. Essayé sur le terrain : aucun changement.
- **0.11.4** a mis en cache les points des traces sous `fullLoadLimit`
  (`fullPointsCache`), qui étaient relues intégralement à chaque republication. Gain
  réel de performance, mais ne pouvait pas débloquer un rechargement qui n'était
  jamais déclenché.

Ces trois versions restent utiles, aucune n'est à défaire. La leçon est ailleurs :
le défaut n'était pas dans le circuit de chargement, mais dans le filtre placé à son
entrée. Ce qui suit décrit les deux premières tentatives, conservées pour mémoire.

### Ce que les 0.11.3 et 0.11.4 avaient corrigé au passage

Symptôme : en voiture, mode focus actif, les tracés affichés en superposition cessent
de se charger au fil du déplacement, jusqu'à ce qu'un glissement ou un zoom manuel
remette tout à jour d'un coup.

**La 0.11 s'était trompée de cause.** Elle avait supposé que la caméra prenait du
retard sur la position (`animateTo` rejeté en cours d'animation) et que la zone
republiée n'était donc pas la bonne ; `reportAutoFollowViewport` reconstruit depuis
la position GPS plutôt que depuis `map.boundingBox`. C'est juste en soi, et c'est
gardé — mais ce n'était pas ce qui cassait l'affichage.

Les deux vraies causes, trouvées en 0.11.3 :

1. **`flatMapLatest` annulait la lecture en cours.** `selectedImportedPoints` et
   `selectedTrackPoints` relancent une lecture à chaque nouvelle zone, et
   `flatMapLatest` annule la précédente. Hors suivi, les zones n'arrivent qu'aux
   gestes de l'utilisateur : sans conséquence. En mode focus, elles arrivaient toutes
   les secondes (`AUTO_FOLLOW_VIEWPORT_POLL_MS`), si bien que toute lecture dépassant
   la seconde était annulée avant d'avoir rien produit, repartait de zéro, et se
   faisait annuler de nouveau — indéfiniment. D'où le symptôme exact : un geste manuel
   coupe le suivi (`setOnTouchListener` → `onAutoFollowChanged(false)`), donc les
   republications, donc la lecture aboutit enfin et tout apparaît d'un coup.
   Corrigé par `conflate()` : une lecture commencée va toujours à son terme, les zones
   arrivées entre-temps s'écrasent, et la plus récente est traitée ensuite.
2. **La zone était republiée à chaque seconde pour rien.** Le sondage envoyait la
   position à chaque tour, alors que la zone publiée déborde l'écran de
   `VIEWPORT_MARGIN` : tant qu'on s'est déplacé de moins que cette marge, l'écran est
   déjà couvert. Pire, `getDisplayPoints` relit intégralement les traces de moins de
   `fullLoadLimit` points — une relecture qui ne dépend même pas de la zone. Le
   sondage ne republie désormais qu'au-delà de `AUTO_FOLLOW_RELOAD_FRACTION` de la
   largeur d'écran parcourue, ou sur changement de zoom (à conserver : sans ce
   second cas, un zoom avant resterait sur la silhouette grossière).

Ces deux points étaient l'un des « leviers » laissés en arbitrage plus bas ; ils n'y
figurent donc plus.

**Troisième cause, trouvée en 0.11.4 après un essai sur le terrain** qui rapportait
« du mieux, mais ça bugue encore ». La branche `pointCount <= fullLoadLimit` de
`getDisplayPoints` était la seule à ne rien mettre en cache : une trace de moins de
60 000 points était relue intégralement en base à chaque republication de la zone,
alors que son résultat ne dépend pas du cadrage. En suivi automatique, cela revenait
à redemander des dizaines de milliers de lignes par trace affichée, et à reconstruire
autant d'objets, pour un résultat identique au précédent. `fullPointsCache` corrige
cela ; renvoyer la même instance rend en prime immédiate la comparaison de
`MapViewContainer`, qui repassait sur chaque point pour conclure que rien n'avait
changé. Toute écriture sur les points d'une trace passe déjà par
`invalidatePointCaches`, le cache ne peut donc pas rester périmé.

Ce qui reste possible si le défaut persiste : les traces **au-dessus** de
`fullLoadLimit` refont à chaque republication un `countPointsInBounds` puis un
`getDetailPointsInBounds`, ces deux-là dépendant réellement de la zone ; et
`MapViewContainer` invalide ses polylignes en bloc, si bien qu'une seule trace dense
qui bouge fait reconstruire celles de toutes les autres sur le thread principal. Un
cache de polylignes par trace serait la marche suivante.

### En attente d'arbitrage

- `VIEWPORT_MARGIN` est passé de 0,35 à 0,60 à la demande de l'auteur. À évaluer.
  Surveiller l'effet indirect documenté au-dessus de la constante : la zone élargie
  sert aussi à `coversMostOfTrack`, donc l'affichage se rabat plus tôt sur la seule
  silhouette. Si le tracé paraît plus grossier, mesurer cette couverture sur la zone
  réellement visible plutôt que sur la zone élargie.
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
- Deuxième passe, `0.11.2`, après un audit de tout le code source :
  - **Le partage d'un parcours en entier.** `shareGPX` / `shareKML` n'avaient plus
    aucun appelant — le bouton avait disparu de l'interface, pas le code : avec eux
    sont partis `shareTrack`, `shareCachedFile`, `safeFileName`, le dossier
    `cacheDir/exports` et son entrée `<cache-path>` dans `file_paths.xml`. Ce dernier
    point n'est pas cosmétique : tout chemin déclaré là est exposable via le
    `FileProvider`, et un chemin sans usage est une surface offerte pour rien.
  - Trois composables jamais appelés de `TrackingTab` : `SportRadarBackground`,
    `StatColumn`, `GpsStatusSquare` (environ 270 lignes).
  - `UpdatePrompt.findUpdate`, écrite pour une recherche manuelle qui n'a jamais existé.
  - **L'allure** : `LiveStats.paceMinPerKm` était calculée en trois endroits et
    `FormatUtils.formatPace` n'était appelée nulle part. Aucun écran ne l'affichait.
  - `TrackRepository.getPointsForTrack` et `setLivePoints`, sans appelant.
  - 55 imports inutilisés, surtout dans `TrackingTab` et `HistoryTab`.
  - `OsmConfig.init` était appelé deux fois, dans `TrackApplication` puis dans
    `MainActivity` ; le second appel ne faisait rien (garde `isInitialized`).
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
- **`sha256` : l'empreinte de l'APK, vérifiée pendant l'écriture.** Le contrôle de
  taille ne suffisait pas : `contentLength` vaut -1 quand le serveur répond en
  découpage par blocs, et un téléchargement interrompu passait alors inaperçu. Le
  champ est **facultatif** — les publications antérieures à son introduction n'en
  portent pas et doivent rester installables — et une empreinte présente mais
  malformée est ignorée plutôt que retenue, sinon la comparaison échouerait toujours
  et la mise à jour deviendrait ininstallable. `release.yml` la calcule au moment de
  la publication.
- L'installation silencieuse est impossible pour une application ordinaire : le
  système affiche toujours son écran de confirmation.

Ignorer le bandeau (« Plus tard ») ne fait pas disparaître la mise à jour : un badge
rouge sur l'onglet « Paramètres » et une carte en tête de cet écran restent, avec un
bouton qui rouvre le même bandeau. `MainScreen` conserve la mise à jour détectée
(`onUpdateAvailable`, appelé une fois par `UpdatePrompt`) et un compteur
`updateReopenTrigger` incrémenté par ce bouton ; `UpdatePrompt` le lit pour rouvrir
son dialogue sur la mise à jour déjà connue, sans reconsulter le réseau ni redémarrer
l'application.

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
