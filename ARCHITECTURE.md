# Architecture proposée — parsers vidéo français pour Kototoro

## Objectif

Le dépôt livrera un plugin Kototoro de type **VIDÉO**, concentré sur des catalogues ayant une API publique et dont les contenus sont explicitement partagés sur des plateformes publiques. Il ne contient ni lecteur intégré, ni identifiants utilisateur, ni logique de contournement de DRM, de géoblocage, de CAPTCHA ou de paywall.

## Socle technique et licence

Le mécanisme de plugin, le contrat `ContentParser`, l’annotation de découverte et le script de construction seront dérivés de `skepsun/kototoro-parsers`, distribué sous **GPL-3.0-or-later**. Le dépôt de sortie sera donc aussi sous GPL-3.0-or-later, conservera une copie de cette licence, indiquera clairement son origine technique et publiera les sources correspondant à chaque artefact JAR.

| Élément | Décision |
| --- | --- |
| Nom de dépôt prévu | `kototoro-french-video-parsers` |
| Langage | Kotlin/JVM 8, avec Gradle |
| Type Kototoro | `ContentType.VIDEO` |
| Distribution | JAR compilé, dexé puis empaqueté en `plugin.jar` |
| Index | `index.min.json` sur une branche de publication |
| Licence | GPL-3.0-or-later |

## Sources initiales

| Parser | Source | Garanties et limites |
| --- | --- | --- |
| `FRENCH_PEERTUBE` | API REST d’une instance PeerTube publique choisie par l’utilisateur | Le domaine est configurable. Le parser n’interroge qu’une instance explicitement fournie et affiche ses pages d’origine. La francophonie dépend de l’instance sélectionnée et de ses métadonnées. |
| `WIKIMEDIA_COMMONS_FR_VIDEO` | API MediaWiki de Wikimedia Commons, catégorie de vidéos en français | Les résultats utilisent les métadonnées publiques, l’URL de fichier et le lien de description. Les licences restent affichées, et le catalogue peut contenir WebM/Ogg historiques selon les fichiers disponibles. |

Les dépôts CloudStream, Nuvio et AniZen cités par l’utilisateur servent exclusivement à comparer la séparation catalogue/détails/épisodes/liens. Leur code ne sera pas importé lorsque la licence est absente ou incompatible.

## Modèle de données vidéo

Chaque élément de catalogue devient un `Content`. Sa fiche détail conserve la description, la vignette, les tags et les liens de provenance. Un épisode ou une vidéo devient un `ContentChapter`; la résolution retourne un `ContentPage` dont l’URL est fournie par l’API publique de la source. Les URLs de lecture sont utilisées telles que renvoyées par la source et ne sont jamais déconstruites ni déchiffrées.

## Compatibilité et validation

Les tests couvriront les réponses JSON enregistrées comme fixtures, sans appeler une plateforme tierce à chaque exécution. La chaîne de validation doit vérifier : compilation Kotlin, tests, création du JAR, conversion D8 et présence du `classes.dex` dans `plugin.jar`. La publication GitHub ne sera faite qu’après ces vérifications.
