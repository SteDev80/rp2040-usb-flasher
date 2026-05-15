package com.rp2040.usbflasher;

import android.app.PendingIntent;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import me.jahnen.libaums.core.UsbMassStorageDevice;
import me.jahnen.libaums.core.fs.FileSystem;
import me.jahnen.libaums.core.fs.UsbFile;
import me.jahnen.libaums.core.fs.UsbFileStreamFactory;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.rp2040.usbflasher.USB_PERMISSION";
    private static final int REQUEST_OPEN_FIRMWARE = 1001;
    private static final int RP2040_VENDOR_ID = 0x2E8A;

    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private Uri selectedFirmwareUri;

    private TextView deviceText;
    private TextView fileText;
    private TextView logText;
    private ProgressBar progressBar;
    private Button flashButton;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
                if (device != null) {
                    log("USB collegata: " + describeDevice(device));
                    scanForDevice();
                }
                return;
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
                if (device != null) {
                    log("USB scollegata: " + describeDevice(device));
                    if (selectedDevice != null && selectedDevice.getDeviceName().equals(device.getDeviceName())) {
                        selectedDevice = null;
                        deviceText.setText("Dispositivo: non rilevato");
                        updateFlashButton();
                    }
                }
                return;
            }

            if (!ACTION_USB_PERMISSION.equals(action)) {
                return;
            }

            UsbDevice device = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                selectedDevice = device;
                setDeviceText(device);
                log("Permesso USB concesso.");
            } else {
                log("Permesso USB negato.");
            }
            updateFlashButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerUsbReceiver();
        buildUi();
        handleAttachedDevice(getIntent());
        scanForDevice();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_FIRMWARE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        selectedFirmwareUri = data.getData();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (flags != 0) {
            try {
                getContentResolver().takePersistableUriPermission(selectedFirmwareUri, flags);
            } catch (SecurityException ignored) {
                // Some document providers grant temporary access only; the current selection still works.
            }
        }
        fileText.setText("File: " + getDisplayName(selectedFirmwareUri));
        log("File selezionato: " + getDisplayName(selectedFirmwareUri));
        updateFlashButton();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAttachedDevice(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbPermissionReceiver);
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad + getStatusBarPadding(), pad, pad);
        root.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("RP2040 USB Flasher");
        title.setTextSize(28);
        title.setTextColor(0xFF172026);
        title.setGravity(Gravity.START);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Carica firmware UF2 su Pico/RP2040 via USB OTG.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF52616B);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        deviceText = label("Dispositivo: non rilevato");
        fileText = label("File: nessun firmware selezionato");
        root.addView(deviceText);
        root.addView(fileText);

        Button scanButton = button("Cerca RP2040");
        scanButton.setOnClickListener(v -> scanForDevice());
        root.addView(scanButton);

        Button pickButton = button("Scegli file UF2");
        pickButton.setOnClickListener(v -> openFirmwarePicker());
        root.addView(pickButton);

        flashButton = button("Carica firmware");
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> flashFirmware());
        root.addView(flashButton);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setPadding(0, dp(10), 0, dp(10));
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        logText = label("");
        logText.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logText);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);
        log("Pronto. Collega l'RP2040 in BOOTSEL.");
    }

    private void openFirmwarePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream",
                "application/x-uf2"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_FIRMWARE);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(0xFF172026);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
        );
        params.setMargins(0, dp(8), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void handleAttachedDevice(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
        if (device != null && isLikelyRp2040Bootloader(device)) {
            selectedDevice = device;
            requestPermissionIfNeeded(device);
        }
    }

    private void scanForDevice() {
        selectedDevice = null;
        UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
        log("Dispositivi Mass Storage trovati: " + devices.length + ".");
        for (UsbMassStorageDevice storageDevice : devices) {
            UsbDevice usbDevice = storageDevice.getUsbDevice();
            log("Trovato: " + describeDevice(usbDevice));
            if (isLikelyRp2040Bootloader(usbDevice) || devices.length == 1) {
                selectedDevice = usbDevice;
                break;
            }
        }

        if (selectedDevice == null) {
            deviceText.setText("Dispositivo: non rilevato");
            log("Nessun dispositivo Mass Storage/RP2040 trovato.");
            updateFlashButton();
            return;
        }

        setDeviceText(selectedDevice);
        requestPermissionIfNeeded(selectedDevice);
        updateFlashButton();
    }

    private boolean isLikelyRp2040Bootloader(UsbDevice device) {
        return device.getVendorId() == RP2040_VENDOR_ID;
    }

    private void requestPermissionIfNeeded(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            log("Permesso USB gia disponibile.");
            updateFlashButton();
            return;
        }

        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE
        );
        usbManager.requestPermission(device, permissionIntent);
        log("Richiesto permesso USB ad Android.");
    }

    private void flashFirmware() {
        if (selectedDevice == null || selectedFirmwareUri == null) {
            return;
        }
        if (!usbManager.hasPermission(selectedDevice)) {
            requestPermissionIfNeeded(selectedDevice);
            return;
        }

        flashButton.setEnabled(false);
        progressBar.setProgress(0);
        log("Lettura firmware...");

        new Thread(() -> {
            try {
                byte[] firmware = readAllBytes(selectedFirmwareUri);
                validateUf2(firmware);
                runOnUiThread(() -> log("Scrittura UF2 sul bootloader RP2040..."));
                writeUf2ToBootloader(firmware);
                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    log("Firmware caricato. La scheda dovrebbe riavviarsi.");
                    Toast.makeText(this, "Firmware caricato", Toast.LENGTH_LONG).show();
                    updateFlashButton();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    log("Errore: " + error.getMessage());
                    Toast.makeText(this, "Caricamento non riuscito", Toast.LENGTH_LONG).show();
                    updateFlashButton();
                });
            }
        }).start();
    }

    private void writeUf2ToBootloader(byte[] firmware) throws Exception {
        if (isLikelyRp2040Bootloader(selectedDevice)) {
            logFromWorker("Modalita RP2040: scrittura raw dei blocchi UF2.");
            copyUf2Raw(firmware);
            return;
        }

        try {
            copyUf2ToBootloader(firmware);
        } catch (IndexOutOfBoundsException emptyPartitionList) {
            logFromWorker("Nessuna partizione leggibile: uso scrittura raw UF2.");
            copyUf2Raw(firmware);
        }
    }

    private void copyUf2Raw(byte[] firmware) throws Exception {
        RawScsiUf2Writer writer = new RawScsiUf2Writer(
                usbManager,
                selectedDevice,
                progress -> runOnUiThread(() -> progressBar.setProgress(progress))
        );
        writer.writeUf2(firmware);
    }

    private void copyUf2ToBootloader(byte[] firmware) throws Exception {
        UsbMassStorageDevice storageDevice = findSelectedStorageDevice();
        storageDevice.init();
        boolean wroteAllBytes = false;

        try {
            FileSystem fileSystem = storageDevice.getPartitions().get(0).getFileSystem();
            UsbFile root = fileSystem.getRootDirectory();
            deleteIfExists(root, "firmware.uf2");

            UsbFile firmwareFile = root.createFile("firmware.uf2");
            firmwareFile.setLength(firmware.length);

            int chunkSize = Math.max(fileSystem.getChunkSize(), 512);
            OutputStream output = null;
            try {
                output = UsbFileStreamFactory.createBufferedOutputStream(firmwareFile, fileSystem);
                int offset = 0;
                while (offset < firmware.length) {
                    int count = Math.min(chunkSize, firmware.length - offset);
                    output.write(firmware, offset, count);
                    offset += count;
                    int progress = offset * 100 / firmware.length;
                    runOnUiThread(() -> progressBar.setProgress(progress));
                }
                wroteAllBytes = true;
                output.flush();
            } catch (Exception error) {
                if (!wroteAllBytes) {
                    throw error;
                }
                runOnUiThread(() -> log("La scheda si e riavviata dopo la scrittura: comportamento normale."));
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } catch (Exception closeError) {
                        if (!wroteAllBytes) {
                            throw closeError;
                        }
                        runOnUiThread(() -> log("Connessione chiusa dal bootloader dopo il flash."));
                    }
                }
            }
        } finally {
            try {
                storageDevice.close();
            } catch (Exception closeError) {
                if (!wroteAllBytes) {
                    throw closeError;
                }
            }
        }
    }

    private UsbMassStorageDevice findSelectedStorageDevice() {
        UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
        for (UsbMassStorageDevice storageDevice : devices) {
            UsbDevice usbDevice = storageDevice.getUsbDevice();
            if (usbDevice.getDeviceName().equals(selectedDevice.getDeviceName())) {
                return storageDevice;
            }
        }
        throw new IllegalStateException("Dispositivo USB non piu disponibile.");
    }

    private void deleteIfExists(UsbFile root, String name) throws Exception {
        for (UsbFile file : root.listFiles()) {
            if (!file.isDirectory() && name.equalsIgnoreCase(file.getName())) {
                file.delete();
                return;
            }
        }
    }

    private byte[] readAllBytes(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("Impossibile aprire il file.");
            }
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void validateUf2(byte[] data) {
        if (data.length < 512 || data.length % 512 != 0) {
            throw new IllegalArgumentException("Il firmware deve essere un file UF2 a blocchi da 512 byte.");
        }
        int magic0 = littleEndianInt(data, 0);
        int magic1 = littleEndianInt(data, 4);
        if (magic0 != 0x0A324655 || magic1 != 0x9E5D5157) {
            throw new IllegalArgumentException("Il file selezionato non sembra un UF2 valido.");
        }
    }

    private int littleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private void updateFlashButton() {
        flashButton.setEnabled(selectedDevice != null
                && selectedFirmwareUri != null
                && usbManager.hasPermission(selectedDevice));
    }

    private void setDeviceText(UsbDevice device) {
        deviceText.setText("Dispositivo: " + describeDevice(device));
    }

    private String describeDevice(UsbDevice device) {
        return String.format(
                Locale.US,
                "VID %04X / PID %04X / %s",
                device.getVendorId(),
                device.getProductId(),
                device.getDeviceName()
        );
    }

    private void logFromWorker(String message) {
        runOnUiThread(() -> log(message));
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        }
        return uri.getLastPathSegment() == null ? "firmware.uf2" : uri.getLastPathSegment();
    }

    private <T> T getParcelableExtra(Intent intent, String name, Class<T> type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(name, type);
        }
        Object value = intent.getParcelableExtra(name);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private void log(String message) {
        logText.append(message + "\n");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getStatusBarPadding() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId <= 0) {
            return dp(12);
        }
        return Math.max(getResources().getDimensionPixelSize(resourceId), dp(12));
    }
}
