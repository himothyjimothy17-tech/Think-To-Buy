package org.biobuzz.sim.hardware;

import com.qualcomm.robotcore.hardware.DistanceSensor;

import org.biobuzz.simhooks.SimHooks;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * The optional POSSESSION sensor: a distance sensor looking across the
 * gate. It reads ~3 cm when a ball is waiting at the gate, and the far side
 * of the robot (~25 cm) when not. Both ESTIMATEs.
 */
public final class SimDistanceSensor implements DistanceSensor {

    private static final double BALL_CM = 3.0;
    private static final double EMPTY_CM = 25.0;
    private static final double NOISE_CM = 0.3;

    private final String configName;
    private final long readNs;
    private final Random rng;
    /** Set by the simulation once our robot's mechanisms exist. */
    public BooleanSupplier ballPresent = () -> false;

    public SimDistanceSensor(String configName, long readNs, Random rng) {
        this.configName = configName;
        this.readNs = readNs;
        this.rng = rng;
    }

    public String configName() {
        return configName;
    }

    @Override
    public double getDistance(DistanceUnit unit) {
        SimHooks.charge(readNs);
        double cm = (ballPresent.getAsBoolean() ? BALL_CM : EMPTY_CM) + rng.nextGaussian() * NOISE_CM;
        return unit.fromCm(cm);
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Broadcom;
    }

    @Override
    public String getDeviceName() {
        return "REV 2m Distance Sensor (possession)";
    }

    @Override
    public String getConnectionInfo() {
        return "Control Hub; I2C bus 1";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
    }

    @Override
    public void close() {
    }
}
