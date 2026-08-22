# Parsers vidéo français pour Kototoro

Ce dépôt publie un plugin **Kototoro** de type vidéo. Il contient quatre sources dont les interfaces sont publiques : une instance PeerTube francophone configurable, les fichiers de la catégorie **Videos in French** de Wikimedia Commons, ainsi que les catalogues French-Manga et French-Stream. Le plugin ne fournit pas de lecteur, ne contourne ni DRM, ni contrôle d’accès, ni géoblocage, et n’essaie pas de résoudre des lecteurs tiers.

| Source | Fonctionnement | Langue et limites |
| --- | --- | --- |
| **PeerTube francophone** | Interroge l’API REST publique de l’instance configurée et restitue son URL HTTPS de fichier ou de playlist. | L’instance par défaut est Framatube. Les éléments sont filtrés sur le champ API `language.id = fr`; l’utilisateur peut choisir une autre instance. |
| **Wikimedia Commons — vidéos françaises** | Recherche les fichiers de la catégorie `Videos in French`, puis utilise les métadonnées et URL publiées par MediaWiki. | Les fichiers conservent leur page d’attribution, leur licence et leur format d’origine, notamment WebM ou Ogg. |
| **French-Manga** | Recherche AJAX, fiches, saisons et épisodes; lit les URLs média directes renvoyées publiquement. | Films/séries en français; les hosters non résolus restent indisponibles. |
| **French-Stream** | Recherche HTML, fiches films/séries et épisodes; lit les URLs média directes renvoyées publiquement. | Films/séries en français; aucune résolution de CAPTCHA, DRM ou accès protégé. |

## Installation

Après une publication, ajoutez dans Kototoro l’URL de l’index de la branche `repo` :

```text
https://raw.githubusercontent.com/j97970293-lang/kototoro-french-video-parsers/repo/index.min.json
```

L’URL sera disponible dès que la première version GitHub Actions aura été publiée. Dans les réglages de **PeerTube francophone**, vous pouvez modifier le domaine. Saisissez seulement le nom de domaine d’une instance qui expose une API PeerTube publique, par exemple `framatube.org`, sans chemin ni paramètres.

### Sources incluses

Les deux nouveaux parsers sont identifiés dans Kototoro par `FRENCH_MANGA` et `FRENCH_STREAM`, avec `locale = fr` et `ContentType.VIDEO`. Après actualisation du dépôt, l’index public et le JAR sont reconstruits automatiquement par GitHub Actions.

### Dépannage : le dépôt ou l’extension ne s’affiche pas

La première publication utilisait par erreur le même identifiant de catalogue que le dépôt officiel Kototoro. Depuis la version `0.1.4`, le plugin utilise l’identifiant distinct `org.j97970293.kototoro.frenchvideoparsers` et peut donc coexister avec le dépôt officiel.

Si vous avez ajouté l’URL avant ce correctif, supprimez ce dépôt dans **Réglages → Sources → Extensions → Dépôts**, ajoutez à nouveau l’URL ci-dessus, validez la demande de confiance, puis actualisez la liste des extensions **JAR**. L’extension doit apparaître sous le nom **Kototoro — vidéos françaises**. GitHub peut conserver l’ancien index dans son cache pendant quelques minutes après une publication ; dans ce cas, recommencez l’actualisation après ce délai.

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
