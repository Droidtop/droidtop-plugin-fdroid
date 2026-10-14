# Changelog

## 0.1.2

- Shorter text: a one-sentence description and one-line review notes and refusals ("This key is trusted
  from now on. Compare it with the repository's page first").

## 0.1.1

- Reviewing a repository no longer fails with "entry.jar has no manifest": the signed index description is
  unpacked and its manifest, signature file and signature block are checked directly, so a jar that lists
  META-INF/MANIFEST.MF last (the real entry.jar of F-Droid and IzzyOnDroid) is read. The rules are F-Droid's own:
  one signer with one certificate, the pinned or linked fingerprint, no MD5 or SHA-1 digests. A body that is not
  a zip says how many bytes arrived and how they began. test/run.sh checks it against both real files.

## 0.1.0

- First version: F-Droid repositories as an app catalog for droidtop (apps.catalog@1): add by link, QR
  code, address and fingerprint or from F-Droid's own list; index v2 fetched and verified with F-Droid's
  rules; search, app pages with anti-features and signing keys, newest compatible versions for droidtop's
  Updates, and downloads droidtop checks before installing.
