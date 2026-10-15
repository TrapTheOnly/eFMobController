package com.padlayer.engine;

/** Maps a frame onto one physical controller. */
public interface DeviceProfile {
    String name();

    AxisReport encode(Output frame);
}
