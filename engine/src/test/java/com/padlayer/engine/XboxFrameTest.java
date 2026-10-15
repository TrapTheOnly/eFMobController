package com.padlayer.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XboxFrameTest {
    private final DeviceProfile profile = new XboxFrame();

    @Test
    public void neutralFrameIsCenteredAndClear() {
        AxisReport report = profile.encode(new Output());
        assertEquals(32768, report.lx);
        assertEquals(32768, report.ly);
        assertEquals(32768, report.rx);
        assertEquals(32768, report.ry);
        assertEquals(0, report.lt);
        assertEquals(0, report.rt);
        assertEquals(0, report.buttons);
        assertEquals(0, report.hat);
    }

    @Test
    public void faceButtonsUseTheXboxBitOrder() {
        Output frame = new Output();
        frame.a = true;
        assertEquals(1, profile.encode(frame).buttons);
        frame.a = false;
        frame.b = true;
        assertEquals(2, profile.encode(frame).buttons);
        frame.b = false;
        frame.x = true;
        assertEquals(4, profile.encode(frame).buttons);
        frame.x = false;
        frame.y = true;
        assertEquals(8, profile.encode(frame).buttons);
        frame.y = false;
        frame.lb = true;
        assertEquals(16, profile.encode(frame).buttons);
        frame.lb = false;
        frame.rb = true;
        assertEquals(32, profile.encode(frame).buttons);
        frame.rb = false;
        frame.start = true;
        assertEquals(64, profile.encode(frame).buttons);
        frame.start = false;
        frame.l3 = true;
        assertEquals(128, profile.encode(frame).buttons);
    }

    @Test
    public void fullSprintTriggerFillsTheAxis() {
        Output frame = new Output();
        frame.rt = 1f;
        assertEquals(1023, profile.encode(frame).rt);
    }

    @Test
    public void engineUpEncodesAsAndroidDown() {
        Output frame = new Output();
        frame.ly = 1f;
        frame.ry = 1f;
        AxisReport report = profile.encode(frame);
        assertEquals(0, report.ly);
        assertEquals(0, report.ry);
    }

    @Test
    public void attackLevelHatIsLinuxUp() {
        Output frame = new Output();
        frame.hatY = 1;
        assertEquals(-1, profile.encode(frame).hat);
    }

    @Test
    public void profileIsTheXboxWirelessController() {
        assertEquals("Xbox Wireless Controller", profile.name());
    }

    @Test
    public void chipFrameDoesNotAddTheSprintTrigger() {
        Output frame = new Output();
        frame.x = true;
        frame.lb = true;
        frame.rt = 0f;
        AxisReport report = profile.encode(frame);
        assertEquals(0, report.rt);
        assertEquals(4 | 16, report.buttons);
    }
}
