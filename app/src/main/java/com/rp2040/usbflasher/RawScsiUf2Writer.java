package com.rp2040.usbflasher;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class RawScsiUf2Writer {
    interface ProgressListener {
        void onProgress(int percent);
    }

    private static final int BLOCK_SIZE = 512;
    private static final int TIMEOUT_MS = 10000;
    private static final int CBW_SIGNATURE = 0x43425355;
    private static final int CSW_SIGNATURE = 0x53425355;

    private final UsbManager usbManager;
    private final UsbDevice device;
    private final ProgressListener progressListener;
    private int tag = 1;

    RawScsiUf2Writer(UsbManager usbManager, UsbDevice device, ProgressListener progressListener) {
        this.usbManager = usbManager;
        this.device = device;
        this.progressListener = progressListener;
    }

    void writeUf2(byte[] data) throws Exception {
        UsbInterface usbInterface = findMassStorageInterface();
        UsbEndpoint bulkIn = null;
        UsbEndpoint bulkOut = null;

        for (int index = 0; index < usbInterface.getEndpointCount(); index++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(index);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                bulkIn = endpoint;
            } else {
                bulkOut = endpoint;
            }
        }

        if (bulkIn == null || bulkOut == null) {
            throw new IllegalStateException("Endpoint USB bulk non trovati.");
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Impossibile aprire il dispositivo USB.");
        }

        boolean wroteAllBlocks = false;
        try {
            if (!connection.claimInterface(usbInterface, true)) {
                throw new IllegalStateException("Impossibile acquisire l'interfaccia Mass Storage.");
            }

            int blockCount = data.length / BLOCK_SIZE;
            for (int block = 0; block < blockCount; block++) {
                byte[] chunk = Arrays.copyOfRange(data, block * BLOCK_SIZE, (block + 1) * BLOCK_SIZE);
                write10(connection, bulkOut, bulkIn, block, chunk);
                progressListener.onProgress((block + 1) * 100 / blockCount);
            }
            wroteAllBlocks = true;

            try {
                synchronizeCache(connection, bulkOut, bulkIn);
            } catch (Exception ignoredAfterWrite) {
                // UF2 bootloaders often disconnect immediately after receiving the final block.
            }
        } catch (Exception error) {
            if (!wroteAllBlocks) {
                throw error;
            }
        } finally {
            try {
                connection.releaseInterface(usbInterface);
            } catch (Exception ignored) {
            }
            connection.close();
        }
    }

    private UsbInterface findMassStorageInterface() {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return usbInterface;
            }
        }
        throw new IllegalStateException("Interfaccia USB Mass Storage non trovata.");
    }

    private void write10(
            UsbDeviceConnection connection,
            UsbEndpoint bulkOut,
            UsbEndpoint bulkIn,
            int logicalBlockAddress,
            byte[] block
    ) throws Exception {
        byte[] command = new byte[10];
        command[0] = 0x2A;
        putInt(command, 2, logicalBlockAddress);
        command[7] = 0;
        command[8] = 1;
        transport(connection, bulkOut, bulkIn, command, block, false);
    }

    private void synchronizeCache(
            UsbDeviceConnection connection,
            UsbEndpoint bulkOut,
            UsbEndpoint bulkIn
    ) throws Exception {
        byte[] command = new byte[10];
        command[0] = 0x35;
        transport(connection, bulkOut, bulkIn, command, new byte[0], false);
    }

    private void transport(
            UsbDeviceConnection connection,
            UsbEndpoint bulkOut,
            UsbEndpoint bulkIn,
            byte[] command,
            byte[] data,
            boolean dataIn
    ) throws Exception {
        int currentTag = tag++;
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(CBW_SIGNATURE);
        cbw.putInt(currentTag);
        cbw.putInt(data.length);
        cbw.put((byte) (dataIn ? 0x80 : 0x00));
        cbw.put((byte) 0);
        cbw.put((byte) command.length);
        cbw.put(Arrays.copyOf(command, 16));

        bulkTransferExact(connection, bulkOut, cbw.array(), cbw.array().length);

        if (data.length > 0) {
            UsbEndpoint dataEndpoint = dataIn ? bulkIn : bulkOut;
            bulkTransferExact(connection, dataEndpoint, data, data.length);
        }

        byte[] csw = new byte[13];
        bulkTransferExact(connection, bulkIn, csw, csw.length);
        ByteBuffer cswBuffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN);
        int signature = cswBuffer.getInt();
        int responseTag = cswBuffer.getInt();
        cswBuffer.getInt();
        int status = cswBuffer.get() & 0xFF;

        if (signature != CSW_SIGNATURE || responseTag != currentTag) {
            throw new IllegalStateException("Risposta USB Mass Storage non valida.");
        }
        if (status != 0) {
            throw new IllegalStateException("Comando SCSI fallito con stato " + status + ".");
        }
    }

    private void bulkTransferExact(
            UsbDeviceConnection connection,
            UsbEndpoint endpoint,
            byte[] buffer,
            int length
    ) throws Exception {
        int offset = 0;
        while (offset < length) {
            byte[] packet = offset == 0 && length == buffer.length
                    ? buffer
                    : Arrays.copyOfRange(buffer, offset, length);
            int transferred = connection.bulkTransfer(endpoint, packet, packet.length, TIMEOUT_MS);
            if (transferred <= 0) {
                throw new IllegalStateException("Trasferimento USB interrotto.");
            }
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN && packet != buffer) {
                System.arraycopy(packet, 0, buffer, offset, transferred);
            }
            offset += transferred;
        }
    }

    private void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }
}
