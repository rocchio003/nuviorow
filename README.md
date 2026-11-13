# Nuvio Rows

App per Android TV che pubblica i cataloghi degli addon Stremio/Nuvio (Cinemeta e simili) come **righe nella home** di Projectivy Launcher, nello stesso modo di "Continua a guardare".

Funziona tramite i *canali* di Android TV (TvProvider): l'app crea un canale per ogni catalogo scelto e lo riempie con le locandine. Projectivy mostra i canali di questo tipo.

## Come si usa

1. Installa l'APK sulla TV (vedi sotto).
2. Apri **Nuvio Rows**.
3. In "Impostazioni" scrivi il pacchetto dell'app Nuvio (serve per aprirla al tocco di una card).
4. Incolla l'URL del manifest di un addon e premi **Aggiungi addon**. In alternativa usa **Importa lista** con l'URL di un file di testo (es. un Gist raw) con un manifest per riga.
   Esempio per provare: `https://v3-cinemeta.strem.io/manifest.json`
5. Spunta i cataloghi che vuoi in home.
6. Premi **Aggiorna le righe adesso**. Se compare la richiesta di mostrare i canali, accetta.
7. In Projectivy abilita le nuove righe dalle impostazioni dei canali/categorie. Poi puoi riordinarle.

Da lì le righe si aggiornano da sole ogni 6 ore.

## Apertura delle card

- Solo pacchetto: la card apre l'app Nuvio.
- Pacchetto + deep link: la card apre direttamente il titolo, se Nuvio supporta un deep link. Esempio di formato: `nuvio://{type}/{id}`. Segnaposto: `{type}` (movie/series), `{id}` (es. tt0111161), `{name}`. Lo schema giusto va preso dal manifest dell'app Nuvio (cerca gli `intent-filter` con `android:scheme`).

## Compilare l'APK da GitHub (senza PC)

1. Crea un repository e carica tutti i file di questo progetto mantenendo le cartelle.
2. Vai nella scheda **Actions**: la build parte a ogni commit (o con "Run workflow").
3. A build finita, l'APK è nella sezione **Releases** (release `latest`, file `NuvioRows.apk`) e anche negli artifact del workflow.
4. Installalo sulla TV con Downloader (incollando l'URL dell'asset) oppure con `adb install NuvioRows.apk`.

## Note

- Servono Android 8.0+ e un launcher che supporti i canali Android TV (Projectivy sì; sui launcher stock di Google TV/Fire TV il supporto può mancare).
- I cataloghi che richiedono un parametro obbligatorio (per esempio solo ricerca) non compaiono, perché non si possono mostrare come riga.
