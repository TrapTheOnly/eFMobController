package com.padlayer;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Runs shell commands through Wireless debugging on this phone. The translator
 * process is detached, so it keeps running after debugging is turned off.
 */
final class AdbShell {
    private static final String EXIT = "PADLAYER_EXIT:";

    private final Context context;

    AdbShell(Context context) {
        this.context = context.getApplicationContext();
    }

    static void pair(Context context, int port, String code) throws Exception {
        PadAdb adb = PadAdb.get(context);
        if (!adb.pair("127.0.0.1", port, code)) {
            throw new IllegalStateException("Pairing did not complete.");
        }
    }

    static int findPairingPort(Context context) throws Exception {
        return findPort(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 8000);
    }

    void connect(String portText) throws Exception {
        PadAdb adb = PadAdb.get(context);
        if (adb.isConnected()) {
            return;
        }
        adb.setHostAddress("127.0.0.1");
        if (portText != null && !portText.isEmpty()) {
            try {
                if (adb.connect("127.0.0.1", parsePort(portText)) || adb.isConnected()) {
                    return;
                }
            } catch (Exception ignored) {
                // The typed port can be stale after Wireless debugging restarts.
            }
        }
        try {
            if (adb.connectTls(context, 8000) || adb.isConnected()) {
                return;
            }
        } catch (AdbPairingRequiredException e) {
            throw new IllegalStateException("Tap Pair and enter the code from the Wireless debugging pairing screen.");
        } catch (Exception ignored) {
            // Discovery can fail on this phone. The system property is the next try.
        }
        int propertyPort = propertyPort("service.adb.tls.port");
        if (propertyPort > 0 && (adb.connect("127.0.0.1", propertyPort) || adb.isConnected())) {
            return;
        }
        throw new IllegalStateException(
                "Turn Wireless debugging on. If it is already on, type the port shown next to the IP address.");
    }

    String run(String script) throws Exception {
        PadAdb adb = PadAdb.get(context);
        String suffix = script.endsWith("&") ? " echo " : "; echo ";
        try (AdbStream stream = adb.openStream("shell:" + script + suffix + EXIT + "$?")) {
            String text = readText(stream.openInputStream());
            int marker = text.lastIndexOf(EXIT);
            if (marker < 0) {
                throw new IllegalStateException(text.isEmpty() ? "Wireless debugging returned no result." : text);
            }
            String body = text.substring(0, marker).trim();
            String code = text.substring(marker + EXIT.length()).trim();
            int newline = code.indexOf('\n');
            if (newline >= 0) {
                code = code.substring(0, newline).trim();
            }
            if (!"0".equals(code)) {
                throw new IllegalStateException(body.isEmpty() ? "Command failed (" + code + ")" : body);
            }
            return body;
        }
    }

    void pushAsset(String asset, String dest) throws Exception {
        PadAdb adb = PadAdb.get(context);
        try (AdbStream stream = adb.openStream("sync:");
             InputStream in = context.getAssets().open(asset)) {
            String spec = dest + ",420";
            byte[] specBytes = spec.getBytes(StandardCharsets.UTF_8);
            writeHeader(stream, "SEND", specBytes.length);
            stream.write(specBytes, 0, specBytes.length);
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                writeHeader(stream, "DATA", n);
                stream.write(buffer, 0, n);
            }
            writeHeader(stream, "DONE", (int) (System.currentTimeMillis() / 1000));
            byte[] response = new byte[8];
            readFully(stream.openInputStream(), response);
            String id = new String(response, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(id)) {
                int length = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                byte[] message = new byte[Math.max(0, Math.min(length, 512))];
                if (message.length > 0) {
                    readFully(stream.openInputStream(), message);
                }
                throw new IllegalStateException("Could not copy " + asset + ": " + new String(message, StandardCharsets.UTF_8));
            }
        }
    }

    private static void writeHeader(AdbStream stream, String id, int value) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put(id.getBytes(StandardCharsets.US_ASCII));
        header.putInt(value);
        stream.write(header.array(), 0, 8);
    }

    private static int findPort(Context context, String serviceType, long timeoutMs) throws Exception {
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch found = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(context, serviceType, new AdbMdns.OnAdbDaemonDiscoveredListener() {
            @Override
            public void onPortChanged(InetAddress hostAddress, int discovered) {
                if (hostAddress != null && discovered > 0) {
                    port.set(discovered);
                    found.countDown();
                }
            }
        });
        mdns.start();
        try {
            if (!found.await(timeoutMs, TimeUnit.MILLISECONDS) || port.get() < 0) {
                throw new IllegalStateException("The pairing screen was not found. Leave it open and type its port.");
            }
            return port.get();
        } finally {
            mdns.stop();
        }
    }

    private static int propertyPort(String name) {
        try {
            Process process = new ProcessBuilder("getprop", name).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[64];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
                return -1;
            }
            return Integer.parseInt(out.toString(StandardCharsets.UTF_8.name()).trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException(text);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That port is not a number from the Wireless debugging page.");
        }
    }

    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void readFully(InputStream in, byte[] dest) throws Exception {
        int offset = 0;
        while (offset < dest.length) {
            int n = in.read(dest, offset, dest.length - offset);
            if (n < 0) {
                throw new IllegalStateException("Wireless debugging closed the file copy early.");
            }
            offset += n;
        }
    }
}
