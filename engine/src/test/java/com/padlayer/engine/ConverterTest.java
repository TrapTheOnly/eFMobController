package com.padlayer.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConverterTest {
    private final Converter converter = new Converter();
    private final PadState pad = new PadState();

    @Test
    public void leftStickMovesAndClickDoesNothing() {
        pad.lx = 0.6f;
        pad.ly = -0.4f;
        pad.timeMs = 10;
        Output out = converter.step(pad);
        assertEquals(0.6f, out.lx, 0.01f);
        assertEquals(-0.4f, out.ly, 0.01f);
        assertEquals(0f, out.rx, 0.001f);
        assertEquals(false, out.l3);
        assertNull(out.action);
    }

    @Test
    public void rightStickDoesNotPassThrough() {
        pad.rx = 0.2f;
        pad.ry = 0.2f;
        pad.timeMs = 10;
        Output out = converter.step(pad);
        assertEquals(0f, out.rx, 0.001f);
        assertNull(out.action);
    }

    @Test
    public void rightStickFeintPassesThrough() {
        pad.rx = 0.9f;
        pad.ry = 0.2f;
        pad.timeMs = 10;
        Output out = converter.step(pad);
        assertEquals(0.9f, out.rx, 0.01f);
        assertEquals(0.2f, out.ry, 0.01f);
    }

    @Test
    public void spinIsMarseilleAndSpinWithTriggerIsRainbow() {
        assertEquals("Marseille turn", runCircle(0f));
        Converter powered = new Converter();
        assertEquals("Rainbow", runCircle(powered, 0.9f));
    }

    @Test
    public void upDownFollowsTheShoulderButtons() {
        assertEquals("Double touch", runUpDown(false, false));
        assertEquals("Draw and open", runUpDown(false, true));
        assertEquals("Draw and close", runUpDown(true, false));
    }

    @Test
    public void sideFlickIsABodyFeintUntilTheOppositeSideMakesAFlipFlap() {
        assertEquals("Body feint left", runSide(-1f, false, false, true));
        Converter flip = new Converter();
        assertEquals("Flip flap", runFlip(flip));
    }

    @Test
    public void bumpersTurnASideFlickIntoCutMoves() {
        assertEquals("L feint left", runSide(-1f, true, false, false));
        assertEquals("Chop turn", runSide(-1f, true, true, false));
    }

    @Test
    public void backFlickWithRbIsScotchAndForwardClickIsStepOver() {
        assertEquals("Scotch move", runBack());
        pad.ry = 0.8f;
        pad.r3 = true;
        pad.timeMs = 50;
        assertEquals("Step-over", converter.step(pad).action);
    }

    @Test
    public void halfSpinForwardIsScissors() {
        String action = null;
        for (int i = 0; i <= 18; i++) {
            double ang = Math.PI - (Math.PI * i / 18.0);
            action = keep(action, put((float) Math.cos(ang), (float) Math.sin(ang), i * 20L));
        }
        action = keep(action, put(0f, 0f, 500));
        action = keep(action, put(0f, 0f, 600));
        assertEquals("Scissors", action);
    }

    @Test
    public void faceButtonsStayNormalUntilTheSecondPress() {
        pad.x = true;
        pad.timeMs = 20;
        Output early = converter.step(pad);
        assertEquals(false, early.x);

        pad.timeMs = 180;
        Output shot = converter.step(pad);
        assertTrue(shot.x);
        assertEquals(0f, shot.rt, 0.001f);

        pad.rt = 1f;
        pad.timeMs = 220;
        Output sprintShot = converter.step(pad);
        assertTrue(sprintShot.x);
        assertEquals(1f, sprintShot.rt, 0.001f);

        pad.x = false;
        pad.rt = 0f;
        pad.timeMs = 260;
        converter.step(pad);

        pad.lb = true;
        pad.x = true;
        pad.rt = 1f;
        pad.timeMs = 280;
        Output chip = converter.step(pad);
        assertTrue(chip.x);
        assertTrue(chip.lb);
        assertEquals(0f, chip.rt, 0.001f);
        assertEquals(null, chip.action);

        pad.x = false;
        pad.timeMs = 320;
        converter.step(pad);
        pad.x = true;
        pad.timeMs = 360;
        Output second = converter.step(pad);
        assertTrue(second.x);
        assertTrue(second.lb);
        assertEquals(0f, second.rt, 0.001f);
        assertEquals(null, second.action);
    }

    @Test
    public void sombreroHoldsTheRightStickAndDoesNotBecomeAFeint() {
        pad.l3 = true;
        pad.rx = 0.9f;
        pad.ry = 0.4f;
        pad.timeMs = 10;
        Output held = converter.step(pad);
        assertTrue(held.l3);
        assertEquals(0.9f, held.rx, 0.01f);
        assertEquals(0.4f, held.ry, 0.01f);

        pad.l3 = false;
        pad.rx = 0f;
        pad.ry = 0f;
        pad.timeMs = 200;
        assertNull(converter.step(pad).action);
    }

    @Test
    public void playerSwitchCancelsADefensiveRightStickFlick() {
        pad.rx = -0.95f;
        pad.timeMs = 0;
        converter.step(pad);
        pad.lb = true;
        pad.timeMs = 30;
        Output switched = converter.step(pad);
        assertTrue(switched.lb);

        pad.rx = 0f;
        pad.lb = false;
        pad.timeMs = 200;
        assertNull(converter.step(pad).action);
    }

    @Test
    public void dpadLeftRightStepsTheAttackLevelAndUpDownCallTeammates() {
        pad.dpadX = 1;
        pad.timeMs = 0;
        assertEquals(1, converter.step(pad).hatY);
        pad.timeMs = 80;
        assertEquals(0, converter.step(pad).hatY);
        pad.timeMs = 160;
        assertEquals(1, converter.step(pad).hatY);

        Converter left = new Converter();
        PadState defend = new PadState();
        defend.dpadX = -1;
        defend.timeMs = 0;
        assertEquals(-1, left.step(defend).hatY);

        Converter down = new Converter();
        PadState sample = new PadState();
        sample.dpadY = -1;
        sample.timeMs = 0;
        Output press = down.step(sample);
        assertTrue(press.rb);
        assertEquals(false, press.lb);

        sample.dpadY = 1;
        sample.timeMs = 40;
        Output cursor = down.step(sample);
        assertTrue(cursor.lb);
        assertEquals(false, cursor.rb);
    }

    @Test
    public void secondPressHoldIsTheStunningKick() {
        Converter local = new Converter();
        PadState sample = new PadState();
        sample.lb = true;
        sample.y = true;
        sample.timeMs = 0;
        local.step(sample);
        sample.y = false;
        sample.timeMs = 50;
        local.step(sample);
        sample.y = true;
        sample.timeMs = 120;
        Output start = local.step(sample);
        assertEquals("Stunning chipped through ball", start.action);
        assertTrue(start.y);
        assertTrue(start.lb);
        assertEquals(1f, start.rt, 0.001f);

        sample.timeMs = 300;
        Output held = local.step(sample);
        assertTrue(held.y);
        assertEquals(1f, held.rt, 0.001f);

        sample.y = false;
        sample.timeMs = 340;
        Output released = local.step(sample);
        assertEquals(false, released.y);
        assertEquals(0f, released.rt, 0.001f);
    }

    @Test
    public void bothBumpersCancelAShotIntoAFakeShot() {
        Converter local = new Converter();
        PadState sample = new PadState();
        sample.x = true;
        sample.timeMs = 0;
        local.step(sample);
        sample.timeMs = 180;
        local.step(sample);
        sample.lb = true;
        sample.rb = true;
        sample.timeMs = 220;
        Output cancel = local.step(sample);
        assertEquals("Fake shot", cancel.action);
        assertEquals(false, cancel.x);
        assertTrue(cancel.rb);
        assertEquals(1f, cancel.rt, 0.001f);
    }

    @Test
    public void backFlickStepsOnTheBallAndForwardFlickWhileSprintingIsSharpTouch() {
        Converter local = new Converter();
        String stepped = null;
        stepped = keep(stepped, nudge(local, 0f, -0.95f, 0, false, false));
        stepped = keep(stepped, nudge(local, 0f, 0f, 40, false, false));
        stepped = keep(stepped, nudge(local, 0f, 0f, 150, false, false));
        assertEquals("Step on the ball", stepped);

        Converter sprint = new Converter();
        String sharp = null;
        sharp = keep(sharp, nudge(sprint, 0f, 0.95f, 0, false, false, 1f));
        sharp = keep(sharp, nudge(sprint, 0f, 0f, 40, false, false, 1f));
        sharp = keep(sharp, nudge(sprint, 0f, 0f, 150, false, false, 1f));
        assertEquals("Sharp touch", sharp);
    }

    private String runCircle(float trigger) {
        return runCircle(converter, trigger);
    }

    private String runCircle(Converter target, float trigger) {
        String action = null;
        PadState sample = new PadState();
        sample.rt = trigger;
        for (int i = 0; i <= 36; i++) {
            double ang = Math.PI * 2 * i / 36.0;
            sample.rx = (float) Math.cos(ang);
            sample.ry = (float) Math.sin(ang);
            sample.timeMs = i * 20L;
            Output out = target.step(sample);
            if (out.action != null) {
                action = out.action;
            }
        }
        return action;
    }

    private String runUpDown(boolean lb, boolean rb) {
        Converter local = new Converter();
        String action = null;
        float[] ys = {0.95f, 0.4f, -0.4f, -0.95f, 0f, 0f};
        long[] ts = {0, 40, 80, 120, 160, 260};
        for (int i = 0; i < ys.length; i++) {
            PadState sample = new PadState();
            sample.ry = ys[i];
            sample.lb = lb;
            sample.rb = rb;
            sample.timeMs = ts[i];
            Output out = local.step(sample);
            if (out.action != null) {
                action = out.action;
            }
        }
        return action;
    }

    private String runSide(float sign, boolean lb, boolean rb, boolean wait) {
        Converter local = new Converter();
        String action = null;
        action = keep(action, nudge(local, sign * 0.95f, 0f, 0, lb, rb));
        action = keep(action, nudge(local, 0f, 0f, 40, lb, rb));
        action = keep(action, nudge(local, 0f, 0f, 140, lb, rb));
        if (wait) {
            action = keep(action, nudge(local, 0f, 0f, 420, false, false));
        }
        return action;
    }

    private String runFlip(Converter local) {
        String action = null;
        action = keep(action, nudge(local, -0.95f, 0f, 0, false, false));
        action = keep(action, nudge(local, 0f, 0f, 40, false, false));
        action = keep(action, nudge(local, 0f, 0f, 140, false, false));
        action = keep(action, nudge(local, 0.95f, 0f, 180, false, false));
        action = keep(action, nudge(local, 0f, 0f, 220, false, false));
        action = keep(action, nudge(local, 0f, 0f, 320, false, false));
        return action;
    }

    private String runBack() {
        Converter local = new Converter();
        String action = null;
        action = keep(action, nudge(local, 0f, -0.95f, 0, false, true));
        action = keep(action, nudge(local, 0f, 0f, 40, false, true));
        action = keep(action, nudge(local, 0f, 0f, 150, false, true));
        return action;
    }

    private String put(float rx, float ry, long time) {
        pad.rx = rx;
        pad.ry = ry;
        pad.timeMs = time;
        return converter.step(pad).action;
    }

    private static String nudge(Converter local, float rx, float ry, long time, boolean lb, boolean rb) {
        return nudge(local, rx, ry, time, lb, rb, 0f);
    }

    private static String nudge(Converter local, float rx, float ry, long time, boolean lb, boolean rb, float rt) {
        PadState sample = new PadState();
        sample.rx = rx;
        sample.ry = ry;
        sample.lb = lb;
        sample.rb = rb;
        sample.rt = rt;
        sample.lx = rt > 0f ? 0.8f : 0f;
        sample.timeMs = time;
        return local.step(sample).action;
    }

    private static String keep(String current, String next) {
        return next == null ? current : next;
    }
}
