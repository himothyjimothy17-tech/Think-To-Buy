package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.VoltageSensor;

/**
 * Every piece of hardware on the robot, in one place.
 *
 * The NAME constants below must match the names typed into the Driver
 * Station's robot configuration EXACTLY (capital letters count). The
 * simulator's configuration (config/hubs.jsonc) uses the same names, so a
 * typo here fails in the simulator exactly like it would on the robot.
 *
 * To use: create one RobotHardware in your OpMode and call init(hardwareMap).
 */
public class RobotHardware {

    // ---- Configuration names (Driver Station "Configure Robot" screen) ----
    // Control Hub motor ports 0-3
    public static final String FRONT_LEFT = "frontLeft";
    public static final String BACK_LEFT = "backLeft";
    public static final String FRONT_RIGHT = "frontRight";
    public static final String BACK_RIGHT = "backRight";
    // Expansion Hub motor ports 0-3
    public static final String INTAKE_LEFT = "intakeLeft";
    public static final String INTAKE_RIGHT = "intakeRight";
    public static final String SHOOTER_LEFT = "shooterLeft";
    public static final String SHOOTER_RIGHT = "shooterRight";
    // Servo and sensors
    public static final String SHOOTER_SERVO = "shooterServo";
    public static final String IMU_NAME = "imu";
    public static final String LIMELIGHT = "limelight";

    // ---- How the Control Hub is mounted (must match the real robot!) ----
    // If these are wrong, the IMU heading will be wrong and field-centric
    // driving will go the wrong way.
    public static final RevHubOrientationOnRobot.LogoFacingDirection HUB_LOGO =
            RevHubOrientationOnRobot.LogoFacingDirection.UP;
    public static final RevHubOrientationOnRobot.UsbFacingDirection HUB_USB =
            RevHubOrientationOnRobot.UsbFacingDirection.FORWARD;

    // ---- Devices (filled in by init) ----
    public DcMotorEx frontLeft;
    public DcMotorEx backLeft;
    public DcMotorEx frontRight;
    public DcMotorEx backRight;
    public DcMotorEx intakeLeft;
    public DcMotorEx intakeRight;
    public DcMotorEx shooterLeft;
    public DcMotorEx shooterRight;
    public Servo shooterServo;
    public Limelight3A limelight;
    public IMU imu;
    public VoltageSensor batteryVoltage;
    private java.util.List<LynxModule> hubs;

    /** Looks up every device and sets it to a safe starting state. */
    public void init(HardwareMap hardwareMap) {
        // BULK READS: one message per hub returns every encoder at once.
        // (In the sim this took our loop from ~14 ms to ~4 ms.)
        // AUTO mode is the safe choice: reading the same value twice fetches a
        // fresh bulk read, so values can never go stale. MANUAL is a little
        // faster but values stay cached until clearBulkCache() - forget that
        // once and isBusy()/encoders freeze (the sim reproduces this bug).
        hubs = hardwareMap.getAll(LynxModule.class);
        for (LynxModule hub : hubs) {
            hub.setBulkCachingMode(LynxModule.BulkCachingMode.AUTO);
        }

        frontLeft = hardwareMap.get(DcMotorEx.class, FRONT_LEFT);
        backLeft = hardwareMap.get(DcMotorEx.class, BACK_LEFT);
        frontRight = hardwareMap.get(DcMotorEx.class, FRONT_RIGHT);
        backRight = hardwareMap.get(DcMotorEx.class, BACK_RIGHT);

        // On a mecanum chassis the left motors face the opposite way from the
        // right motors, so we reverse them. Then +power drives every wheel
        // forward.
        frontLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        backLeft.setDirection(DcMotorSimple.Direction.REVERSE);
        frontRight.setDirection(DcMotorSimple.Direction.FORWARD);
        backRight.setDirection(DcMotorSimple.Direction.FORWARD);

        for (DcMotorEx motor : driveMotors()) {
            // BRAKE: wheels resist turning when power is 0 (robot stops faster).
            motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
            motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            motor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }

        intakeLeft = hardwareMap.get(DcMotorEx.class, INTAKE_LEFT);
        intakeRight = hardwareMap.get(DcMotorEx.class, INTAKE_RIGHT);
        shooterLeft = hardwareMap.get(DcMotorEx.class, SHOOTER_LEFT);
        shooterRight = hardwareMap.get(DcMotorEx.class, SHOOTER_RIGHT);
        shooterServo = hardwareMap.get(Servo.class, SHOOTER_SERVO);

        // The right intake and right shooter motors are mounted mirrored, so we
        // reverse them: then +power pulls balls IN and spins the flywheel to SHOOT.
        intakeLeft.setDirection(DcMotorSimple.Direction.FORWARD);
        intakeRight.setDirection(DcMotorSimple.Direction.REVERSE);
        shooterLeft.setDirection(DcMotorSimple.Direction.FORWARD);
        shooterRight.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[] {intakeLeft, intakeRight}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        for (DcMotorEx m : new DcMotorEx[] {shooterLeft, shooterRight}) {
            // The flywheel coasts when off, and the hub holds its speed (velocity PIDF).
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
            m.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            m.setVelocityPIDFCoefficients(Shooter.KP, Shooter.KI, Shooter.KD, Shooter.KF);
        }

        limelight = hardwareMap.get(Limelight3A.class, LIMELIGHT);

        imu = hardwareMap.get(IMU.class, IMU_NAME);
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(HUB_LOGO, HUB_USB)));

        batteryVoltage = hardwareMap.voltageSensor.iterator().next();
    }

    /** Optional in AUTO mode: call at the top of a loop to force fresh reads. */
    public void clearBulkCache() {
        for (LynxModule hub : hubs) {
            hub.clearBulkCache();
        }
    }

    /** The four drive motors in the order frontLeft, backLeft, frontRight, backRight. */
    public DcMotorEx[] driveMotors() {
        return new DcMotorEx[] {frontLeft, backLeft, frontRight, backRight};
    }
}
