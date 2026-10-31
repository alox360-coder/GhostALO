# GhostALO

App Android in stile terminale (verde su nero) per controllare la privacy del telefono e fare pulizia.

- **Controllo rapido**: un tocco per sapere se è tutto a posto, con le voci da verificare. Il menu è diviso in Sicurezza, Privacy, Pulizia e Strumenti.
- **Sicurezza del dispositivo**: punteggio su 100 (blocco schermo, cifratura, aggiornamenti, debug USB, DNS privato, root, accessibilità…).
- **Scansione privacy + app spia**: confronta le app installate con l'elenco pubblico di stalkerware noti (nomi di pacchetto e firme) e segnala permessi e servizi a rischio.
- **Rilevatore di tracker**: cerca nel codice delle app le librerie pubblicitarie e di tracciamento più note.
- **Pulizia**: APK già scaricati, file temporanei, miniature, cartelle vuote, duplicati, cache e app non usate da tempo. Ogni eliminazione richiede conferma. Il pulsante "Libera cache di sistema" chiede ad Android di fare spazio cancellando le cache delle altre app (il risultato dipende dal telefono).
- **Pulizia WhatsApp e Telegram**: media ricevuti vecchi, note vocali, copie dei file inviati e stati già visti, con soglia a scelta (30-365 giorni). Le chat non vengono toccate; ogni eliminazione richiede conferma.
- **Analisi dello spazio per tipo**: foto, video, audio, documenti, archivi e app con barre in stile terminale, più i file più grandi.
- **App in background**: quali app hanno tenuto attivo un servizio in primo piano e per quanto, confrontato con l uso reale; collegamenti alle impostazioni di batteria di Android.
- **App senza icona e cartelle nascoste**: elenca le app installate senza icona nel menu (con permessi e servizi) e le cartelle nascoste con foto o video. Solo indizi.
- **Spazio privato e profili**: segnala se esistono altri profili (lavoro, clone, spazio privato) e porta alle impostazioni di Android.
- **Rete Wi-Fi e reti pubbliche**: tipo di protezione del Wi-Fi, VPN, DNS privato e consigli. Non rileva attacchi man-in-the-middle.
- **Permessi: chi usa cosa**: per ogni permesso (fotocamera, microfono, posizione, SMS…) le app che lo hanno, con collegamento alla loro scheda.
- **Controllo degli APK scaricati**: controlla i file .apk prima dell'installazione (app spia note, firma diversa, permessi sensibili).
- **Avvisi in background**: ogni 30 minuti circa controlla in silenzio e manda una notifica se compare un'app spia nota, un nuovo servizio di accessibilità, un nuovo amministratore del dispositivo, un nuovo lettore di notifiche o un'app installata da APK con permessi sensibili.
- **Widget** per la schermata Home con lo stato di sicurezza e un tasto per aggiornarlo.

## Privacy

Informativa completa / full policy: [PRIVACY.md](PRIVACY.md). L'app è disponibile in italiano e inglese (segue la lingua del telefono).

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
