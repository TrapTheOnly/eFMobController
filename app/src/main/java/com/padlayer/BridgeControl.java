package com.padlayer;

import android.content.Context;

/**
 * Copies the translator onto the phone and starts it through Wireless debugging,
 * so the USB cable does not have to stay connected.
 */
public final class BridgeControl {
    static final String JAR = "/data/local/tmp/padlayer-bridge.jar";
    static final String SO = "/data/local/tmp/libpadlayer.so";
    static final String LOG = "/data/local/tmp/padlayer.log";
    static final String PID = "/data/local/tmp/padlayer.pid";

    private final AdbShell shell;

    private BridgeControl(AdbShell shell) {
        this.shell = shell;
    }

    static String start(Context context, String connectPort) throws Exception {
        AdbShell shell = new AdbShell(context);
        shell.connect(connectPort);
        BridgeControl control = new BridgeControl(shell);
        control.stopProcesses();
        boolean padWasOn = control.xboxPresent();
        if (padWasOn) {
            control.run("cmd bluetooth_manager disable >/dev/null 2>&1 || true");
            if (!control.waitUntil(15000, new Check() {
                @Override
                public boolean ok() throws Exception {
                    return !control.xboxPresent();
                }
            })) {
                control.run("cmd bluetooth_manager enable >/dev/null 2>&1 || true");
                throw new IllegalStateException(
                        "The Xbox pad stayed connected, so the translator could not take the first controller slot. Turn the pad off and tap Start again.");
            }
        }
        try {
            shell.pushAsset("padlayer-bridge.jar", JAR);
            shell.pushAsset("libpadlayer.so", SO);
            control.run("rm -f " + LOG + " " + PID);
            control.run("toybox nohup toybox setsid /system/bin/sh -c 'CLASSPATH=" + JAR
                    + " exec app_process /system/bin com.padlayer.bridge.BridgeMain' </dev/null >"
                    + LOG + " 2>&1 &");
            if (!control.waitUntil(20000, new Check() {
                @Override
                public boolean ok() throws Exception {
                    String log = control.run("cat " + LOG + " 2>/dev/null || true");
                    if (log.contains("Virtual controller failed")) {
                        throw new IllegalStateException(control.lastLines(log));
                    }
                    return log.contains("Virtual controller is live");
                }
            })) {
                throw new IllegalStateException("The translator did not stay running.\n" + control.logTail());
            }
        } finally {
            if (padWasOn) {
                control.run("cmd bluetooth_manager enable >/dev/null 2>&1 || true");
            }
        }
        if (padWasOn) {
            return "Translator is running. Bluetooth is coming back. Turn the Xbox pad on if it does not reconnect, then open a VS AI match.";
        }
        return "Translator is running. Turn the Xbox pad on, then open a VS AI match. You can unplug the cable.";
    }

    static void stop(Context context, String connectPort) throws Exception {
        AdbShell shell = new AdbShell(context);
        shell.connect(connectPort);
        new BridgeControl(shell).stopProcesses();
    }

    static String status(Context context, String connectPort) throws Exception {
        AdbShell shell = new AdbShell(context);
        shell.connect(connectPort);
        BridgeControl control = new BridgeControl(shell);
        if (!control.running()) {
            return "stopped\n";
        }
        return "running\n" + control.logTail();
    }

    private boolean running() {
        try {
            String ps = run("ps -A -o PID,ARGS");
            for (String line : ps.split("\n")) {
                if (line.contains("com.padlayer.bridge.BridgeMain") && line.contains("app_process")) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String logTail() {
        try {
            return lastLines(run("cat " + LOG + " 2>/dev/null || true"));
        } catch (Exception e) {
            return "";
        }
    }

    private void stopProcesses() throws Exception {
        String ps = run("ps -A -o PID,ARGS");
        for (String line : ps.split("\n")) {
            if (!line.contains("com.padlayer.bridge.BridgeMain") || !line.contains("app_process")) {
                continue;
            }
            String pid = line.trim().split("\\s+")[0];
            run("kill " + pid + " >/dev/null 2>&1 || true");
        }
        run("rm -f " + PID);
        if (!waitUntil(4000, new Check() {
            @Override
            public boolean ok() {
                return !running();
            }
        })) {
            throw new IllegalStateException("The previous translator is still running.");
        }
    }

    private boolean xboxPresent() throws Exception {
        String listing = run("getevent -lp 2>/dev/null || true");
        for (String line : listing.split("\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("name:") && lower.contains("xbox") && !lower.contains("padlayer")) {
                return true;
            }
        }
        return false;
    }

    private String run(String script) throws Exception {
        return shell.run(script);
    }

    private boolean waitUntil(long timeoutMs, Check check) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (check.ok()) {
                return true;
            }
            Thread.sleep(250);
        }
        return check.ok();
    }

    private String lastLines(String text) {
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 8);
        StringBuilder tail = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            if (tail.length() > 0) {
                tail.append('\n');
            }
            tail.append(lines[i]);
        }
        return tail.toString();
    }

    private interface Check {
        boolean ok() throws Exception;
    }
}
