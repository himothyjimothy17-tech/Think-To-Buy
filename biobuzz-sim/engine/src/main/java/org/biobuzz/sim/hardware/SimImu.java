package org.biobuzz.sim.hardware;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.IMU;

import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.Units;
import org.biobuzz.simhooks.SimHooks;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

import java.util.function.Consumer;

/**
 * The Control Hub's built-in IMU.
 *
 * Yaw = how far the robot has turned since the last resetYaw() (or since
 * initialize()), counter-clockwise positive, like the real IMU.
 *
 * If our code tells initialize() a different hub mounting than the one in
 * robot.jsonc, the sim warns us - on the real robot that mistake makes the
 * heading wrong.
 *
 * TODO(stage 2): small drift and noise; wrong-mounting effects on the axes.
 */
public final class SimImu implements IMU {

    private final String configName;
    private final SimRobot robot;
    private final long readNs;
    private final String actualLogo;
    private final String actualUsb;
    private final Consumer<String> warn;

    private double yawZeroRad;
    private boolean initialized;

    public SimImu(String configName, SimRobot robot, long readNs, String actualLogo, String actualUsb,
                  Consumer<String> warn) {
        this.configName = configName;
        this.robot = robot;
        this.readNs = readNs;
        this.actualLogo = actualLogo;
        this.actualUsb = actualUsb;
        this.warn = warn;
        this.yawZeroRad = robot.heading;
    }

    public String configName() {
        return configName;
    }

    private double yawRad() {
        return Units.wrapRadians(robot.heading - yawZeroRad);
    }

    @Override
    public boolean initialize(Parameters parameters) {
        SimHooks.charge(readNs * 5); // initializing takes a few I2C transactions
        initialized = true;
        if (parameters.imuOrientationOnRobot instanceof RevHubOrientationOnRobot) {
            RevHubOrientationOnRobot o = (RevHubOrientationOnRobot) parameters.imuOrientationOnRobot;
            if (!o.simLogoFacing().name().equals(actualLogo) || !o.simUsbFacing().name().equals(actualUsb)) {
                warn.accept(String.format(
                        "IMU mounting mismatch: code says logo %s / USB %s but robot.jsonc says logo %s / USB %s. "
                                + "On the real robot the heading would be wrong.",
                        o.simLogoFacing(), o.simUsbFacing(), actualLogo, actualUsb));
            }
        }
        return true;
    }

    @Override
    public void resetYaw() {
        SimHooks.charge(readNs);
        yawZeroRad = robot.heading;
    }

    @Override
    public YawPitchRollAngles getRobotYawPitchRollAngles() {
        SimHooks.charge(readNs);
        return new YawPitchRollAngles(AngleUnit.DEGREES, Math.toDegrees(yawRad()), 0, 0, SimHooks.nanoTime());
    }

    /**
     * With pitch and roll both zero (a flat field), yaw simply appears
     * wherever Z is in the requested axis order.
     */
    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        SimHooks.charge(readNs);
        float yaw = (float) angleUnit.fromRadians(yawRad());
        float[] angles = new float[3];
        int[] idx = order.indices();
        for (int i = 0; i < 3; i++) {
            if (idx[i] == 2) {
                angles[i] = yaw;
                break;
            }
        }
        return new Orientation(reference, order, angleUnit, angles[0], angles[1], angles[2], SimHooks.nanoTime());
    }

    @Override
    public Quaternion getRobotOrientationAsQuaternion() {
        SimHooks.charge(readNs);
        double half = yawRad() / 2.0;
        return new Quaternion((float) Math.cos(half), 0, 0, (float) Math.sin(half), SimHooks.nanoTime());
    }

    @Override
    public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) {
        SimHooks.charge(readNs);
        float z = (float) (angleUnit == AngleUnit.DEGREES ? Math.toDegrees(robot.omega) : robot.omega);
        return new AngularVelocity(angleUnit, 0f, 0f, z, SimHooks.nanoTime());
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return "REV Internal IMU (BHI260AP)";
    }

    @Override
    public String getConnectionInfo() {
        return "Control Hub; I2C bus 0";
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
