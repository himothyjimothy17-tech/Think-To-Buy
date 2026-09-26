package org.biobuzz.sim.hardware;

import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.ServoController;

import org.biobuzz.simhooks.SimHooks;

/**
 * A simulated standard servo.
 *
 * Like the real SDK, getPosition() returns the position you COMMANDED, not
 * where the servo horn actually is. {@link #commandedRaw()} gives the physics
 * the target after direction and scaleRange() are applied.
 *
 * TODO(stage 3): the servo physically moves toward the target at its real
 * speed (robot.jsonc shooter.servo.secondsPer60Deg).
 */
public final class SimServo implements Servo {

    private final String configName;
    private final SimHub hub;
    private final int port;
    private final long writeNs;

    private Direction direction = Direction.FORWARD;
    private double position = Double.NaN; // like the SDK: unknown until first set
    private double scaleMin = MIN_POSITION;
    private double scaleMax = MAX_POSITION;

    public SimServo(String configName, SimHub hub, int port, long writeNs) {
        this.configName = configName;
        this.hub = hub;
        this.port = port;
        this.writeNs = writeNs;
        hub.servos[port] = this;
    }

    public String configName() {
        return configName;
    }

    /** The PWM target (0..1 of the servo's full range) actually sent to the servo, or NaN if never set. */
    public double commandedRaw() {
        if (Double.isNaN(position)) {
            return Double.NaN;
        }
        double p = direction == Direction.REVERSE ? 1.0 - position : position;
        return scaleMin + p * (scaleMax - scaleMin);
    }

    @Override
    public ServoController getController() {
        return hub;
    }

    @Override
    public int getPortNumber() {
        return port;
    }

    @Override
    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    @Override
    public Direction getDirection() {
        return direction;
    }

    @Override
    public void setPosition(double position) {
        double clipped = Math.max(MIN_POSITION, Math.min(MAX_POSITION, position));
        if (clipped != this.position) {
            SimHooks.charge(writeNs);
        }
        this.position = clipped;
    }

    @Override
    public double getPosition() {
        return position;
    }

    @Override
    public void scaleRange(double min, double max) {
        if (min < MIN_POSITION || max > MAX_POSITION || min >= max) {
            throw new IllegalArgumentException(String.format("scaleRange(%.3f, %.3f) is invalid", min, max));
        }
        scaleMin = min;
        scaleMax = max;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return "Servo";
    }

    @Override
    public String getConnectionInfo() {
        return hub.name + "; servo port " + port;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        direction = Direction.FORWARD;
        scaleMin = MIN_POSITION;
        scaleMax = MAX_POSITION;
    }

    @Override
    public void close() {
    }
}
