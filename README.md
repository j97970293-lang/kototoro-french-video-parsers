# Parsers vidéo français pour Kototoro

Ce dépôt publie un plugin **Kototoro** de type vidéo. Sa première version ne contient que deux sources dont les interfaces sont publiques : une instance PeerTube francophone configurable et les fichiers de la catégorie **Videos in French** de Wikimedia Commons. Le plugin ne fournit pas de lecteur, ne contourne ni DRM, ni contrôle d’accès, ni géoblocage, et n’essaie pas de résoudre des lecteurs tiers.

| Source | Fonctionnement | Langue et limites |
| --- | --- | --- |
| **PeerTube francophone** | Interroge l’API REST publique de l’instance configurée et restitue son URL HTTPS de fichier ou de playlist. | L’instance par défaut est Framatube. Les éléments sont filtrés sur le champ API `language.id = fr`; l’utilisateur peut choisir une autre instance. |
| **Wikimedia Commons — vidéos françaises** | Recherche les fichiers de la catégorie `Videos in French`, puis utilise les métadonnées et URL publiées par MediaWiki. | Les fichiers conservent leur page d’attribution, leur licence et leur format d’origine, notamment WebM ou Ogg. |

## Installation

Après une publication, ajoutez dans Kototoro l’URL de l’index de la branche `repo` :

```text
https://raw.githubusercontent.com/j97970293-lang/kototoro-french-video-parsers/repo/index.min.json
```

L’URL sera disponible dès que la première version GitHub Actions aura été publiée. Dans les réglages de **PeerTube francophone**, vous pouvez modifier le domaine. Saisissez seulement le nom de domaine d’une instance qui expose une API PeerTube publique, par exemple `framatube.org`, sans chemin ni paramètres.

## Construire localement

Le projet requiert un JDK 17 pour compiler, mais cible le bytecode Java 8 afin de rester compatible avec le pipeline D8 de Kototoro.

```bash
./gradlew test --no-daemon
./gradlew jar --no-daemon
./build_plugin.sh
```

Le workflow GitHub Actions exécute la même construction, convertit le JAR en `classes.dex`, puis publie `plugin.jar` et `index.min.json` sur la branche `repo`.

## Licence et contributions

Le socle technique provient de `skepsun/kototoro-parsers` et ce dépôt est publié sous **GPL-3.0-or-later**. Consultez [`NOTICE.md`](NOTICE.md) pour l’attribution. Une contribution doit cibler une interface publique documentée, préserver la page de provenance et la licence du contenu, et ne doit inclure aucun mécanisme de contournement de restrictions d’accès ou de chiffrement.
