package org.biobuzz.sim.physics;

import java.util.List;

/**
 * The 12 V robot battery.
 *
 * A real battery has internal resistance (plus wires, connectors and the
 * fuse). Drawing current makes the voltage at the hubs drop:
 *     V = restVoltage - internalResistance x totalCurrent
 * So driving, intaking and spinning the flywheel at the same time lowers the
 * voltage, which makes EVERY motor weaker - including the flywheel, which
 * then shoots slower. That's why good FTC code reads the battery voltage.
 */
public final class Battery {
    private final double restVoltage;
    private final double internalResistance;
    /** Constant draw of the hubs, sensors and camera (A). */
    private final double electronicsCurrentA;

    private double voltage;
    private double totalCurrentA;
    private double minVoltage;

    public Battery(double restVoltage, double internalResistance, double electronicsCurrentA) {
        this.restVoltage = restVoltage;
        this.internalResistance = internalResistance;
        this.electronicsCurrentA = electronicsCurrentA;
        this.voltage = restVoltage;
        this.minVoltage = restVoltage;
    }

    /** Voltage at the hubs right now. */
    public double voltage() {
        return voltage;
    }

    public double totalCurrentA() {
        return totalCurrentA;
    }

    /** Lowest voltage seen so far (useful to spot brownout risk). */
    public double minVoltage() {
        return minVoltage;
    }

    /**
     * Recomputes the voltage from what every motor drew during the last step.
     * (Using the previous step's currents adds a 1 ms delay, far faster than
     * anything we'd notice.)
     */
    public void update(List<MotorState> motors) {
        double total = electronicsCurrentA;
        for (MotorState m : motors) {
            total += m.batteryCurrentA;
        }
        // A battery can't be charged much by braking motors; ignore regeneration.
        totalCurrentA = Math.max(total, electronicsCurrentA);
        voltage = Math.max(0.0, restVoltage - internalResistance * totalCurrentA);
        minVoltage = Math.min(minVoltage, voltage);
    }
}
