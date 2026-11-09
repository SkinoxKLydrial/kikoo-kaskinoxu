# Kikoo Kaskinoxu

Lecteur de mangas pour Android (Kotlin + Jetpack Compose), inspiré de Mihon.

## Fonctions

- **Bibliothèque** avec compteur de chapitres non lus et recherche des nouveaux chapitres.
- **Explorer** : MangaDex (API officielle) et fichiers locaux (CBZ, ZIP, dossiers d'images).
- **Lecteur** : manga (droite → gauche), gauche → droite, webtoon vertical ; zoom (pincer, double-tap), zones de toucher, reprise à la dernière page, passage au chapitre suivant.
- **Téléchargements** pour lire hors ligne.
- **Historique** de lecture.
- **Mise à jour automatique** : au lancement, l'appli regarde la dernière version publiée sur GitHub et propose de l'installer.

## Comment ça se met à jour

À chaque `git push` sur `main`, GitHub Actions compile l'APK, le signe avec ta clé et le publie dans **Releases** (`v1.0.<numéro>`). L'appli installée voit la nouvelle version et propose « Installer ».
Android demande une confirmation à chaque installation (impossible de faire autrement pour une appli hors Play Store), et la première fois il faut autoriser Kikoo Kaskinoxu à installer des applis.

## Secrets GitHub nécessaires

Dans *Settings → Secrets and variables → Actions* du dépôt :

| Nom | Contenu |
| --- | --- |
| `KEYSTORE_BASE64` | le fichier `yomu-release.p12` encodé en base64 |
| `KEYSTORE_PASSWORD` | son mot de passe |

⚠️ Garde une copie de `yomu-release.p12` et de son mot de passe. Si tu les perds, les nouvelles versions ne pourront plus s'installer par-dessus l'ancienne (il faudra désinstaller l'appli, et tu perdras ta bibliothèque).

## Fichiers locaux

Choisis un dossier dans *Réglages → Dossier des mangas*. Structure attendue :

```
Mangas/
  One Piece/
    Chapitre 001.cbz
    Chapitre 002.cbz
  Autre série/
    Ch 1/  (images)
    Ch 2/  (images)
  Un one-shot.cbz
```

## Ajouter une source

Implémente l'interface `Source` (`app/src/main/java/app/yomu/source/Source.kt`) et ajoute-la dans `Sources.init`.
