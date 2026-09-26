package org.firstinspires.ftc.teamcode;

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
    public IMU imu;
    public VoltageSensor batteryVoltage;

    /** Looks up every device and sets it to a safe starting state. */
    public void init(HardwareMap hardwareMap) {
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
        // TODO(stage 3): set intake/shooter directions and modes once the
        // mechanisms are simulated.

        imu = hardwareMap.get(IMU.class, IMU_NAME);
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(HUB_LOGO, HUB_USB)));

        batteryVoltage = hardwareMap.voltageSensor.iterator().next();
    }

    /** The four drive motors in the order frontLeft, backLeft, frontRight, backRight. */
    public DcMotorEx[] driveMotors() {
        return new DcMotorEx[] {frontLeft, backLeft, frontRight, backRight};
    }
}
