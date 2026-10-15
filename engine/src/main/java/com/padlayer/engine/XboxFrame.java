package com.padlayer.engine;

/** Xbox Wireless Controller report. Engine +Y is up. The device axis grows downward. */
public final class XboxFrame implements DeviceProfile {
    @Override
    public String name() {
        return "Xbox Wireless Controller";
    }

    @Override
    public AxisReport encode(Output frame) {
        int buttons = 0;
        if (frame.a) buttons |= 1;
        if (frame.b) buttons |= 2;
        if (frame.x) buttons |= 4;
        if (frame.y) buttons |= 8;
        if (frame.lb) buttons |= 16;
        if (frame.rb) buttons |= 32;
        if (frame.start) buttons |= 64;
        if (frame.l3) buttons |= 128;
        return new AxisReport(
                stick(frame.lx),
                stick(-frame.ly),
                stick(frame.rx),
                stick(-frame.ry),
                trigger(frame.lt),
                trigger(frame.rt),
                buttons,
                -clampHat(frame.hatY));
    }

    private static int stick(float value) {
        float clamped = Math.max(-1f, Math.min(1f, value));
        return Math.round((clamped + 1f) * 0.5f * 65535f);
    }

    private static int trigger(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        return Math.round(clamped * 1023f);
    }

    private static int clampHat(int value) {
        if (value < 0) return -1;
        if (value > 0) return 1;
        return 0;
    }
}
