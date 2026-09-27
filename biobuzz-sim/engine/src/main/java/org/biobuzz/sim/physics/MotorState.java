package org.biobuzz.sim.physics;

/**
 * What one motor is doing right now: output shaft angle and speed, the power
 * the hub applies, and the resulting current and torque.
 *
 * "appliedPower" is AFTER the SDK's setDirection() and the hub's control
 * loop, so it is exactly the duty cycle the hub puts on the motor wires (-1..1).
 *
 * The motor doesn't move itself: a MECHANISM (drivetrain wheel, flywheel,
 * intake roller) asks for the torque with {@link #torque(double)}, adds its
 * own loads, and integrates the motion. That way two motors on one shaft
 * (our flywheel) really share the load.
 */
public final class MotorState {
    public final MotorSpec spec;
    public final String role;

    /** Output shaft angle in radians (never wraps; this is what the encoder counts). */
    public double angleRad;
    /** Output shaft speed in rad/s. */
    public double velocityRadPerSec;
    /** Duty cycle on the motor wires, -1..1. */
    public double appliedPower;
    /** Brake (true) or float (false) when power is zero. */
    public boolean brakeAtZero = true;
    /** Motor current (A) from the last torque() call. Positive = driving forward. */
    public double currentA;
    /** Current drawn from the battery (A): duty cycle x motor current. */
    public double batteryCurrentA;

    public MotorState(MotorSpec spec, String role) {
        this.spec = spec;
        this.role = role;
    }

    /**
     * Output-shaft torque (N*m) the motor produces right now, given the
     * battery voltage. Also updates {@link #currentA}.
     */
    public double torque(double batteryVolts) {
        double w = velocityRadPerSec;
        double current;
        if (appliedPower == 0.0 && !brakeAtZero) {
            // FLOAT: the H-bridge disconnects the motor, no current flows.
            current = 0.0;
        } else {
            // PWM averages to duty x battery voltage. BRAKE = duty 0 with the
            // wires shorted, which is the same formula with V = 0.
            double volts = appliedPower * batteryVolts;
            current = (volts - spec.ke * w) / spec.resistanceOhm;
        }
        currentA = current;
        batteryCurrentA = appliedPower * current;
        // Friction always opposes motion (smoothed near zero to keep physics stable).
        double friction = spec.frictionTorqueNm * Math.tanh(w / 2.0);
        return spec.kt * current - friction;
    }
}
