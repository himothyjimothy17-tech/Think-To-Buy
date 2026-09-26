package com.qualcomm.robotcore.hardware;

/**
 * A gamepad (we use a Logitech F310 in X mode).
 *
 * Sign conventions match the real SDK:
 *   left_stick_y / right_stick_y are NEGATIVE when the stick is pushed UP.
 *   left_stick_x / right_stick_x are POSITIVE when pushed RIGHT.
 *   triggers go from 0.0 (released) to 1.0 (fully pressed).
 *
 * The "WasPressed()" / "WasReleased()" methods return true once per press:
 * they report whether the button changed since the last time you asked.
 */
public class Gamepad {

    public static final float DEFAULT_TRIGGER_THRESHOLD = 0.15f;
    public static final int ID_UNASSOCIATED = -1;
    public static final int ID_SYNTHETIC = -2;
    public static final int LED_DURATION_CONTINUOUS = -1;
    public static final int RUMBLE_DURATION_CONTINUOUS = -1;

    public volatile float left_stick_x;
    public volatile float left_stick_y;
    public volatile float right_stick_x;
    public volatile float right_stick_y;
    public volatile boolean dpad_up;
    public volatile boolean dpad_down;
    public volatile boolean dpad_left;
    public volatile boolean dpad_right;
    public volatile boolean a;
    public volatile boolean b;
    public volatile boolean x;
    public volatile boolean y;
    public volatile boolean guide;
    public volatile boolean start;
    public volatile boolean back;
    public volatile boolean left_bumper;
    public volatile boolean right_bumper;
    public volatile boolean left_stick_button;
    public volatile boolean right_stick_button;
    public volatile float left_trigger;
    public volatile float right_trigger;
    public volatile boolean left_trigger_pressed;
    public volatile boolean right_trigger_pressed;

    // PlayStation-style aliases (same buttons, different names).
    public volatile boolean circle;
    public volatile boolean cross;
    public volatile boolean triangle;
    public volatile boolean square;
    public volatile boolean share;
    public volatile boolean options;
    public volatile boolean touchpad;
    public volatile boolean touchpad_finger_1;
    public volatile boolean touchpad_finger_2;
    public volatile float touchpad_finger_1_x;
    public volatile float touchpad_finger_1_y;
    public volatile float touchpad_finger_2_x;
    public volatile float touchpad_finger_2_y;
    public volatile boolean ps;

    public volatile int id = ID_UNASSOCIATED;
    public volatile long timestamp;
    public long nextRumbleApproxFinishTime;

    private float triggerThreshold = DEFAULT_TRIGGER_THRESHOLD;

    // Edge detection: one "pressed" and one "released" latch per button.
    private enum Button {
        DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, A, B, X, Y, GUIDE, START, BACK,
        LEFT_BUMPER, RIGHT_BUMPER, LEFT_STICK_BUTTON, RIGHT_STICK_BUTTON, TOUCHPAD, PS,
        LEFT_TRIGGER, RIGHT_TRIGGER
    }

    private final boolean[] pressedLatch = new boolean[Button.values().length];
    private final boolean[] releasedLatch = new boolean[Button.values().length];

    public Gamepad() {
    }

    public void setTriggerThreshold(float threshold) {
        this.triggerThreshold = threshold;
    }

    public float getTriggerThreshold() {
        return triggerThreshold;
    }

    public void setGamepadId(int id) {
        this.id = id;
    }

    public int getGamepadId() {
        return id;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void refreshTimestamp() {
        this.timestamp = org.biobuzz.simhooks.SimHooks.nanoTime();
    }

    /**
     * Copies another gamepad's state into this one. The simulator calls this
     * whenever new controller data arrives; it also updates the
     * WasPressed/WasReleased latches.
     */
    public void copy(Gamepad g) {
        boolean[] before = buttonStates();
        left_stick_x = g.left_stick_x;
        left_stick_y = g.left_stick_y;
        right_stick_x = g.right_stick_x;
        right_stick_y = g.right_stick_y;
        dpad_up = g.dpad_up;
        dpad_down = g.dpad_down;
        dpad_left = g.dpad_left;
        dpad_right = g.dpad_right;
        a = g.a;
        b = g.b;
        x = g.x;
        y = g.y;
        guide = g.guide;
        start = g.start;
        back = g.back;
        left_bumper = g.left_bumper;
        right_bumper = g.right_bumper;
        left_stick_button = g.left_stick_button;
        right_stick_button = g.right_stick_button;
        left_trigger = g.left_trigger;
        right_trigger = g.right_trigger;
        touchpad = g.touchpad;
        ps = g.ps;
        id = g.id;
        timestamp = g.timestamp;
        updateButtonAliases();
        boolean[] after = buttonStates();
        for (int i = 0; i < after.length; i++) {
            if (!before[i] && after[i]) {
                pressedLatch[i] = true;
            }
            if (before[i] && !after[i]) {
                releasedLatch[i] = true;
            }
        }
    }

    /** Releases every button and centers every stick. */
    public void reset() {
        copy(new Gamepad());
    }

    /** True when no stick is moved and no button is pressed. */
    public boolean atRest() {
        return left_stick_x == 0f && left_stick_y == 0f && right_stick_x == 0f && right_stick_y == 0f
                && left_trigger == 0f && right_trigger == 0f;
    }

    protected void updateButtonAliases() {
        circle = b;
        cross = a;
        triangle = y;
        square = x;
        share = back;
        options = start;
        left_trigger_pressed = left_trigger > triggerThreshold;
        right_trigger_pressed = right_trigger > triggerThreshold;
    }

    private boolean[] buttonStates() {
        return new boolean[] {
            dpad_up, dpad_down, dpad_left, dpad_right, a, b, x, y, guide, start, back,
            left_bumper, right_bumper, left_stick_button, right_stick_button, touchpad, ps,
            left_trigger > triggerThreshold, right_trigger > triggerThreshold
        };
    }

    private boolean takePressed(Button b) {
        boolean v = pressedLatch[b.ordinal()];
        pressedLatch[b.ordinal()] = false;
        return v;
    }

    private boolean takeReleased(Button b) {
        boolean v = releasedLatch[b.ordinal()];
        releasedLatch[b.ordinal()] = false;
        return v;
    }

    public void resetEdgeDetection() {
        java.util.Arrays.fill(pressedLatch, false);
        java.util.Arrays.fill(releasedLatch, false);
    }

    // ---- Rumble / LEDs: the F310 has neither, so these just do nothing. ----

    public void setLedColor(double r, double g, double b, int durationMs) {
    }

    public void rumble(int durationMs) {
    }

    public void rumble(double rumble1, double rumble2, int durationMs) {
    }

    public void stopRumble() {
    }

    public void rumbleBlips(int count) {
    }

    public boolean isRumbling() {
        return false;
    }

    @Override
    public String toString() {
        return String.format(
                "ID: %d LX: %.2f LY: %.2f RX: %.2f RY: %.2f LT: %.2f RT: %.2f %s%s%s%s%s%s",
                id, left_stick_x, left_stick_y, right_stick_x, right_stick_y, left_trigger, right_trigger,
                a ? "a " : "", b ? "b " : "", x ? "x " : "", y ? "y " : "",
                left_bumper ? "lb " : "", right_bumper ? "rb " : "");
    }

    // ---- Edge detection (one method pair per button, same names as the real SDK) ----

    public boolean dpadUpWasPressed() { return takePressed(Button.DPAD_UP); }
    public boolean dpadUpWasReleased() { return takeReleased(Button.DPAD_UP); }
    public boolean dpadDownWasPressed() { return takePressed(Button.DPAD_DOWN); }
    public boolean dpadDownWasReleased() { return takeReleased(Button.DPAD_DOWN); }
    public boolean dpadLeftWasPressed() { return takePressed(Button.DPAD_LEFT); }
    public boolean dpadLeftWasReleased() { return takeReleased(Button.DPAD_LEFT); }
    public boolean dpadRightWasPressed() { return takePressed(Button.DPAD_RIGHT); }
    public boolean dpadRightWasReleased() { return takeReleased(Button.DPAD_RIGHT); }
    public boolean aWasPressed() { return takePressed(Button.A); }
    public boolean aWasReleased() { return takeReleased(Button.A); }
    public boolean bWasPressed() { return takePressed(Button.B); }
    public boolean bWasReleased() { return takeReleased(Button.B); }
    public boolean xWasPressed() { return takePressed(Button.X); }
    public boolean xWasReleased() { return takeReleased(Button.X); }
    public boolean yWasPressed() { return takePressed(Button.Y); }
    public boolean yWasReleased() { return takeReleased(Button.Y); }
    public boolean guideWasPressed() { return takePressed(Button.GUIDE); }
    public boolean guideWasReleased() { return takeReleased(Button.GUIDE); }
    public boolean startWasPressed() { return takePressed(Button.START); }
    public boolean startWasReleased() { return takeReleased(Button.START); }
    public boolean backWasPressed() { return takePressed(Button.BACK); }
    public boolean backWasReleased() { return takeReleased(Button.BACK); }
    public boolean leftBumperWasPressed() { return takePressed(Button.LEFT_BUMPER); }
    public boolean leftBumperWasReleased() { return takeReleased(Button.LEFT_BUMPER); }
    public boolean rightBumperWasPressed() { return takePressed(Button.RIGHT_BUMPER); }
    public boolean rightBumperWasReleased() { return takeReleased(Button.RIGHT_BUMPER); }
    public boolean leftStickButtonWasPressed() { return takePressed(Button.LEFT_STICK_BUTTON); }
    public boolean leftStickButtonWasReleased() { return takeReleased(Button.LEFT_STICK_BUTTON); }
    public boolean rightStickButtonWasPressed() { return takePressed(Button.RIGHT_STICK_BUTTON); }
    public boolean rightStickButtonWasReleased() { return takeReleased(Button.RIGHT_STICK_BUTTON); }
    public boolean circleWasPressed() { return bWasPressed(); }
    public boolean circleWasReleased() { return bWasReleased(); }
    public boolean crossWasPressed() { return aWasPressed(); }
    public boolean crossWasReleased() { return aWasReleased(); }
    public boolean triangleWasPressed() { return yWasPressed(); }
    public boolean triangleWasReleased() { return yWasReleased(); }
    public boolean squareWasPressed() { return xWasPressed(); }
    public boolean squareWasReleased() { return xWasReleased(); }
    public boolean shareWasPressed() { return backWasPressed(); }
    public boolean shareWasReleased() { return backWasReleased(); }
    public boolean optionsWasPressed() { return startWasPressed(); }
    public boolean optionsWasReleased() { return startWasReleased(); }
    public boolean touchpadWasPressed() { return takePressed(Button.TOUCHPAD); }
    public boolean touchpadWasReleased() { return takeReleased(Button.TOUCHPAD); }
    public boolean psWasPressed() { return takePressed(Button.PS); }
    public boolean psWasReleased() { return takeReleased(Button.PS); }
    public boolean leftTriggerWasPressed() { return takePressed(Button.LEFT_TRIGGER); }
    public boolean leftTriggerWasReleased() { return takeReleased(Button.LEFT_TRIGGER); }
    public boolean rightTriggerWasPressed() { return takePressed(Button.RIGHT_TRIGGER); }
    public boolean rightTriggerWasReleased() { return takeReleased(Button.RIGHT_TRIGGER); }
}
