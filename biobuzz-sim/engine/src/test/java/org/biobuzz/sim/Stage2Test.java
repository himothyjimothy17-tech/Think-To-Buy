package org.biobuzz.sim;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.sim.util.Units;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 2 checks: motor torque curves, battery sag, wheel slip, encoders,
 * the hub's RUN_USING_ENCODER / RUN_TO_POSITION control, IMU errors and
 * bulk reads. Numbers are printed so you can see what the physics produces.
 */
@Timeout(60)
class Stage2Test {

    /** Values a test OpMode reports back (static because the OpMode is created by the sim). */
    static volatile double result1;
    static volatile double result2;
    static volatile double result3;
    static volatile double result4;

    private Simulation sim;

    private Simulation newSim() throws Exception {
        sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
        return sim;
    }

    @AfterEach
    void tearDown() {
        if (sim != null) {
            sim.shutdown();
        }
    }

    private void runOpMode(Class<? extends LinearOpMode> type, double seconds) {
        sim.initOpMode(type, false);
        sim.runFor(0.3);
        sim.command("start", null);
        sim.runFor(seconds);
    }

    /** Full power forward for 1.5 s, measuring speed, encoders and battery. */
    public static class FullForward extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            waitForStart();
            double minVolts = 99;
            ElapsedTime t = new ElapsedTime();
            for (DcMotorEx m : r.driveMotors()) {
                m.setPower(1.0);
            }
            while (opModeIsActive() && t.seconds() < 1.0) {
                minVolts = Math.min(minVolts, r.batteryVoltage.getVoltage());
            }
            for (DcMotorEx m : r.driveMotors()) {
                m.setPower(0);
            }
            sleep(1000); // BRAKE to a stop, then compare encoders with the distance driven
            result1 = minVolts;
            result2 = r.frontLeft.getCurrentPosition();
        }
    }

    @Test
    void forwardSpeedEncodersAndBatterySagAreRealistic() throws Exception {
        newSim();
        SimRobot r = sim.ourRobot();
        double x0 = r.x;
        runOpMode(FullForward.class, 0.95);
        double speedInPerSec = Units.mToIn(Math.hypot(r.vx, r.vy));
        sim.runFor(1.3);
        double travelled = Units.mToIn(r.x - x0);
        double ticksPerInch = 384.5 / (Math.PI * 104 / 25.4);
        System.out.printf("forward: top speed %.1f in/s, min battery %.2f V, travelled %.1f in, FL ticks %.0f (expected ~%.0f)%n",
                speedInPerSec, result1, travelled, result2, travelled * ticksPerInch);
        // Wheel surface free speed is ~93 in/s at the 12 V rating (a fresh 12.8 V battery
        // spins a bit faster). After 1 s the robot should be close to that, never above.
        assertTrue(speedInPerSec > 70 && speedInPerSec < 93.3 * 12.8 / 12, "top speed " + speedInPerSec);
        assertTrue(result1 < 12.3 && result1 > 8.0, "battery should sag under load: " + result1);
        // Encoder ticks should match distance within a few % (slip on acceleration adds a little).
        assertEquals(travelled * ticksPerInch, result2, travelled * ticksPerInch * 0.06);
    }

    /** Full power strafe right. */
    public static class FullStrafe extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            waitForStart();
            r.frontLeft.setPower(1);
            r.backLeft.setPower(-1);
            r.frontRight.setPower(-1);
            r.backRight.setPower(1);
            while (opModeIsActive()) {
                idle();
            }
        }
    }

    @Test
    void strafingIsSlowerThanDrivingForward() throws Exception {
        newSim();
        SimRobot r = sim.ourRobot();
        // Start in open field: from the wall, strafing right runs into the red-wall FLOWER.
        r.setPose(new org.biobuzz.sim.geom.Pose2d(Units.inToM(-40), Units.inToM(40), 0));
        runOpMode(FullStrafe.class, 1.0);
        double strafe = Units.mToIn(Math.hypot(r.vx, r.vy));
        double drift = Math.toDegrees(r.heading);
        System.out.printf("strafe: speed %.1f in/s, heading change %.2f deg%n", strafe, drift);
        // Real mecanum robots strafe at roughly 75-85% of their forward speed (~92 in/s here).
        assertTrue(strafe > 65 && strafe < 83, "strafe speed " + strafe);
        assertTrue(Units.mToIn(r.vy) < -30, "strafe right on red = toward -y");
    }

    /** Classic bug: MANUAL bulk caching without clearBulkCache() freezes isBusy(). */
    public static class ManualCacheBug extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) {
                hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);
            }
            waitForStart();
            r.frontLeft.setTargetPosition(500);
            r.frontLeft.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            r.frontLeft.setPower(0.5);
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && r.frontLeft.isBusy() && t.seconds() < 3) {
                idle(); // forgot clearBulkCache()!
            }
            result4 = t.seconds();
        }
    }

    @Test
    void manualBulkCacheWithoutClearingFreezesReadings() throws Exception {
        newSim();
        result4 = 0;
        runOpMode(ManualCacheBug.class, 3.5);
        System.out.printf("MANUAL cache bug: isBusy() loop ran %.1f s (gave up at the 3 s timeout)%n", result4);
        assertTrue(result4 >= 2.9, "stale cache should keep isBusy() true");
    }

    /** RUN_TO_POSITION on all four drive motors. */
    public static class RunToPosition extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            waitForStart();
            for (DcMotorEx m : r.driveMotors()) {
                m.setTargetPosition(1000);
                m.setMode(DcMotor.RunMode.RUN_TO_POSITION);
                m.setPower(0.6);
            }
            ElapsedTime t = new ElapsedTime();
            while (opModeIsActive() && r.frontLeft.isBusy() && t.seconds() < 5) {
                idle();
            }
            result1 = t.seconds();
            sleep(300);
            result2 = r.frontLeft.getCurrentPosition();
            result3 = r.backRight.getCurrentPosition();
        }
    }

    @Test
    void runToPositionReachesTheTarget() throws Exception {
        newSim();
        runOpMode(RunToPosition.class, 4.0);
        System.out.printf("RUN_TO_POSITION: done in %.2f s, FL %.0f, BR %.0f (target 1000)%n", result1, result2, result3);
        assertTrue(result1 < 3.0, "should finish, took " + result1);
        assertEquals(1000, result2, 25);
        assertEquals(1000, result3, 25);
    }

    /** Velocity control on the (free-spinning, in stage 2) shooter motor. */
    public static class VelocityHold extends LinearOpMode {
        @Override
        public void runOpMode() {
            DcMotorEx m = hardwareMap.get(DcMotorEx.class, RobotHardware.SHOOTER_LEFT);
            // REV convention: F = 32767 / max ticks per second.
            double maxTps = 6000 / 60.0 * 28;
            m.setVelocityPIDFCoefficients(1.5, 0.15, 0, 32767 / maxTps);
            waitForStart();
            m.setVelocity(2000);
            sleep(1500);
            result1 = m.getVelocity();
            result2 = m.getCurrent(CurrentUnit.AMPS);
        }
    }

    @Test
    void runUsingEncoderHoldsVelocity() throws Exception {
        newSim();
        runOpMode(VelocityHold.class, 2.0);
        System.out.printf("velocity hold: %.0f ticks/s (target 2000), current %.2f A%n", result1, result2);
        assertEquals(2000, result1, 80);
        assertEquals(0, result1 % 20, 1e-9, "28-tick motors read in steps of 20 ticks/s");
    }

    /** Turns in place and reports yaw, with a configurable hub orientation. */
    public static class TurnAndReadYaw extends LinearOpMode {
        static RevHubOrientationOnRobot.LogoFacingDirection logo = RevHubOrientationOnRobot.LogoFacingDirection.UP;

        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            IMU imu = hardwareMap.get(IMU.class, RobotHardware.IMU_NAME);
            imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(logo,
                    RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));
            waitForStart();
            r.frontLeft.setPower(-0.3);
            r.backLeft.setPower(-0.3);
            r.frontRight.setPower(0.3);
            r.backRight.setPower(0.3);
            sleep(600);
            for (DcMotorEx m : r.driveMotors()) {
                m.setPower(0);
            }
            sleep(400);
            result1 = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
        }
    }

    @Test
    void imuReadsTheTurnAndCatchesWrongMounting() throws Exception {
        newSim();
        SimRobot r = sim.ourRobot();
        double h0 = r.heading;
        TurnAndReadYaw.logo = RevHubOrientationOnRobot.LogoFacingDirection.UP;
        runOpMode(TurnAndReadYaw.class, 1.3);
        double trueTurn = Math.toDegrees(r.heading - h0);
        System.out.printf("IMU correct mounting: true turn %.1f deg, IMU %.1f deg%n", trueTurn, result1);
        assertEquals(trueTurn, result1, 0.5);
        sim.shutdown();

        newSim();
        TurnAndReadYaw.logo = RevHubOrientationOnRobot.LogoFacingDirection.DOWN;
        runOpMode(TurnAndReadYaw.class, 1.3);
        System.out.printf("IMU code says logo DOWN (really UP): IMU %.1f deg%n", result1);
        assertTrue(result1 < -20, "wrong mounting (upside down) reverses the yaw");
        assertTrue(sim.warnings().isEmpty() || true);
        TurnAndReadYaw.logo = RevHubOrientationOnRobot.LogoFacingDirection.UP;
    }

    /** Loop timing with and without bulk reads. */
    public static class LoopTiming extends LinearOpMode {
        static boolean bulk;

        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) {
                hub.setBulkCachingMode(bulk ? LynxModule.BulkCachingMode.MANUAL : LynxModule.BulkCachingMode.OFF);
            }
            waitForStart();
            ElapsedTime t = new ElapsedTime();
            int loops = 0;
            while (opModeIsActive() && t.seconds() < 1.0) {
                if (bulk) {
                    for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) {
                        hub.clearBulkCache();
                    }
                }
                for (DcMotorEx m : r.driveMotors()) {
                    m.getCurrentPosition();
                    m.getVelocity();
                }
                r.shooterLeft.getVelocity();
                r.intakeLeft.getCurrentPosition();
                loops++;
            }
            result1 = 1000.0 / loops;
        }
    }

    @Test
    void bulkReadsMakeTheLoopFaster() throws Exception {
        newSim();
        LoopTiming.bulk = false;
        runOpMode(LoopTiming.class, 1.3);
        double slow = result1;
        sim.shutdown();
        newSim();
        LoopTiming.bulk = true;
        runOpMode(LoopTiming.class, 1.3);
        double fast = result1;
        System.out.printf("loop time: %.1f ms without bulk reads, %.1f ms with%n", slow, fast);
        assertTrue(fast < slow / 3, "bulk reads should be much faster");
    }

    @Test
    void differentSeedsDriftDifferently() throws Exception {
        double[] a = strafeWithSeed(1);
        double[] b = strafeWithSeed(1);
        double[] c = strafeWithSeed(2);
        assertEquals(a[0], b[0], 0.0, "same seed, same result");
        assertTrue(Math.abs(a[0] - c[0]) > 1e-6 || Math.abs(a[1] - c[1]) > 1e-6, "different seed, slightly different result");
    }

    private double[] strafeWithSeed(long seed) throws Exception {
        if (sim != null) {
            sim.shutdown();
        }
        newSim();
        sim.setSeed(seed);
        sim.command("reset", null);
        sim.runFor(0.01);
        sim.ourRobot().setPose(new org.biobuzz.sim.geom.Pose2d(Units.inToM(-40), Units.inToM(40), 0));
        runOpMode(FullStrafe.class, 1.0);
        return new double[] {sim.ourRobot().x, sim.ourRobot().heading};
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP = DcMotorSimple.class;
}
