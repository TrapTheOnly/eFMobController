package com.padlayer.bridge;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.padlayer.engine.Converter;
import com.padlayer.engine.Output;
import com.padlayer.engine.PadState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/**
 * Runs as the shell user, started from the phone or from a computer. It grabs
 * the Xbox event device so the game does not see the raw gesture, then writes
 * a virtual gamepad. There is no feint method on the game to call.
 */
public final class BridgeMain {
    private static final int EV_KEY = 1;
    private static final int EV_ABS = 3;
    private static final int EVIOCGRAB = 0x40044590;
    private static final int SIZE = 24;

    private static final int ABS_X = 0;
    private static final int ABS_Y = 1;
    private static final int ABS_Z = 2;
    private static final int ABS_RX = 3;
    private static final int ABS_RY = 4;
    private static final int ABS_RZ = 5;
    private static final int ABS_GAS = 9;
    private static final int ABS_BRAKE = 10;
    private static final int ABS_HAT0X = 16;
    private static final int ABS_HAT0Y = 17;

    private static final int BTN_SOUTH = 0x130;
    private static final int BTN_EAST = 0x131;
    private static final int BTN_NORTH = 0x133;
    private static final int BTN_WEST = 0x134;
    private static final int BTN_TL = 0x136;
    private static final int BTN_TR = 0x137;
    private static final int BTN_START = 0x13b;
    private static final int BTN_THUMBL = 0x13d;
    private static final int BTN_THUMBR = 0x13e;

    private final Converter converter = new Converter();
    private final PadState pad = new PadState();
    private final Object lock = new Object();
    private boolean androidStyle = true;
    private final boolean[] axisReady = new boolean[16];
    private final int[] axisCenter = new int[16];
    private final boolean[] axisLogged = new boolean[64];
    private final HashSet<Integer> seenKeys = new HashSet<>();
    private int eventsSeen;
    private Object inputManager;
    private Method injectMethod;
    private int deviceId;
    private Output previous = new Output();

    public static void main(String[] args) throws Exception {
        new BridgeMain().run();
    }

    private void run() throws Exception {
        writePid();
        try {
            loadNative();
            prepareAndroid();
            int virtual = nativeOpenVirtual();
            if (virtual != 0) {
                throw new IOException("Virtual controller failed (" + virtual + ")");
            }
            log("Virtual controller is live");
            Thread.sleep(400);
            File device = findXbox();
            log("Using " + device.getAbsolutePath());
            try (FileInputStream in = new FileInputStream(device)) {
                grab(in);
                Thread reader = new Thread(() -> readLoop(in), "pad-read");
                reader.setDaemon(true);
                reader.start();
                long next = System.nanoTime();
                long frameNs = 1_000_000_000L / 120L;
                while (reader.isAlive()) {
                    Output out;
                    synchronized (lock) {
                        pad.timeMs = SystemClock.uptimeMillis();
                        out = converter.step(copy());
                    }
                    publish(out);
                    next += frameNs;
                    long delay = next - System.nanoTime();
                    if (delay <= 0) {
                        next = System.nanoTime();
                        continue;
                    }
                    Thread.sleep(delay / 1_000_000L, (int) (delay % 1_000_000L));
                }
            }
        } finally {
            removePid();
        }
    }

    private static void writePid() {
        try (FileOutputStream out = new FileOutputStream("/data/local/tmp/padlayer.pid")) {
            out.write((android.os.Process.myPid() + "\n").getBytes());
        } catch (IOException e) {
            log("Could not write pid file: " + e.getMessage());
        }
    }

    private static void removePid() {
        new File("/data/local/tmp/padlayer.pid").delete();
    }

    private PadState copy() {
        PadState s = new PadState();
        s.lx = pad.lx;
        s.ly = pad.ly;
        s.rx = pad.rx;
        s.ry = pad.ry;
        s.rt = pad.rt;
        s.lt = pad.lt;
        s.a = pad.a;
        s.b = pad.b;
        s.x = pad.x;
        s.y = pad.y;
        s.lb = pad.lb;
        s.rb = pad.rb;
        s.l3 = pad.l3;
        s.r3 = pad.r3;
        s.start = pad.start;
        s.dpadX = pad.dpadX;
        s.dpadY = pad.dpadY;
        s.timeMs = pad.timeMs;
        return s;
    }

    private void prepareAndroid() throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        systemMain.invoke(null);
        Method current = activityThread.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object thread = current.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context context = (Context) getSystemContext.invoke(thread);
        if (context == null) {
            throw new IllegalStateException("No system context");
        }
        Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
        Method getInstance = managerClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        inputManager = getInstance.invoke(null);
        for (Method method : managerClass.getDeclaredMethods()) {
            if ("injectInputEvent".equals(method.getName())) {
                injectMethod = method;
                injectMethod.setAccessible(true);
                break;
            }
        }
        if (injectMethod == null) {
            throw new IllegalStateException("InputManager.injectInputEvent is missing");
        }
        log("inject " + injectMethod.getParameterCount() + " args");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice found = InputDevice.getDevice(id);
            if (found != null && isPhysicalXbox(found.getName())) {
                deviceId = id;
                log("Injecting as device " + id + " " + found.getName());
            }
        }
    }

    private static boolean isPhysicalXbox(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("xbox") && !lower.contains("padlayer");
    }

    private File findXbox() throws IOException, InterruptedException {
        log("Turn the Xbox pad on");
        for (int attempt = 0; attempt < 180; attempt++) {
            File found = scanGetevent();
            if (found != null) {
                return found;
            }
            Thread.sleep(500);
        }
        throw new IOException("No Xbox controller event device. Pair it in Bluetooth first.");
    }

    private File scanGetevent() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("getevent", "-lp").redirectErrorStream(true).start();
        String path = null;
        String name = null;
        boolean gas = false;
        boolean brake = false;
        File match = null;
        String matchName = null;
        boolean matchAndroid = true;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("add device")) {
                    if (match == null && isPhysicalXbox(name) && path != null) {
                        match = new File(path);
                        matchName = name;
                        matchAndroid = gas || brake;
                    }
                    int colon = trimmed.lastIndexOf(':');
                    path = colon >= 0 ? trimmed.substring(colon + 1).trim() : null;
                    name = null;
                    gas = false;
                    brake = false;
                } else if (trimmed.startsWith("name:") && trimmed.contains("\"")) {
                    name = trimmed.substring(trimmed.indexOf('"') + 1, trimmed.lastIndexOf('"'));
                } else if (trimmed.startsWith("ABS_GAS")) {
                    gas = true;
                } else if (trimmed.startsWith("ABS_BRAKE")) {
                    brake = true;
                }
            }
        }
        process.waitFor();
        if (match == null && isPhysicalXbox(name) && path != null) {
            match = new File(path);
            matchName = name;
            matchAndroid = gas || brake;
        }
        if (match != null) {
            androidStyle = matchAndroid;
            log("input " + match.getName() + " = " + matchName);
            log(androidStyle ? "Bluetooth axis map" : "USB axis map");
        }
        return match;
    }

    private void grab(FileInputStream in) throws IOException {
        if (ioctlGrab(in)) {
            log("Grabbed the controller. eFootball will see only translated commands.");
            return;
        }
        throw new IOException("Could not grab the controller. Refusing to inject, because the game would also see the raw pad.");
    }

    private boolean ioctlGrab(FileInputStream in) {
        exemptHiddenApi();
        for (Method method : Os.class.getDeclaredMethods()) {
            if (!method.getName().toLowerCase().contains("ioctl")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            StringBuilder kinds = new StringBuilder();
            for (Class<?> param : params) {
                if (kinds.length() > 0) {
                    kinds.append(", ");
                }
                kinds.append(param.getSimpleName());
            }
            log("ioctl candidate " + method.getName() + " (" + kinds + ")");
            if (!"ioctlInt".equals(method.getName())
                    || params.length != 3
                    || params[0] != FileDescriptor.class
                    || params[1] != int.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object third = params[2] == int.class
                        ? Integer.valueOf(1)
                        : params[2].getDeclaredConstructor(int.class).newInstance(1);
                method.invoke(null, in.getFD(), Integer.valueOf(EVIOCGRAB), third);
                return true;
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log("ioctl failed: " + cause);
            }
        }
        return nativeGrab(in);
    }

    private static boolean nativeReady;

    private static void loadNative() {
        if (!nativeReady) {
            System.load("/data/local/tmp/libpadlayer.so");
            nativeReady = true;
        }
    }

    private static boolean nativeGrab(FileInputStream in) {
        try {
            loadNative();
            int fd = rawFd(in);
            int rc = nativeGrabFd(fd);
            if (rc == 0) {
                return true;
            }
            log("native grab failed: " + rc);
        } catch (Throwable e) {
            log("native grab failed: " + e);
        }
        return false;
    }

    private static int rawFd(FileInputStream in) throws Exception {
        FileDescriptor owned = in.getFD();
        for (String fieldName : new String[]{"descriptor", "fd"}) {
            try {
                java.lang.reflect.Field field = FileDescriptor.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getInt(owned);
            } catch (NoSuchFieldException ignored) {
                // The other field name is the one this runtime uses.
            }
        }
        throw new IOException("FileDescriptor has no integer field");
    }

    private static native int nativeGrabFd(int fd);

    private static native int nativeOpenVirtual();

    private static native int nativePush(float lx, float ly, float rx, float ry, float lt, float rt,
            int hatX, int hatY, int buttons);

    private boolean pushLogged;

    private void silencePhysical() {
        try {
            Method disable = inputManager.getClass().getDeclaredMethod("disableInputDevice", int.class);
            disable.setAccessible(true);
            disable.invoke(inputManager, deviceId);
            log("Silenced the physical pad so the match follows the translated one");
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log("Physical pad stayed visible: " + cause);
        }
    }

    private void publish(Output out) {
        if (out.action != null) {
            log(out.action);
        }
        int buttons = 0;
        if (out.a) buttons |= 1;
        if (out.b) buttons |= 2;
        if (out.x) buttons |= 4;
        if (out.y) buttons |= 8;
        if (out.lb) buttons |= 16;
        if (out.rb) buttons |= 32;
        if (out.start) buttons |= 64;
        if (out.l3) buttons |= 128;
        int rc = nativePush(out.lx, out.ly, out.rx, out.ry, out.lt, out.rt, 0, out.hatY, buttons);
        if (rc != 0 && !pushLogged) {
            pushLogged = true;
            log("push failed: " + rc);
        }
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            log("hidden api exemptions set");
        } catch (Exception e) {
            log("hidden api exemption failed: " + e);
        }
    }

    private void readLoop(FileInputStream in) {
        byte[] buf = new byte[SIZE];
        try {
            while (true) {
                int got = 0;
                while (got < SIZE) {
                    int n = in.read(buf, got, SIZE - got);
                    if (n < 0) {
                        return;
                    }
                    got += n;
                }
                ByteBuffer view = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                int type = Short.toUnsignedInt(view.getShort(16));
                int code = Short.toUnsignedInt(view.getShort(18));
                int value = view.getInt(20);
                if (eventsSeen < 4 && type != 0 && type != EV_KEY && type != EV_ABS && type != 4) {
                    log("unexpected event type " + type + " code " + code + " value " + value);
                }
                eventsSeen++;
                if (type == EV_ABS) {
                    if (code < axisLogged.length && !axisLogged[code]) {
                        axisLogged[code] = true;
                        log("axis " + code + " first value " + value);
                    }
                    onAbs(code, value);
                } else if (type == EV_KEY) {
                    onKey(code, value != 0);
                }
            }
        } catch (IOException e) {
            log("Reader stopped: " + e.getMessage());
        }
    }

    private void onAbs(int code, int value) {
        synchronized (lock) {
            if (code == ABS_HAT0X) {
                pad.dpadX = Integer.signum(value);
                return;
            }
            if (code == ABS_HAT0Y) {
                pad.dpadY = Integer.signum(value);
                return;
            }
            if (androidStyle) {
                if (code == ABS_X) {
                    pad.lx = stick(code, value);
                } else if (code == ABS_Y) {
                    pad.ly = -stick(code, value);
                } else if (code == ABS_Z) {
                    pad.rx = stick(code, value);
                } else if (code == ABS_RZ) {
                    pad.ry = -stick(code, value);
                } else if (code == ABS_GAS) {
                    pad.rt = trigger(value);
                } else if (code == ABS_BRAKE) {
                    pad.lt = trigger(value);
                }
            } else if (code == ABS_X) {
                pad.lx = stick(code, value);
            } else if (code == ABS_Y) {
                pad.ly = -stick(code, value);
            } else if (code == ABS_RX) {
                pad.rx = stick(code, value);
            } else if (code == ABS_RY) {
                pad.ry = -stick(code, value);
            } else if (code == ABS_Z) {
                pad.lt = trigger(value);
            } else if (code == ABS_RZ) {
                pad.rt = trigger(value);
            }
        }
    }

    private void onKey(int code, boolean down) {
        synchronized (lock) {
            if (seenKeys.add(code)) {
                log("key " + code + (down ? " down" : " up"));
            }
            switch (code) {
                case BTN_SOUTH:
                    pad.a = down;
                    break;
                case BTN_EAST:
                    pad.b = down;
                    break;
                case BTN_WEST:
                    pad.x = down;
                    break;
                case BTN_NORTH:
                    pad.y = down;
                    break;
                case BTN_TL:
                    pad.lb = down;
                    break;
                case BTN_TR:
                    pad.rb = down;
                    break;
                case BTN_THUMBL:
                    pad.l3 = down;
                    break;
                case BTN_THUMBR:
                    pad.r3 = down;
                    break;
                case BTN_START:
                    pad.start = down;
                    break;
                default:
                    break;
            }
        }
    }

    private float stick(int code, int raw) {
        if (code >= 0 && code < axisReady.length && !axisReady[code]) {
            axisCenter[code] = raw > 10000 ? 32768 : 0;
            axisReady[code] = true;
            log("axis " + code + " center " + axisCenter[code]);
        }
        int center = code >= 0 && code < axisCenter.length ? axisCenter[code] : 0;
        return clamp((raw - center) / 32767f);
    }

    private float trigger(int raw) {
        if (raw < 0) {
            raw = 0;
        }
        float scale = raw > 1023 ? 65535f : 1023f;
        return clamp(raw / scale);
    }

    private void inject(Output out) {
        if (out.action != null) {
            log(out.action);
        }
        long now = SystemClock.uptimeMillis();
        sendKey(KeyEvent.KEYCODE_BUTTON_A, previous.a, out.a, now);
        sendKey(KeyEvent.KEYCODE_BUTTON_B, previous.b, out.b, now);
        sendKey(KeyEvent.KEYCODE_BUTTON_X, previous.x, out.x, now);
        sendKey(KeyEvent.KEYCODE_BUTTON_Y, previous.y, out.y, now);
        sendKey(KeyEvent.KEYCODE_BUTTON_L1, previous.lb, out.lb, now);
        sendKey(KeyEvent.KEYCODE_BUTTON_R1, previous.rb, out.rb, now);
        sendKey(KeyEvent.KEYCODE_BUTTON_START, previous.start, out.start, now);
        sendMotion(out, now);
    }

    private void sendKey(int keyCode, boolean wasDown, boolean down, long now) {
        if (wasDown == down) {
            return;
        }
        KeyEvent event = new KeyEvent(
                now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD);
        dispatch(event);
    }

    private void sendMotion(Output out, long now) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].setAxisValue(MotionEvent.AXIS_X, out.lx);
        coords[0].setAxisValue(MotionEvent.AXIS_Y, -out.ly);
        coords[0].setAxisValue(MotionEvent.AXIS_Z, out.rx);
        coords[0].setAxisValue(MotionEvent.AXIS_RZ, -out.ry);
        coords[0].setAxisValue(MotionEvent.AXIS_LTRIGGER, out.lt);
        coords[0].setAxisValue(MotionEvent.AXIS_RTRIGGER, out.rt);
        coords[0].setAxisValue(MotionEvent.AXIS_BRAKE, out.lt);
        coords[0].setAxisValue(MotionEvent.AXIS_GAS, out.rt);
        int source = InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD;
        MotionEvent event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_MOVE,
                1, props, coords,
                0, 0, 1f, 1f, deviceId, 0, source, 0);
        dispatch(event);
        event.recycle();
    }

    private void dispatch(InputEvent event) {
        try {
            if (injectMethod.getParameterCount() == 2) {
                injectMethod.invoke(inputManager, event, 0);
            } else {
                injectMethod.invoke(inputManager, event, 0, 0);
            }
        } catch (ReflectiveOperationException e) {
            log("inject failed: " + e.getCause());
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static void log(String message) {
        System.out.println("Padlayer: " + message);
        System.out.flush();
        Log.i("Padlayer", message);
    }
}
