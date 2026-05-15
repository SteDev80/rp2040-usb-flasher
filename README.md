# RP2040 Flasher

App Android nativa per caricare firmware `.uf2` su una scheda RP2040 collegata via USB OTG.

## Uso

1. Metti la scheda RP2040 in modalita BOOTSEL.
2. Collega la scheda al telefono/tablet Android con cavo USB OTG.
3. Apri l'app e premi **Cerca RP2040**.
4. Seleziona un file `.uf2`.
5. Premi **Carica firmware**.

## Note

- Il bootloader ROM standard dell'RP2040 si presenta come dispositivo USB Mass Storage. Per `VID 2E8A` l'app scrive direttamente i blocchi UF2 via SCSI raw, perche il volume BOOTSEL puo non esporre una partizione FAT32 leggibile da Android.
- La gestione USB Mass Storage/FAT e affidata a `me.jahnen.libaums:core:0.10.0`.
- I file `.bin` non sono caricabili dal bootloader BOOTSEL standard senza un bootloader/protocollo dedicato sul firmware gia presente.
- Serve un dispositivo Android con supporto USB Host/OTG.

## Build

Apri questa cartella con Android Studio e compila il modulo `app`.

Da terminale, se hai Gradle e Android SDK configurati:

```powershell
gradle assembleDebug
```

L'APK debug viene generato in:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Per generare una release firmata crea un tuo keystore locale e imposta questi valori in `local.properties`:

```properties
RELEASE_STORE_FILE=../keystore/rp2040-flasher-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=rp2040-flasher
RELEASE_KEY_PASSWORD=...
```

Poi esegui:

```powershell
gradle assembleRelease
```

## Risoluzione problemi USB

- Usa un cavo dati USB OTG, non un cavo solo ricarica.
- Collega la scheda tenendo premuto BOOTSEL: Android deve vederla come disco USB.
- Se Android mostra una finestra di permesso USB, accettala.
- Se il log dice che la USB si e scollegata dopo la copia, e normale: l'RP2040 si riavvia quando riceve un UF2 valido.
- Se il telefono monta automaticamente il disco e l'app non riesce ad aprirlo, scollega e ricollega la scheda mentre l'app e aperta.
