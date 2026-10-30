# Controllo Privacy

App Android in stile terminale (verde su nero) per controllare la privacy del telefono e fare pulizia.

- **Sicurezza del dispositivo**: punteggio su 100 (blocco schermo, cifratura, aggiornamenti, debug USB, DNS privato, root, accessibilità…).
- **Scansione privacy + app spia**: confronta le app installate con l'elenco pubblico di stalkerware noti (nomi di pacchetto e firme) e segnala permessi e servizi a rischio.
- **Rilevatore di tracker**: cerca nel codice delle app le librerie pubblicitarie e di tracciamento più note.
- **Pulizia**: APK già scaricati, file temporanei, miniature, cartelle vuote, duplicati, cache e app non usate da tempo. Ogni eliminazione richiede conferma.

## Privacy

L'app **non ha il permesso Internet**: non può inviare nulla fuori dal telefono. Non modifica né elimina niente senza la tua conferma.

## Limiti

- Non può leggere i dati di altre app né vedere in tempo reale chi usa fotocamera e microfono.
- Non può svuotare cache e cronologia di altre app (Android lo vieta).
- Non è un antivirus: confronta app e firme con un elenco noto e cerca indizi. I risultati sono indizi, non prove.
- L'elenco di app spia può non essere completo.

## Scaricare e verificare

Gli APK sono nella sezione **Releases**. Ogni release contiene anche `ControlloPrivacy.apk.sha256`:
confronta l'impronta SHA-256 del file scaricato con quella indicata. Le release firmate con la chiave del progetto
hanno sempre lo stesso certificato; una release marcata "pre-release" è firmata con una chiave di prova.

## Compilare

```
./gradlew assembleRelease
```

Senza variabili d'ambiente di firma l'APK è firmato con la chiave di prova di Android. Per firmare con la propria chiave
impostare `SIGNING_KEYSTORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`
(in GitHub Actions: i quattro secrets omonimi, con il keystore in base64 in `SIGNING_KEYSTORE_B64`).

Non inserire mai chiavi nel repository.

## Crediti e licenza dei dati

L'elenco di app spia deriva da [stalkerware-indicators](https://github.com/AssoEchap/stalkerware-indicators)
di Echap, licenza [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Nessuna garanzia di completezza o accuratezza.

Se sospetti che un partner o un familiare violento ti controlli il telefono, rimuovere un'app spia potrebbe avvisarlo:
valuta prima di contattare un centro antiviolenza (in Italia: 1522, gratuito e attivo 24 ore su 24).
