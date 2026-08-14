# Mes parcours

Traqueur GPS Android privé pour activités de plein air : enregistrement de parcours,
carte OSM, statistiques en direct, import/export GPX & KML, fusion de parcours.

Application personnelle (dédiée « à mon père Thierry »), sans compte ni serveur :
toutes les données restent sur l'appareil.

- Nom affiché : « Mes parcours » (`app_name` dans `res/values/strings.xml`, garde-fou
  dans `ExampleRobolectricTest`). Anciennement « Sillage ».
- `applicationId` : `com.toche.mesparcours` — `namespace` Kotlin : `com.example`
- Version courante : `0.9.8-thierry` (`versionCode` 8). Elle n'est écrite qu'une fois,
  dans `app/build.gradle.kts` ; l'écran « À propos » la lit via `BuildConfig.VERSION_NAME`.
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
  préférences de style

`TrackRepository` est la **source de vérité** de l'état en direct (`isTracking`,
`livePoints`, `liveStats`, `gpsStatus`…). Le service écrit, l'interface lit.

## Invariants à ne pas casser

Ces points ont chacun corrigé un bug réel ; les commentaires du code expliquent
pourquoi. À relire avant d'y toucher.

1. **Singletons à double vérification** (`AppDatabase`, `TrackRepository`). Deux
   instances = deux jeux de `StateFlow` et de suivis d'invalidation Room : le
   service alimente l'une pendant que l'écran observe l'autre, et l'interface cesse
   silencieusement de se mettre à jour.
2. **Migration Room 4→5 obligatoire.** La base est construite avec
   `fallbackToDestructiveMigration()` : retirer `MIGRATION_4_5` effacerait tous les
   parcours des utilisateurs existants. Toute nouvelle version de schéma doit venir
   avec sa migration explicite. **Vital** depuis le passage aux mises à jour sur
   place : plus rien ne rattrape un oubli, voir « Identité de l'application ».
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
- 55 tests unitaires : `SolarTimesTest` (9), `AltitudeSmootherTest` (7),
  `TrackSegmentsTest` (7), `UpdateManifestTest` (7), `TunnelDetectorTest` (6),
  `ElevationAccumulatorTest` (6), `DarkTilesColorFilterTest` (6), `MergeTracksTest`
  (4), plus trois tests d'échafaudage hérités (`ExampleUnitTest`,
  `ExampleRobolectricTest`, `GreetingScreenshotTest` avec Roborazzi).
- **Le projet n'est pas sous contrôle de version** — aucun dépôt git.

## Poids mort identifié

Non corrigé pour l'instant, à traiter si l'occasion se présente :

- Dépendances déclarées et jamais utilisées dans `app/build.gradle.kts` : `retrofit`,
  `converter-moshi`, `moshi-kotlin` (+ son processeur KSP), `okhttp`,
  `logging-interceptor`, `work-runtime-ktx`, `credentials`,
  `credentials-play-services-auth`, `googleid` (vestiges d'une sauvegarde Drive retirée).
- Code mort : `Exporter.exportToGPX` / `exportToKML` (variantes en mémoire),
  `MediaStoreExporter.saveToLocalDownloads` (variante non-flux),
  `TrackRepository.insertPoints`, `getPointsForTrackFlow`,
  `getSelectedImportedPointsFlow`, `AutoBackupPreferences.isDestLocal` / `setDestLocal`,
  colonne `Track.isMerged` (toujours écrite à `false`, jamais lue).
- Asset jamais chargé : `app/src/main/assets/france_boundary.geojson`.
- `MainScreen` contient une **seconde** simulation GPS, celle-ci *non* gardée par
  `BuildConfig.DEBUG` : sur un appareil réel sans aucun fournisseur de position
  actif, un point bleu fictif tourne dans Paris. Rien n'est écrit en base, mais
  l'incohérence avec l'invariant 8 mérite d'être levée.

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

Tout passe par un **tag annoté**. `.github/workflows/release.yml` s'occupe du reste :
tests, compilation signée, `update.json`, publication GitHub.

```bash
git tag -a v0.9.9-thierry -m "Nouveautés" -m "Première nouveauté" -m "Deuxième"
git push origin v0.9.9-thierry
```

Le corps du tag devient les notes affichées dans la boîte de dialogue de mise à jour,
une puce par ligne. Le workflow **refuse de publier** si le tag ne correspond pas au
`versionName` de `app/build.gradle.kts` : sans ce garde-fou, on publierait une version
que personne ne pourrait installer par-dessus la précédente.

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

1. **`applicationId = "com.toche.mesparcours"` est définitif.** Le changer ferait
   échouer toutes les mises à jour : Android installerait une seconde application à
   côté, et la base de la première deviendrait inaccessible. (Il a successivement valu
   `com.aistudio.mytracks.eaddfb`, hérité de l'échafaudage AI Studio, puis
   `com.toche.sillage` — c'est terminé.)
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
