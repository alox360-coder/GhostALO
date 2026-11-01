# Come si fa una release / How to make a release

1. Cambia `appVersion` in `app/build.gradle.kts` (es. `"1.1"`). Il `versionCode` si calcola da solo.
2. Aggiorna se serve `fastlane/metadata/android/*/` (descrizioni) e `README.md`.
3. Lancia il Build (Actions → Build → Run workflow). Si ferma se mancano le chiavi di firma o se il certificato dell'APK non coincide con `certificato.sha256`.
4. Controlla l'APK sul telefono: aggiornamento sopra la versione precedente, widget, avvisi in background, blocco, cestino.
5. Crea il tag `vX.Y` sul commit della release. Per F-Droid serve l'hash completo del commit (`git rev-parse vX.Y`), non il nome del tag.

La chiave di firma è nei Secrets (`SIGNING_*`); la copia di riserva cifrata è conservata fuori da GitHub. **Non cambiare mai la chiave** dopo la prima release pubblica: gli utenti non potrebbero più aggiornare.

1. Change `appVersion` in `app/build.gradle.kts`; `versionCode` is derived from it.
2. Update store texts and README if needed.
3. Run the Build. It stops if signing keys are missing or the APK certificate differs from `certificato.sha256`.
4. Test the APK on a phone (update over previous version, widgets, background alerts, lock, trash).
5. Tag the release commit. F-Droid needs the full commit hash, not the tag name.
