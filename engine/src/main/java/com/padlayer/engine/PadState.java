package com.padlayer.engine;

/**
 * One sample from the Xbox pad. Stick axes are -1..1 with positive Y meaning
 * stick up. Triggers are 0..1. Buttons are held levels, not edges.
 */
public final class PadState {
    public float lx;
    public float ly;
    public float rx;
    public float ry;
    public float rt;
    public float lt;
    public boolean a;
    public boolean b;
    public boolean x;
    public boolean y;
    public boolean lb;
    public boolean rb;
    public boolean l3;
    public boolean r3;
    public boolean start;
    /** -1 left, +1 right. */
    public int dpadX;
    /** -1 up, +1 down. */
    public int dpadY;
    public long timeMs;

    public PadState at(long timeMs) {
        this.timeMs = timeMs;
        return this;
    }
}
