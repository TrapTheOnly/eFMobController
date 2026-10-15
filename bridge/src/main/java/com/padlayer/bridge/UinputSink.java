package com.padlayer.bridge;

import com.padlayer.engine.AxisReport;
import com.padlayer.engine.Output;
import com.padlayer.engine.XboxFrame;

/**
 * Writes one frame to the virtual pad. Button bits follow {@link XboxFrame}.
 * Axis scaling stays in the native writer.
 */
final class UinputSink {
    private final XboxFrame profile = new XboxFrame();

    int publish(Output out) {
        AxisReport report = profile.encode(out);
        return BridgeMain.nativePush(
                out.lx, out.ly, out.rx, out.ry, out.lt, out.rt, 0, out.hatY, report.buttons);
    }
}
