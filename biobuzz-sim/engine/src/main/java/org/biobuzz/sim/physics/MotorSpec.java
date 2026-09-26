package org.biobuzz.sim.physics;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.util.Units;

/**
 * Spec-sheet numbers for one motor model (e.g. goBILDA Yellow Jacket 435 RPM),
 * measured at the OUTPUT shaft (after the built-in gearbox), at 12 V.
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

    public MotorSpec(String id, Cfg c) {
        this.id = id;
        this.freeRpm = c.num("freeRpm");
        this.gearRatio = c.num("gearRatio");
        this.ticksPerRev = c.num("ticksPerRev");
        this.stallTorqueNm = c.num("stallTorqueNm");
        this.stallCurrentA = c.num("stallCurrentA");
        this.freeCurrentA = c.num("freeCurrentA");
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
