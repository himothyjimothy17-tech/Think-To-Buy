package org.biobuzz.sim.physics;

/**
 * What one motor is doing right now: its output shaft angle and speed, and
 * the power the hub is applying to it.
 *
 * "appliedPower" is AFTER the SDK's setDirection() is applied and BEFORE the
 * physical mounting is considered, so it is exactly what the hub sends to the
 * motor's wires (-1..1).
 *
 * STAGE 1 MODEL (simple on purpose): the shaft speed moves smoothly toward
 * appliedPower x free speed. Stage 2 replaces step() with the real DC motor
 * torque/speed curve, battery voltage and load.
 */
public final class MotorState {
    public final MotorSpec spec;
    public final String role;

    /** Output shaft angle in radians (never wraps; this is what the encoder counts). */
    public double angleRad;
    /** Output shaft speed in rad/s. */
    public double velocityRadPerSec;
    /** Power on the motor wires, -1..1. */
    public double appliedPower;
    /** Brake (true) or float (false) when power is zero. Used from stage 2. */
    public boolean brakeAtZero = true;

    public MotorState(MotorSpec spec, String role) {
        this.spec = spec;
        this.role = role;
    }

    /**
     * Stage 1 update: first-order response toward the commanded speed.
     * @param responseSeconds time constant - how quickly the motor reaches speed
     */
    public void stepSimple(double dt, double responseSeconds) {
        double target = appliedPower * spec.freeSpeedRadPerSec();
        double blend = 1.0 - Math.exp(-dt / responseSeconds);
        velocityRadPerSec += (target - velocityRadPerSec) * blend;
        angleRad += velocityRadPerSec * dt;
    }
}
