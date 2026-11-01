# GhostALO

App Android in stile terminale (verde su nero) per controllare la privacy del telefono e fare pulizia, **senza Internet**: l'app non ha il permesso di usare la rete, quindi non può inviare nulla fuori dal telefono. Niente pubblicità, statistiche o account.

*English summary at the bottom.*

## Cosa fa

**Sicurezza e app spia**
- **Controllo rapido**: un tocco per sapere se è tutto a posto. Tre esiti (minaccia nota, da verificare, nessun indicatore) e l'elenco dei controlli eseguiti e di quelli non completati: un controllo non eseguito non conta mai come superato.
- **Sicurezza del dispositivo**: punteggio su 100 (blocco schermo, cifratura, aggiornamenti, debug USB, DNS privato, root, accessibilità…).
- **Scansione privacy e app spia**: confronta le app installate con l'elenco pubblico di stalkerware noti (nomi di pacchetto e firme) e segnala permessi e servizi a rischio. L'elenco si può aggiornare da file (ioc.yaml di Echap), senza Internet.
- **Avvisi in background** (ogni 15 minuti - 6 ore, a scelta): notifica per una nuova app spia, un nuovo servizio di accessibilità, amministratore del dispositivo o lettore di notifiche, un'app da APK con permessi sensibili, un nuovo permesso sensibile su un'app già installata, o una firma cambiata. Storico degli ultimi 50 avvisi e app fidate.
- **Controllo degli APK scaricati** prima dell'installazione, **rilevatore di tracker** (librerie note nel codice delle app), **app senza icona e cartelle nascoste**, **spazio privato e profili**, **rete Wi-Fi e reti pubbliche**.

**Privacy**
- **Permessi: chi usa cosa**, con collegamento alla scheda di ogni app.
- **Microfono e fotocamera**: app con il permesso attivo, da quanto non vengono aperte e un tasto per toglierlo.
- **Cookie e tracciamento**: browser installati, collegamento alle loro schede e consigli (Android non permette di cancellare i cookie al posto tuo).
- **Blocco dell'app** con il PIN, la sequenza o l'impronta del telefono (nessun codice salvato), richiesto dopo il tempo scelto.

**Pulizia**
- File temporanei, miniature, APK già installati, cartelle vuote, **duplicati** (controllo veloce e poi hash completo), download incompleti, archivi vecchi, cache e app non usate da tempo, appunti.
- **File grandi e vecchi**, **cartelle di app disinstallate** (indizi), **WhatsApp e Telegram** (media vecchi, note vocali, stati già visti; le chat non vengono toccate), analisi dello spazio per tipo, app in background.
- **Cestino**: i file puliti restano recuperabili per 7 o 30 giorni. Prima di eliminare, l'app ricontrolla che i file non siano cambiati dopo la scansione. Ogni eliminazione richiede conferma.

**Altro**: widget per la Home (piccolo e esteso), esporta l'elenco delle app, rapporto errori **solo locale** (lo condividi tu, se vuoi), italiano e inglese (segue la lingua del telefono).

## Limiti

- Non è un antivirus: confronta app e firme con un elenco noto e cerca indizi. **I risultati sono indizi, non prove.** L'elenco può non essere completo.
- Non può leggere i dati di altre app né vedere in tempo reale chi usa fotocamera e microfono (Android lo vieta): l'app mostra chi *potrebbe* farlo, non chi lo fa. La prova concreta è la Dashboard privacy del telefono.
- Non può svuotare cache e cronologia di altre app.
- Se temi di essere sorvegliato da una persona, considera che una pulizia o una disinstallazione improvvisa può insospettirla.

## Privacy e permessi

Informativa completa: [PRIVACY.md](PRIVACY.md). Elenco dei permessi con il motivo di ciascuno: [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## Scaricare e verificare

Gli APK sono nella sezione **Releases**. Ogni release contiene anche `GhostALO.apk.sha256` (confronta l'impronta SHA-256 del file scaricato) e `GhostALO-mapping.txt` (serve a rileggere i rapporti di errore). Le release firmate con la chiave del progetto hanno sempre lo stesso certificato; una release marcata "pre-release" o "TEST" è firmata con una chiave di prova.

## Compilare

```
./gradlew assembleRelease
```

Senza variabili d'ambiente di firma l'APK è firmato con la chiave di prova di Android. Per firmare con la propria chiave impostare `SIGNING_KEYSTORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` (in GitHub Actions: i quattro secrets omonimi, con il keystore in base64 in `SIGNING_KEYSTORE_B64`). Non inserire mai chiavi nel repository.

Il `versionCode` in GitHub Actions è `100 + numero della build`; in locale si può fissare con `-PghostaloVersionCode=NNN`.

## Test

```
./gradlew testDebugUnitTest
```

Controllano, tra l'altro, l'importazione dell'elenco spia, le impronte dei file, l'assenza del permesso Internet e la coerenza delle traduzioni. Sono eseguiti anche a ogni build.

## Licenza e crediti

Codice sotto licenza **GPL-3.0** (vedi [LICENSE](LICENSE)). L'elenco delle app spia incluso nell'app deriva dal progetto pubblico [stalkerware-indicators](https://github.com/AssoEchap/stalkerware-indicators) dell'associazione Echap, licenza CC BY 4.0.

---

## English summary

GhostALO is a terminal-style Android app that checks your phone's privacy and cleans it up, **with no Internet permission**: it cannot send anything out of your phone. It compares installed apps with the public list of known stalkerware, warns you in the background about new accessibility services, device administrators, notification listeners, new sensitive permissions and changed signatures, lists apps with microphone and camera access, detects trackers, and cleans junk files, duplicates, old chat media and more, with a recoverable trash and a re-check before every deletion. The app is in Italian and English. It is **not an antivirus**: results are clues, not proof. Licensed under GPL-3.0. See [PRIVACY.md](PRIVACY.md) and [docs/PERMISSIONS.md](docs/PERMISSIONS.md).
