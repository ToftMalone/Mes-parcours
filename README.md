# Mes parcours

Une application Android qui enregistre vos balades.

Vous appuyez sur « démarrer » avant de partir, sur « arrêter » en rentrant. Entre les
deux, l'application suit votre position et trace votre chemin sur une carte. C'est
tout ce qu'elle fait — mais elle le fait bien, et sans rien envoyer à personne.

---

## Ce qu'elle fait

**Elle enregistre votre trajet.** Marche, vélo, voiture, peu importe. L'écran peut
s'éteindre, vous pouvez changer d'application : l'enregistrement continue. Si votre
téléphone redémarre en route, il reprend tout seul là où il s'était arrêté.

**Elle vous montre où vous en êtes.** Pendant la balade : la distance parcourue, votre
vitesse, votre altitude. Après : la durée, les vitesses moyenne et maximale, et ce que
vous avez monté et descendu.

**Elle affiche vos parcours sur une carte.** Carte classique ou vue satellite. La carte
peut rester orientée vers le nord, ou pivoter dans le sens où vous avancez. Vous pouvez
afficher plusieurs parcours en même temps pour les comparer, et donner à chacun la
couleur que vous voulez.

**Elle ouvre et enregistre les fichiers GPX et KML.** Ce sont les formats que tout le
monde utilise — Google Earth, les montres de sport, les sites de randonnée. Vous pouvez
importer un parcours qu'on vous a envoyé, ou exporter les vôtres pour les partager.
Rien n'est simplifié au passage : tous les points sont conservés, même sur un fichier
énorme.

**Elle sait fusionner et découper.** Réunir plusieurs parcours en un seul, remis dans
l'ordre chronologique — pratique quand une longue sortie s'est retrouvée coupée en
morceaux. Ou l'inverse : séparer un parcours en plusieurs, à chaque tronçon ou à
chaque longue pause. C'est ce qu'il faut pour un fichier Google Earth qui réunit
des dizaines de voyages en un seul bloc.

**Elle sauvegarde vos sorties toute seule.** À la fin de chaque enregistrement, un
fichier est déposé dans votre dossier `Téléchargements`, prêt à être récupéré ou
envoyé où vous voulez.

**Elle passe en sombre quand il fait sombre.** Soit en suivant le réglage de votre
téléphone, soit en calculant les vraies heures de lever et de coucher du soleil là où
vous êtes. Elle sait même reconnaître un tunnel et s'assombrir le temps que vous y
passez.

**Elle corrige l'altitude.** Le GPS d'un téléphone se trompe d'une cinquantaine de
mètres, systématiquement. L'application rectifie, pour afficher l'altitude que vous
liriez sur une carte ou un panneau.

---

## Vos données restent sur votre téléphone

Pas de compte à créer. Pas de mot de passe. Pas de serveur.

Vos parcours sont enregistrés dans votre téléphone et **n'en sortent jamais**, sauf si
vous décidez vous-même d'exporter un fichier. Il n'y a ni statistiques d'usage, ni
publicité, ni suivi d'aucune sorte. L'application ne sait pas qui vous êtes et ne
cherche pas à le savoir.

Elle a besoin d'internet pour deux choses seulement : afficher le fond de carte, et
vérifier si une nouvelle version existe.

> **Une exception à connaître.** Si vous activez la vue satellite, les images viennent
> des serveurs de Google — donc les zones que vous regardez en satellite passent par
> chez eux. La carte classique, elle, vient d'OpenStreetMap. Si ça vous gêne, restez
> en carte classique.

---

## Installer l'application

Elle n'est pas sur le Play Store. Ça se fait en trois étapes :

1. Allez sur la [page des versions](https://github.com/ToftMalone/Mes-parcours/releases)
   et téléchargez le fichier `.apk` de la dernière version.
2. Ouvrez-le. Android va vous demander l'autorisation d'installer une application qui
   ne vient pas du Play Store — c'est normal, acceptez.
3. C'est installé.

Ensuite, l'application vous prévient toute seule quand une nouvelle version sort, et
vous propose de l'installer. Vos parcours sont conservés d'une version à l'autre.

---

## Les autorisations demandées

| Ce qu'elle demande | Pourquoi |
| --- | --- |
| Votre position | C'est le principe même de l'application |
| Position en arrière-plan | Continuer à enregistrer quand l'écran est éteint |
| Notifications | Vous montrer que l'enregistrement tourne, et vous prévenir si le GPS se perd |
| Internet | Afficher la carte, et chercher les mises à jour |
| Démarrage du téléphone | Reprendre un enregistrement interrompu par un redémarrage |
| Installer des applications | Installer les mises à jour que l'application vous propose |

---

## Pour ceux que le code intéresse

C'est une application Android écrite en Kotlin, avec Jetpack Compose pour l'interface
et OpenStreetMap pour les cartes. Elle se compile avec Android Studio, ou en ligne de
commande :

```bash
./gradlew assembleDebug        # construire l'application
./gradlew testDebugUnitTest    # lancer les tests
```

Il vous faut un SDK Android avec l'API 36, et un fichier `local.properties` à la racine
indiquant où il se trouve :

```properties
sdk.dir=/chemin/vers/Android/Sdk
```

[CLAUDE.md](CLAUDE.md) documente le fonctionnement interne en détail : l'organisation
du code, les choix techniques et les pièges à ne pas réintroduire.

---

## Licence

[GPL-3.0](LICENSE) — libre d'utilisation, de modification et de redistribution, à
condition que les versions modifiées restent libres elles aussi.

Développée par ToftMalone, avec Claude. Dédiée à mon père, Thierry.
