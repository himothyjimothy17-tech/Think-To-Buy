package org.biobuzz.sim.physics;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.util.Units;

/**
 * Spec-sheet numbers for one motor model (e.g. goBILDA Yellow Jacket 435 RPM),
 * measured at the OUTPUT shaft (after the built-in gearbox), at 12 V, plus
 * the "DC motor model" constants we derive from them.
 *
 * THE DC MOTOR MODEL (standard physics, worth knowing for judges):
 *   A motor is a coil with resistance R. Spinning makes it a generator that
 *   pushes back with a voltage ke * speed ("back-EMF"). So:
 *       current I  = (V - ke * speed) / R
 *       torque     = kt * I  -  friction
 *   At 0 speed the current is the biggest (stall current) and so is the
 *   torque (stall torque). At free speed, back-EMF almost cancels V, the
 *   current is tiny and the torque only covers internal friction.
 *   That's why a motor gets weaker as it speeds up - the "torque curve".
 */
public final class MotorSpec {
    public final String id;
    public final double freeRpm;
    public final double gearRatio;
    public final double ticksPerRev;
    public final double stallTorqueNm;
    public final double stallCurrentA;
    public final double freeCurrentA;

    /** Voltage the spec sheet numbers are measured at. */
    public static final double SPEC_VOLTAGE = 12.0;

    /** Coil resistance in ohms: at stall, all 12 V is across the resistance. */
    public final double resistanceOhm;
    /** Torque per amp (N*m/A), at the output shaft. */
    public final double kt;
    /** Back-EMF volts per rad/s, at the output shaft. */
    public final double ke;
    /** Internal friction torque (gearbox, brushes) - what the free current is spent on. */
    public final double frictionTorqueNm;

    public MotorSpec(String id, Cfg c) {
        this.id = id;
        this.freeRpm = c.num("freeRpm");
        this.gearRatio = c.num("gearRatio");
        this.ticksPerRev = c.num("ticksPerRev");
        this.stallTorqueNm = c.num("stallTorqueNm");
        this.stallCurrentA = c.num("stallCurrentA");
        this.freeCurrentA = c.num("freeCurrentA");

        resistanceOhm = SPEC_VOLTAGE / stallCurrentA;
        kt = stallTorqueNm / stallCurrentA;
        // At free speed the current is freeCurrent, so V = I*R + ke*speed.
        ke = (SPEC_VOLTAGE - freeCurrentA * resistanceOhm) / freeSpeedRadPerSec();
        frictionTorqueNm = kt * freeCurrentA;
    }

    /** Free (no-load) speed of the output shaft in rad/s at 12 V. */
    public double freeSpeedRadPerSec() {
        return Units.rpmToRadPerSec(freeRpm);
    }

    /** Encoder ticks per radian of the output shaft. */
    public double ticksPerRadian() {
        return ticksPerRev / (2.0 * Math.PI);
    }
}
