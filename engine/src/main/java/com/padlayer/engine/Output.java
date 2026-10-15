package com.padlayer.engine;

/**
 * What the game should see for this frame. Sticks use the same space as
 * {@link PadState}. Button fields are held levels.
 */
public final class Output {
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
    public boolean start;
    /** -1 down, +1 up. Left and right are sent as the game's vertical level command. */
    public int hatY;
    /** Set only on the frame a new command is recognized. */
    public String action;
}
