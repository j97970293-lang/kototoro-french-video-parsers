# Dépôt de parsers vidéo français pour Kototoro — TODO

- [x] Vérifier que Kototoro charge des plugins JAR dexés et génère l’index `index.min.json` attendu.
- [x] Vérifier le contrat `ContentParser`, l’annotation `ContentSourceParser` et un exemple réel de `ContentType.VIDEO`.
- [x] Auditer les dépôts de référence fournis et exclure toute copie de code sans licence explicite.
- [x] Retenir des sources à API publique et contenu libre : PeerTube configurable et Wikimedia Commons, avec attribution visible.
- [x] Créer le dépôt GPL-3.0 basé sur l’infrastructure de compilation Kototoro-parsers et conserver les avis d’attribution.
- [x] Implémenter un parser PeerTube francophone configurable, sans agrégation cachée ni contournement de restrictions.
- [x] Implémenter un parser Wikimedia Commons qui limite les résultats à la catégorie de vidéos en français et affiche les métadonnées de licence.
- [x] Ajouter des tests unitaires pour le filtrage linguistique, les liens HTTPS de lecture et les pages d’attribution.
- [x] Compiler le JAR puis le convertir en plugin dexé afin de vérifier l’artefact Kototoro.
- [x] Ajouter la documentation d’installation, les limites de contenu et une politique de contribution.
- [x] Créer et publier publiquement le dépôt GitHub avec une version initiale.
- [x] Diagnostiquer pourquoi l’index public n’est pas découvert dans Kototoro et corriger le format ou l’URL nécessaires : le `pkg` du premier index doublonnait celui du dépôt officiel et a été rendu unique.
- [x] Auditer les dépôts CloudStream, Nuvio et anime indiqués afin d’identifier uniquement des sources de streaming publiquement réutilisables et compatibles avec Kototoro : aucune licence explicite n’a été trouvée dans leurs métadonnées GitHub.
