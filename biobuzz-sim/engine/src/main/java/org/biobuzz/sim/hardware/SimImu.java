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

import java.util.Random;
import java.util.function.Consumer;

/**
 * The Control Hub's built-in IMU (BHI260AP).
 *
 * Yaw = how far the robot has turned since the last resetYaw() (or since
 * initialize()), counter-clockwise positive, plus realistic errors:
 *   - NOISE: each reading wobbles a tiny bit.
 *   - DRIFT: the zero slowly wanders (a different rate each seed).
 *   - MOUNTING: the IMU turns the hub's own axes into robot axes using the
 *     RevHubOrientationOnRobot our code passes to initialize(). If that
 *     doesn't match how the hub is really mounted (robot.jsonc "imu"), the
 *     turn shows up on the wrong axis - e.g. yaw reads BACKWARDS (logo UP vs
 *     DOWN) or barely changes (logo UP vs FORWARD). Exactly like a real robot.
 */
public final class SimImu implements IMU {

    private final String configName;
    private final SimRobot robot;
    private final long readNs;
    private final double[][] actualAxes;
    private final String actualLogo;
    private final String actualUsb;
    private final Consumer<String> warn;
    private final Random noiseRng;
    private final double yawNoiseRad;
    private final double rateNoiseRad;
    private final double driftRadPerSec;

    /** How much of the robot's true yaw the IMU reports (1 when mounted as the code says). */
    private double yawScale = 1.0;
    private double yawZeroRad;
    /** Accumulated drift since power-on (radians). */
    private double bias;
    private double biasAtReset;

    public SimImu(String configName, SimRobot robot, long readNs, String actualLogo, String actualUsb,
                  Consumer<String> warn, Random rng, double yawNoiseDeg, double rateNoiseDegPerSec,
                  double driftSigmaDegPerMin) {
        this.configName = configName;
        this.robot = robot;
        this.readNs = readNs;
        this.actualLogo = actualLogo;
        this.actualUsb = actualUsb;
        this.actualAxes = hubAxes(actualLogo, actualUsb);
        this.warn = warn;
        this.noiseRng = rng;
        this.yawNoiseRad = Math.toRadians(yawNoiseDeg);
        this.rateNoiseRad = Math.toRadians(rateNoiseDegPerSec);
        this.driftRadPerSec = Math.toRadians(rng.nextGaussian() * driftSigmaDegPerMin) / 60.0;
        this.yawZeroRad = robot.heading;
    }

    public String configName() {
        return configName;
    }

    /** Called every physics step: the drift grows over time. */
    public void step(double dt) {
        bias += driftRadPerSec * dt;
    }

    /** Yaw the IMU reports (radians), before read noise. */
    private double measuredYaw() {
        return Units.wrapRadians(yawScale * (robot.heading - yawZeroRad) + (bias - biasAtReset));
    }

    @Override
    public boolean initialize(Parameters parameters) {
        SimHooks.charge(readNs * 5); // initializing takes a few I2C transactions
        if (parameters.imuOrientationOnRobot instanceof RevHubOrientationOnRobot) {
            RevHubOrientationOnRobot o = (RevHubOrientationOnRobot) parameters.imuOrientationOnRobot;
            String codeLogo = o.simLogoFacing().name();
            String codeUsb = o.simUsbFacing().name();
            yawScale = yawScaleFor(hubAxes(codeLogo, codeUsb), actualAxes);
            if (!codeLogo.equals(actualLogo) || !codeUsb.equals(actualUsb)) {
                warn.accept(String.format(
                        "IMU mounting mismatch: code says logo %s / USB %s but robot.jsonc says logo %s / USB %s. "
                                + "Yaw now reads %.0f%% of the real turn.",
                        codeLogo, codeUsb, actualLogo, actualUsb, yawScale * 100));
            }
        }
        yawZeroRad = robot.heading;
        biasAtReset = bias;
        return true;
    }

    /**
     * The hub's axes (x = USB direction, z = logo direction, y = z cross x)
     * written in robot coordinates (+x forward, +y left, +z up).
     */
    static double[][] hubAxes(String logo, String usb) {
        double[] z = dir(logo);
        double[] x = dir(usb);
        double[] y = {z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]};
        return new double[][] {x, y, z};
    }

    private static double[] dir(String d) {
        switch (d) {
            case "UP": return new double[] {0, 0, 1};
            case "DOWN": return new double[] {0, 0, -1};
            case "FORWARD": return new double[] {1, 0, 0};
            case "BACKWARD": return new double[] {-1, 0, 0};
            case "LEFT": return new double[] {0, 1, 0};
            default: return new double[] {0, -1, 0}; // RIGHT
        }
    }

    /**
     * The robot turns about its +z axis. The sensor feels that turn in its
     * own axes (actual mounting), and the SDK converts back to robot axes
     * using what the CODE claims. The yaw it reports is the z part of that.
     */
    static double yawScaleFor(double[][] code, double[][] actual) {
        double scale = 0;
        for (int i = 0; i < 3; i++) {
            scale += code[i][2] * actual[i][2];
        }
        return scale;
    }

    @Override
    public void resetYaw() {
        SimHooks.charge(readNs);
        yawZeroRad = robot.heading;
        biasAtReset = bias;
    }

    @Override
    public YawPitchRollAngles getRobotYawPitchRollAngles() {
        SimHooks.charge(readNs);
        double yaw = Units.wrapRadians(measuredYaw() + noiseRng.nextGaussian() * yawNoiseRad);
        return new YawPitchRollAngles(AngleUnit.DEGREES, Math.toDegrees(yaw), 0, 0, SimHooks.nanoTime());
    }

    /**
     * With pitch and roll both zero (a flat field), yaw simply appears
     * wherever Z is in the requested axis order.
     */
    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        SimHooks.charge(readNs);
        double yawRad = Units.wrapRadians(measuredYaw() + noiseRng.nextGaussian() * yawNoiseRad);
        float yaw = (float) angleUnit.fromRadians(yawRad);
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
        double half = measuredYaw() / 2.0;
        return new Quaternion((float) Math.cos(half), 0, 0, (float) Math.sin(half), SimHooks.nanoTime());
    }

    @Override
    public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) {
        SimHooks.charge(readNs);
        double rate = yawScale * robot.omega + driftRadPerSec + noiseRng.nextGaussian() * rateNoiseRad;
        float z = (float) (angleUnit == AngleUnit.DEGREES ? Math.toDegrees(rate) : rate);
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
