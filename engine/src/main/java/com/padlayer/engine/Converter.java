package com.padlayer.engine;

/**
 * Turns the Series-pad gesture grammar into the controller frames eFootball
 * already understands. The game has no external feint call. Official commands
 * are stick and button chords on the Android gamepad stream, so each gesture
 * becomes that chord.
 *
 * Left stick is movement and aim, passed through, except while a feint chord
 * briefly replaces it with the direction the game itself requires. Clicking
 * it does nothing. A finished right-stick gesture becomes that official
 * chord, aimed along the left stick, or along the attack direction when the
 * left stick is centered.
 */
public final class Converter {
    public enum AttackDirection {
        UP,
        RIGHT
    }

    private static final float DEADZONE = 0.12f;
    private static final float OUT = 0.45f;
    private static final float REST = 0.22f;
    private static final float SPIN = 4.4f;
    private static final long REST_MS = 80;
    private static final long FLIP_WAIT_MS = 240;
    private static final long COOLDOWN_MS = 280;
    private static final long ARM_MS = 140;
    private static final long GAP_MS = 190;
    private static final long LEVEL_ON_MS = 70;
    private static final long LEVEL_GAP_MS = 80;

    private AttackDirection attack = AttackDirection.UP;

    private boolean tracking;
    private boolean lbFromStart;
    private boolean rbFromStart;
    private long restSince = -1;
    private long gestureStart;
    private float accum;
    private boolean haveAngle;
    private float lastAngle;
    private float maxY = -2f;
    private float minY = 2f;
    private float maxAbsX;
    private float sumX;
    private float sumY;
    private int samples;
    private boolean spinFired;

    private int pendingSide;
    private long pendingAt = -1;

    private boolean prevR3;
    private boolean waitForRest;
    private long lastFeintAt = -1_000_000L;

    private Chord chord;
    private long chordAt;
    private int chordIndex;
    private long chordSegAt;
    private String pendingAction;

    private final Kick kickA = new Kick();
    private final Kick kickB = new Kick();
    private final Kick kickX = new Kick();
    private final Kick kickY = new Kick();
    private boolean prevA;
    private boolean prevB;
    private boolean prevX;
    private boolean prevY;
    private boolean prevCancel;
    private boolean lastKickWasShot;
    private long lastKickAt = -1_000_000L;
    private long cancelUntil;
    private boolean finesseOn;
    private boolean rtWasDown;
    private long rtDownAt;
    private long rtUpAt = -1_000_000L;
    private long rtHeldFor;
    private long sharpStart = -1_000_000L;
    private int levelPhase;
    private int levelSign;
    private long levelPhaseAt;
    private boolean prevPadLeft;
    private boolean prevPadRight;

    public void setAttackDirection(AttackDirection attack) {
        this.attack = attack == null ? AttackDirection.UP : attack;
    }

    public Output step(PadState in) {
        Output out = new Output();
        float mag = mag(in.rx, in.ry);
        if (in.l3) {
            tracking = false;
            restSince = -1;
            pendingSide = 0;
            pendingAt = -1;
            if (mag >= OUT) {
                waitForRest = true;
            }
        } else if (tracking && !lbFromStart && !rbFromStart && (in.lb || in.dpadY > 0)) {
            tracking = false;
            restSince = -1;
        } else if (waitForRest) {
            if (mag < REST) {
                waitForRest = false;
            }
        } else if (!tracking) {
            if (mag >= OUT) {
                begin(in);
            }
        } else if (mag >= REST) {
            restSince = -1;
            sample(in);
        } else if (restSince < 0) {
            restSince = in.timeMs;
        } else if (in.timeMs - restSince >= REST_MS) {
            finish(in);
        }

        flushPendingSide(in, false);
        if (!prevR3 && in.r3) {
            onR3(in);
        }
        prevR3 = in.r3;

        applyChord(in, out);
        applyDpad(in, out);
        if (pendingAction != null) {
            out.action = pendingAction;
            pendingAction = null;
        }
        return out;
    }

    private void begin(PadState in) {
        tracking = true;
        lbFromStart = in.lb;
        rbFromStart = in.rb;
        restSince = -1;
        gestureStart = in.timeMs;
        accum = 0f;
        haveAngle = false;
        maxY = -2f;
        minY = 2f;
        maxAbsX = 0f;
        sumX = 0f;
        sumY = 0f;
        samples = 0;
        spinFired = false;
        sample(in);
    }

    private void sample(PadState in) {
        float mag = mag(in.rx, in.ry);
        if (mag < 0.4f) {
            return;
        }
        float angle = (float) Math.atan2(in.ry, in.rx);
        if (haveAngle) {
            float delta = angle - lastAngle;
            while (delta > Math.PI) {
                delta -= (float) (Math.PI * 2);
            }
            while (delta < -Math.PI) {
                delta += (float) (Math.PI * 2);
            }
            accum += delta;
        }
        haveAngle = true;
        lastAngle = angle;
        maxY = Math.max(maxY, in.ry);
        minY = Math.min(minY, in.ry);
        maxAbsX = Math.max(maxAbsX, Math.abs(in.rx));
        sumX += in.rx;
        sumY += in.ry;
        samples++;
        if (!spinFired && Math.abs(accum) >= SPIN && maxAbsX > 0.45f && maxY > 0.45f && minY < -0.45f) {
            spinFired = true;
            if (in.rt >= 0.55f) {
                fire("Rainbow", Chords.RAINBOW, in);
            } else {
                fire("Marseille turn", Chords.MARSEILLE, in);
            }
        }
    }

    private void finish(PadState in) {
        tracking = false;
        restSince = -1;
        if (spinFired || samples == 0) {
            return;
        }
        long dur = in.timeMs - gestureStart;
        float meanY = sumY / samples;
        boolean upDown = maxY > 0.65f && minY < -0.65f && maxAbsX < 0.55f && dur < 420;
        boolean side = maxAbsX > 0.72f && maxY < 0.45f && minY > -0.45f && dur < 280;
        boolean back = minY < -0.72f && maxY < 0.35f && maxAbsX < 0.45f && dur < 280;
        boolean half = Math.abs(accum) >= 2.3f && Math.abs(accum) <= 4.6f;

        boolean forwardFlick = maxY > 0.72f && minY > -0.4f && maxAbsX < 0.5f && dur < 280;
        if (forwardFlick && in.rt >= 0.55f && !in.lb && !in.rb) {
            startSharp(in.timeMs);
            return;
        }
        if (forwardFlick && in.lb && in.rb) {
            fire("Chop turn", Chords.chop(0, 1), in);
            return;
        }
        if (upDown) {
            if (in.lb && !in.rb) {
                fire("Draw and close", Chords.DRAW_CLOSE, in);
            } else if (in.rb && !in.lb) {
                fire("Draw and open", Chords.DRAW_OPEN, in);
            } else if (!in.lb && !in.rb && in.rt < 0.55f) {
                fire("Double touch", Chords.DOUBLE_TOUCH, in);
            }
            return;
        }
        if (half && meanY > 0.25f && !in.lb && !in.rb && in.rt < 0.55f && maxAbsX > 0.45f) {
            fire("Scissors", Chords.SCISSORS, in);
            return;
        }
        if (side) {
            int sideSign = sumX >= 0f ? 1 : -1;
            if (in.lb && in.rb) {
                fire("Chop turn", Chords.chop(sideSign, 0f), in);
                return;
            }
            if (in.lb) {
                fire(sideSign < 0 ? "L feint left" : "L feint right", Chords.lFeint(sideSign), in);
                return;
            }
            if (!in.rb && in.rt < 0.55f) {
                queueSide(sideSign, in.timeMs);
            }
            return;
        }
        if (back && in.rb && !in.lb) {
            fire("Scotch move", Chords.SCOTCH, in);
            return;
        }
        if (back && !in.lb && !in.rb && in.rt < 0.55f) {
            fire("Step on the ball", Chords.STEP_ON, in);
        }
    }

    private void queueSide(int sideSign, long timeMs) {
        if (pendingSide != 0 && pendingSide != sideSign && timeMs - pendingAt <= FLIP_WAIT_MS + 40) {
            pendingSide = 0;
            pendingAt = -1;
            fire("Flip flap", Chords.flipFlap(sideSign), timeMs);
            return;
        }
        if (pendingSide != 0) {
            emitPendingSide(timeMs);
        }
        pendingSide = sideSign;
        pendingAt = timeMs;
    }

    private void flushPendingSide(PadState in, boolean force) {
        if (pendingSide == 0) {
            return;
        }
        if (force || in.timeMs - pendingAt >= FLIP_WAIT_MS) {
            emitPendingSide(in.timeMs);
        }
    }

    private void emitPendingSide(long timeMs) {
        int sideSign = pendingSide;
        pendingSide = 0;
        pendingAt = -1;
        fire(sideSign < 0 ? "Body feint left" : "Body feint right", Chords.body(sideSign), timeMs);
    }

    private void onR3(PadState in) {
        if (in.ry > 0.55f && Math.abs(in.rx) < 0.45f && !in.lb && !in.rb) {
            fire("Step-over", Chords.STEP_OVER, in);
        }
    }

    private void fire(String name, Chord next, PadState in) {
        fire(name, next, in.timeMs);
    }

    private void fire(String name, Chord next, long timeMs) {
        if (timeMs - lastFeintAt < COOLDOWN_MS) {
            return;
        }
        lastFeintAt = timeMs;
        chord = next;
        chordAt = timeMs;
        chordIndex = 0;
        chordSegAt = timeMs;
        pendingAction = name;
        tracking = false;
        spinFired = true;
        waitForRest = true;
    }

    private void applyChord(PadState in, Output out) {
        out.lx = clamp(dead(in.lx));
        out.ly = clamp(dead(in.ly));
        out.rt = in.rt;
        out.lt = in.lt;
        out.a = in.a;
        out.b = in.b;
        out.x = in.x;
        out.y = in.y;
        out.start = in.start;
        out.l3 = in.l3;

        Seg seg = currentSeg(in.timeMs);
        float stick = mag(in.rx, in.ry);
        boolean drawing = tracking || stick >= OUT || seg != null;
        boolean hideRaw = seg == null && (in.lb || in.rb) && drawing && in.rt < 0.55f;
        out.lb = in.lb && !(tracking && lbFromStart) && seg == null;
        out.rb = in.rb && !(tracking && rbFromStart) && seg == null;
        if (in.lt >= 0.35f) {
            out.lt = 1f;
        }
        if (seg == null) {
            if (in.l3 || (!hideRaw && (tracking || stick >= OUT))) {
                out.rx = in.rx;
                out.ry = in.ry;
            }
            applyKicks(in, out, stick);
            return;
        }
        if (seg.clearRt) {
            out.rt = 0f;
        }
        if (seg.lb) {
            out.lb = true;
        }
        if (seg.rb) {
            out.rb = true;
        }
        float[] feint = toScreen(seg.feintRight, seg.feintForward, in);
        float[] move = seg.screenMove
                ? new float[]{seg.moveRight, seg.moveForward}
                : toScreen(seg.moveRight, seg.moveForward, in);
        out.rx = feint[0];
        out.ry = feint[1];
        if (mag(move[0], move[1]) > 0.05f) {
            out.lx = clamp(move[0]);
            out.ly = clamp(move[1]);
        }
        applyKicks(in, out, stick);
    }

    private void applyKicks(PadState in, Output out, float stick) {
        boolean aRise = in.a && !prevA;
        boolean bRise = in.b && !prevB;
        boolean xRise = in.x && !prevX;
        boolean yRise = in.y && !prevY;
        updateKick(kickA, in.a, in.timeMs, bRise || xRise || yRise, false);
        updateKick(kickB, in.b, in.timeMs, aRise || xRise || yRise, false);
        // LB+X is a chip and RB+X is a controlled shot. Neither is a stunning
        // kick, so the first press is sent as-is and the double-press window
        // stays closed.
        updateKick(kickX, in.x, in.timeMs, aRise || bRise || yRise, in.lb || in.rb);
        updateKick(kickY, in.y, in.timeMs, aRise || bRise || xRise, false);
        prevA = in.a;
        prevB = in.b;
        prevX = in.x;
        prevY = in.y;
        if (in.a || in.b || in.x || in.y) {
            lastKickAt = in.timeMs;
            lastKickWasShot = in.x;
        }

        boolean cancelEdge = in.lb && in.rb && !prevCancel;
        prevCancel = in.lb && in.rb;
        if (cancelEdge && stick < 0.35f && !tracking && in.timeMs - lastKickAt <= 400) {
            cancelUntil = in.timeMs + 130;
            if (pendingAction == null) {
                pendingAction = lastKickWasShot ? "Fake shot" : "Fake pass";
            }
        }
        boolean cancelling = in.timeMs < cancelUntil;

        noteSharp(in);
        boolean finesse = out.lt >= 0.9f && mag(in.lx, in.ly) > 0.25f && !cancelling;
        if (finesse && !finesseOn && pendingAction == null) {
            pendingAction = "Finesse dribble";
        }
        finesseOn = finesse;

        if (cancelling) {
            out.a = false;
            out.b = false;
            out.x = false;
            out.y = false;
            out.lb = false;
            out.rb = true;
            out.rt = 1f;
            return;
        }

        out.a = kickA.emit;
        out.b = kickB.emit;
        out.x = kickX.emit;
        out.y = kickY.emit;
        if (kickA.forceRt || kickB.forceRt || kickX.forceRt || kickY.forceRt) {
            out.rt = 1f;
            if (in.lb) {
                out.lb = true;
            }
            if (in.rb) {
                out.rb = true;
            }
            if ((kickA.beganStun || kickB.beganStun || kickX.beganStun || kickY.beganStun)
                    && pendingAction == null) {
                pendingAction = stunName(in);
            }
        } else if (kickA.emit || kickB.emit || kickX.emit || kickY.emit) {
            if (kickX.emit && (in.lb || in.rb)) {
                out.rt = 0f;
            }
            if (kickX.emit && in.lb) {
                out.lb = true;
            }
            if (kickX.emit && in.rb) {
                out.rb = true;
            }
        } else if (in.timeMs - sharpStart < 160) {
            long age = in.timeMs - sharpStart;
            out.rt = age < 60 || age >= 100 ? 1f : 0f;
        }
    }

    private void applyDpad(PadState in, Output out) {
        boolean left = in.dpadX < 0;
        boolean right = in.dpadX > 0;
        if (levelPhase == 0) {
            if (right && !prevPadRight) {
                beginLevel(1, in.timeMs);
            } else if (left && !prevPadLeft) {
                beginLevel(-1, in.timeMs);
            }
        } else if (in.timeMs - levelPhaseAt >= (levelPhase == 2 ? LEVEL_GAP_MS : LEVEL_ON_MS)) {
            levelPhase++;
            levelPhaseAt = in.timeMs;
            if (levelPhase > 3) {
                levelPhase = 0;
            }
        }
        prevPadLeft = left;
        prevPadRight = right;
        if (levelPhase == 1 || levelPhase == 3) {
            out.hatY = levelSign;
        }
        if (in.dpadY < 0) {
            out.rb = true;
        }
        if (in.dpadY > 0) {
            out.lb = true;
        }
    }

    private void beginLevel(int sign, long timeMs) {
        levelSign = sign;
        levelPhase = 1;
        levelPhaseAt = timeMs;
    }

    private void noteSharp(PadState in) {
        boolean down = in.rt >= 0.75f;
        if (down && !rtWasDown) {
            long gap = in.timeMs - rtUpAt;
            if (rtHeldFor > 0 && rtHeldFor <= 150 && gap >= 0 && gap <= 260 && mag(in.lx, in.ly) > 0.3f) {
                startSharp(in.timeMs);
            }
            rtDownAt = in.timeMs;
        } else if (!down && rtWasDown) {
            rtHeldFor = in.timeMs - rtDownAt;
            rtUpAt = in.timeMs;
        }
        rtWasDown = down;
    }

    private void startSharp(long timeMs) {
        sharpStart = timeMs;
        if (pendingAction == null) {
            pendingAction = "Sharp touch";
        }
    }

    private static String stunName(PadState in) {
        if (in.y && in.lb) {
            return "Stunning chipped through ball";
        }
        if (in.x) {
            return "Stunning shot";
        }
        if (in.y) {
            return "Stunning through ball";
        }
        if (in.b) {
            return "Stunning lofted pass";
        }
        return "Stunning pass";
    }

    private void updateKick(Kick kick, boolean held, long timeMs, boolean otherArrived, boolean direct) {
        kick.emit = false;
        kick.forceRt = false;
        kick.beganStun = false;
        if (direct) {
            kick.phase = held ? 3 : 0;
            kick.emit = held;
            return;
        }
        if (otherArrived && kick.phase == 2) {
            kick.phase = 5;
            kick.replayUntil = timeMs + Math.max(32L, kick.firstDur);
        }
        if (kick.phase == 0) {
            if (held) {
                kick.phase = 1;
                kick.downAt = timeMs;
            }
            return;
        }
        if (kick.phase == 1) {
            if (!held) {
                kick.firstDur = timeMs - kick.downAt;
                if (kick.firstDur <= ARM_MS) {
                    kick.phase = 2;
                    kick.upAt = timeMs;
                } else {
                    kick.phase = 0;
                }
            } else if (timeMs - kick.downAt >= ARM_MS) {
                kick.phase = 3;
                kick.emit = true;
            }
            return;
        }
        if (kick.phase == 2) {
            if (held) {
                kick.phase = 4;
                kick.beganStun = true;
                kick.downAt = timeMs;
                kick.emit = true;
                kick.forceRt = true;
            } else if (timeMs - kick.upAt >= GAP_MS) {
                kick.phase = 5;
                kick.replayUntil = timeMs + Math.max(32L, kick.firstDur);
                kick.emit = true;
            }
            return;
        }
        if (kick.phase == 3) {
            kick.emit = held;
            if (!held) {
                kick.phase = 0;
            }
            return;
        }
        if (kick.phase == 4) {
            kick.emit = held;
            kick.forceRt = held;
            if (!held) {
                kick.phase = 0;
            }
            return;
        }
        kick.emit = timeMs < kick.replayUntil;
        if (!kick.emit) {
            kick.phase = 0;
        }
    }

    private Seg currentSeg(long timeMs) {
        if (chord == null) {
            return null;
        }
        while (chordIndex < chord.segs.length && timeMs - chordSegAt >= chord.segs[chordIndex].durMs) {
            chordSegAt += chord.segs[chordIndex].durMs;
            chordIndex++;
        }
        if (chordIndex >= chord.segs.length) {
            chord = null;
            return null;
        }
        return chord.segs[chordIndex];
    }

    /** Player-space (right, forward) into screen space, following facing. */
    private float[] toScreen(float right, float forward, PadState in) {
        float fx;
        float fy;
        float facing = mag(in.lx, in.ly);
        if (facing > 0.35f) {
            fx = in.lx / facing;
            fy = in.ly / facing;
        } else if (attack == AttackDirection.RIGHT) {
            fx = 1f;
            fy = 0f;
        } else {
            fx = 0f;
            fy = 1f;
        }
        return new float[]{
                forward * fx + right * fy,
                forward * fy - right * fx
        };
    }

    private static float dead(float v) {
        return Math.abs(v) < DEADZONE ? 0f : v;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float mag(float x, float y) {
        return (float) Math.hypot(x, y);
    }

    private static final class Kick {
        int phase;
        long downAt;
        long upAt;
        long firstDur;
        long replayUntil;
        boolean emit;
        boolean forceRt;
        boolean beganStun;
    }

    private static final class Seg {
        final int durMs;
        final float feintRight;
        final float feintForward;
        final float moveRight;
        final float moveForward;
        final boolean lb;
        final boolean rb;
        final boolean clearRt;
        final boolean screenMove;

        Seg(int durMs, float feintRight, float feintForward, float moveRight, float moveForward,
                boolean lb, boolean rb, boolean clearRt, boolean screenMove) {
            this.durMs = durMs;
            this.feintRight = feintRight;
            this.feintForward = feintForward;
            this.moveRight = moveRight;
            this.moveForward = moveForward;
            this.lb = lb;
            this.rb = rb;
            this.clearRt = clearRt;
            this.screenMove = screenMove;
        }

        static Seg feint(int dur, float right, float forward) {
            return new Seg(dur, right, forward, 0f, 0f, false, false, false, false);
        }

        static Seg hold(int dur, float stickRight, float stickForward, float moveRight, float moveForward,
                boolean lb, boolean clearRt) {
            return new Seg(dur, stickRight, stickForward, moveRight, moveForward, lb, false, clearRt, false);
        }
    }

    private static final class Chord {
        final Seg[] segs;

        Chord(Seg... segs) {
            this.segs = segs;
        }
    }

    private static final class Chords {
        static final Chord MARSEILLE = new Chord(
                Seg.hold(140, 0f, 1f, 0f, 0f, false, false),
                Seg.hold(160, 0f, 1f, 0.65f, 0.8f, false, false));
        static final Chord RAINBOW = new Chord(
                Seg.hold(100, 0f, 1f, 0f, 0f, false, true),
                Seg.hold(80, 0f, 0f, 0f, 0f, false, true),
                Seg.hold(100, 0f, 1f, 0f, 0f, false, true));
        static final Chord DOUBLE_TOUCH = new Chord(
                Seg.hold(140, 1f, 0f, 0f, 0f, false, false),
                Seg.hold(150, 1f, 0f, 0f, 1f, false, false));
        static final Chord DRAW_OPEN = new Chord(
                Seg.hold(140, 0f, -1f, 0f, 0f, false, false),
                Seg.hold(160, 0f, -1f, 1f, 0.35f, false, false));
        static final Chord DRAW_CLOSE = new Chord(
                Seg.hold(140, 0f, -1f, 0f, 0f, false, false),
                Seg.hold(160, 0f, -1f, 0f, 1f, false, false));
        static final Chord STEP_OVER = new Chord(
                Seg.hold(80, 0f, -1f, 0f, 0f, false, false),
                Seg.hold(80, 1f, 0f, 0f, 0f, false, false),
                Seg.hold(120, 0f, 1f, 0f, 0f, false, false));
        static final Chord SCOTCH = new Chord(
                Seg.hold(220, 0f, -1f, 0.45f, 1f, false, false));
        static final Chord SCISSORS = new Chord(
                Seg.hold(70, -1f, 0.2f, 0f, 0f, false, false),
                Seg.hold(70, -0.3f, 1f, 0f, 0f, false, false),
                Seg.hold(70, 0.3f, 1f, 0f, 0f, false, false),
                Seg.hold(70, 1f, 0.2f, 0f, 0f, false, false),
                Seg.hold(120, 0f, 0f, 0f, 1f, false, false));
        static final Chord STEP_ON = new Chord(
                Seg.hold(240, 0f, -1f, 0f, 0f, false, false));

        static Chord lFeint(int side) {
            return new Chord(Seg.hold(200, 0f, -1f, side, 0f, false, false));
        }

        static Chord chop(int side, float up) {
            float moveRight = side == 0 ? 0.55f : side * 0.75f;
            float moveForward = up > 0f ? 0.85f : 0.7f;
            return new Chord(Seg.hold(180, 0f, 0f, moveRight, moveForward, true, false));
        }

        static Chord body(int side) {
            return new Chord(Seg.hold(150, side, 0f, 0f, 0f, false, false));
        }

        static Chord flipFlap(int side) {
            return new Chord(Seg.hold(220, -side, 0.65f, side, 0.85f, true, false));
        }
    }
}
