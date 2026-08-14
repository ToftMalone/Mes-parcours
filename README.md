# Mes parcours

Traqueur GPS Android privé pour les activités de plein air.

Mes parcours enregistre vos parcours, les affiche sur une carte OpenStreetMap, calcule
vos statistiques en temps réel et vous laisse importer, fusionner et exporter vos
traces au format GPX ou KML. Aucun compte, aucun serveur, aucune télémétrie :
toutes les données restent sur l'appareil.

## Fonctionnalités

- **Enregistrement** en arrière-plan via un service de premier plan, avec reprise
  automatique après un redémarrage ou un arrêt inopiné de l'application.
- **Statistiques en direct** : distance, durée, vitesse instantanée, moyenne et
  maximale, allure, dénivelé positif et négatif.
- **Carte** OpenStreetMap ou vue satellite, orientation fixe au nord ou dans le
  sens du déplacement, épaisseur et couleurs des tracés réglables.
- **Import / export** GPX et KML sans aucune simplification : chaque point du
  fichier est conservé, et l'export restitue l'intégralité de la trace.
- **Fusion** de plusieurs parcours en un seul, par ordre chronologique.
- **Sauvegarde automatique** de chaque parcours terminé dans `Download/Mes parcours/`.
- **Panneau de limitation de vitesse** déduit des données OpenStreetMap.
- Interface Material You, qui s'accorde aux couleurs du système (Android 12+).

## Prérequis

- [Android Studio](https://developer.android.com/studio) récent
- JDK 11 ou supérieur
- Un SDK Android avec l'API 36

## Compiler et lancer

1. Ouvrir le projet dans Android Studio (**Open**, puis choisir ce dossier).
2. Créer un fichier `local.properties` à la racine indiquant l'emplacement du SDK :

   ```properties
   sdk.dir=C:\\Users\\<vous>\\AppData\\Local\\Android\\Sdk
   ```

3. Lancer l'application sur un appareil ou un émulateur.

En ligne de commande :

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

> **Signature de debug.** `debug.keystore` n'est pas versionné. S'il est absent,
> le build retombe automatiquement sur la clé de debug par défaut d'Android : il
> n'y a rien à modifier.

## Compiler une version de publication

La variante `release` attend une clé de signature fournie par l'environnement :

| Variable         | Rôle                                                     |
| ---------------- | -------------------------------------------------------- |
| `KEYSTORE_PATH`  | Chemin du trousseau (défaut : `my-upload-key.jks`)        |
| `STORE_PASSWORD` | Mot de passe du trousseau                                |
| `KEY_PASSWORD`   | Mot de passe de la clé (alias `upload`)                  |

```bash
./gradlew assembleRelease
```

## Autorisations demandées

| Autorisation                  | Pourquoi                                            |
| ----------------------------- | --------------------------------------------------- |
| Localisation précise          | Enregistrer le tracé                                |
| Localisation en arrière-plan  | Continuer l'enregistrement écran éteint             |
| Service de premier plan       | Maintenir l'enregistrement actif                    |
| Notifications                 | Notification d'enregistrement et perte de signal    |
| Internet                      | Tuiles de carte et limitations de vitesse (OSM)     |
| Démarrage terminé             | Reprendre un enregistrement après un redémarrage    |

## Architecture

```
MainActivity ──> MainScreen ──> Enregistrer · Historique · Outils · Paramètres
                     └────────> DetailView

TrackViewModel ──> TrackRepository (singleton) ──> Room (Track, TrackPoint)
                        ▲
                        ├── TrackingService  (acquisition GPS)
                        └── BootReceiver     (reprise au démarrage)
```

`TrackRepository` est la source de vérité de l'état en direct : le service écrit,
l'interface lit. Voir [CLAUDE.md](CLAUDE.md) pour la carte détaillée du code et les
invariants à respecter.

## Licence

Code libre. Développé par ToftMalone avec Claude.
