package com.padlayer.engine;

/** One physical controller report. Stick axes are 0..65535. Triggers are 0..1023. */
public final class AxisReport {
    public final int lx;
    public final int ly;
    public final int rx;
    public final int ry;
    public final int lt;
    public final int rt;
    public final int buttons;
    /** Linux hat Y: -1 is up. */
    public final int hat;

    public AxisReport(int lx, int ly, int rx, int ry, int lt, int rt, int buttons, int hat) {
        this.lx = lx;
        this.ly = ly;
        this.rx = rx;
        this.ry = ry;
        this.lt = lt;
        this.rt = rt;
        this.buttons = buttons;
        this.hat = hat;
    }
}
