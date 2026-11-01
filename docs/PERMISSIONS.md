# Permessi / Permissions — GhostALO

GhostALO **non ha il permesso Internet**. / GhostALO has **no Internet permission**.

| Permesso / Permission | A cosa serve (IT) | What it is for (EN) | Se lo neghi / If denied |
|---|---|---|---|
| `QUERY_ALL_PACKAGES` | Vedere tutte le app installate per confrontarle con l'elenco di app spia, controllarne permessi e firme. È lo scopo principale dell'app. | See all installed apps to compare them with the spy-app list and check their permissions and signatures. Core purpose of the app. | Il controllo delle app non funziona / app checks do not work |
| `MANAGE_EXTERNAL_STORAGE` | Trovare e, solo dopo conferma, spostare nel cestino file inutili, duplicati, media vecchi delle chat; gestire il cestino. | Find and, only after confirmation, trash junk files, duplicates and old chat media; manage the trash. | Le funzioni di pulizia sono disattivate / cleaning features are off |
| `PACKAGE_USAGE_STATS` | Cache delle app, app non usate da tempo, app con servizi in background, data dell'ultima apertura. Concesso dall'utente nelle impostazioni. | App cache sizes, unused apps, apps with background services, last-opened dates. Granted by the user in settings. | Alcune informazioni mancano / some information is missing |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` (fino ad Android 12) | Elencare le foto create da app diverse dalla fotocamera. | List photos created by apps other than the camera. | Quella voce non è mostrata / that item is not shown |
| `POST_NOTIFICATIONS` | Avvisarti di nuove app sospette e cambi importanti. | Warn you about new suspicious apps and important changes. | Nessun avviso in background / no background alerts |
| `RECEIVE_BOOT_COMPLETED` | Far ripartire il controllo periodico dopo il riavvio. | Restart the periodic check after a reboot. | Il controllo riparte solo aprendo l'app / the check restarts only when you open the app |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Dire se sei su Wi-Fi aperto o con VPN. Nessun traffico è inviato o analizzato. | Tell whether you are on open Wi-Fi or using a VPN. No traffic is sent or analyzed. | Il controllo rete è incompleto / network check is incomplete |

## Note per Google Play (per quando servirà)

- `QUERY_ALL_PACKAGES` e `MANAGE_EXTERNAL_STORAGE` richiedono una dichiarazione e una giustificazione nella Play Console. L'uso della prima è coerente con la funzione principale (rilevare stalkerware). La seconda è la più delicata: Google la accetta in pochi casi (gestione file, antivirus). Una variante ridotta può usare solo la selezione dei file di Android (`ACTION_OPEN_DOCUMENT_TREE`) invece dell'accesso a tutti i file.
- Questionario "Sicurezza dei dati": nessun dato raccolto né condiviso; nessuna trasmissione (assenza del permesso Internet).
- Il nome "GhostALO" e l'icona non usano marchi di terzi.

## Notes for Google Play (for later)

- `QUERY_ALL_PACKAGES` and `MANAGE_EXTERNAL_STORAGE` need a declaration and justification in Play Console. The first fits the core purpose (detecting stalkerware). The second is the most sensitive: Google accepts it in few cases (file managers, antivirus). A reduced variant can use Android's file picker (`ACTION_OPEN_DOCUMENT_TREE`) instead of all-files access.
- "Data safety" form: no data collected or shared; no transmission (no Internet permission).
