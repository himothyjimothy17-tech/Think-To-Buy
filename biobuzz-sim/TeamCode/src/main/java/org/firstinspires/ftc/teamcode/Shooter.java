package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.ElapsedTime;

/**
 * The flywheel shooter: two 6000 RPM motors on one 96 mm flywheel, plus the
 * shooter servo (a FEEDER GATE by default; see HOOD_MODE).
 *
 * HOW WE SHOOT (and why it's written this way):
 *  - The hub holds the flywheel RPM for us with its built-in velocity PIDF
 *    (RUN_USING_ENCODER + setVelocity). F = 32767 / max ticks per second is
 *    REV's recommended starting point; P and I fix the rest.
 *  - A shot takes energy out of the wheel (the RPM drops), so we only open
 *    the gate when the wheel is back within TOLERANCE of the target.
 *  - The gate servo takes real time to open (~0.3 s at 5 V), so opening it
 *    once and letting a few balls stream through is faster than open/close
 *    per ball. fire() does exactly that: it holds the gate open while the
 *    wheel stays at speed and closes it when the wheel dips.
 */
public class Shooter {

    /** false = the servo is a feeder gate; true = it's an adjustable hood. Must match the real robot. */
    public static final boolean HOOD_MODE = false;

    // goBILDA 6000 RPM motor: 28 encoder ticks per revolution, 1:1 to the flywheel.
    public static final double TICKS_PER_REV = 28.0;
    public static final double MAX_TICKS_PER_SEC = 6000.0 / 60.0 * TICKS_PER_REV;

    // Hub velocity PIDF (REV units). F from REV's formula (the power needed per
    // tick/s). P and I were tuned in the simulator (FlywheelTuneTest):
    //   SDK default P=10 I=3 : settles in 2.9 s, overshoots 16%
    //   P=60 I=1 (ours)      : settles in 0.8 s, overshoots 1.6%, recovers from a shot in 0.4 s
    // RE-CHECK ON THE REAL ROBOT - the flywheel's weight is still an ESTIMATE.
    public static final double KF = 32767.0 / MAX_TICKS_PER_SEC;
    public static final double KP = 60.0;
    public static final double KI = 1.0;
    public static final double KD = 0.0;

    public static final double GATE_CLOSED = 0.0;
    public static final double GATE_OPEN = 0.3;

    /** How close to the target RPM the wheel must be before we feed a ball. */
    public static final double TOLERANCE_RPM = 60;

    private final DcMotorEx left;
    private final DcMotorEx right;
    private final Servo servo;
    private double targetRpm;
    private boolean gateOpen;
    private final ElapsedTime gateTimer = new ElapsedTime();

    public Shooter(RobotHardware robot) {
        left = robot.shooterLeft;
        right = robot.shooterRight;
        servo = robot.shooterServo;
        closeGate();
    }

    /** Spins the flywheel to this RPM (0 = off). */
    public void setTargetRpm(double rpm) {
        targetRpm = rpm;
        double tps = rpm / 60.0 * TICKS_PER_REV;
        if (rpm <= 0) {
            left.setPower(0);
            right.setPower(0);
        } else {
            left.setVelocity(tps);
            right.setVelocity(tps);
        }
    }

    public double getTargetRpm() {
        return targetRpm;
    }

    /** Measured flywheel RPM (from the left motor's encoder). */
    public double getRpm() {
        return left.getVelocity() / TICKS_PER_REV * 60.0;
    }

    public boolean atSpeed() {
        return targetRpm > 0 && Math.abs(getRpm() - targetRpm) < TOLERANCE_RPM;
    }

    public void openGate() {
        if (!HOOD_MODE) {
            servo.setPosition(GATE_OPEN);
            if (!gateOpen) {
                gateTimer.reset();
            }
            gateOpen = true;
        }
    }

    public void closeGate() {
        if (!HOOD_MODE) {
            servo.setPosition(GATE_CLOSED);
            gateOpen = false;
        }
    }

    public boolean isGateOpen() {
        return gateOpen;
    }

    /** Hood mode only: 0.0 = lowest launch angle, 1.0 = highest. */
    public void setHood(double position) {
        if (HOOD_MODE) {
            servo.setPosition(position);
        }
    }

    /**
     * Call every loop while you want to shoot: opens the gate when the wheel
     * is at speed, closes it when the RPM sags after a shot.
     */
    public void fire() {
        double rpm = getRpm();
        if (!gateOpen && Math.abs(rpm - targetRpm) < TOLERANCE_RPM) {
            openGate();
        } else if (gateOpen && rpm < targetRpm - 3 * TOLERANCE_RPM) {
            closeGate();
        }
    }

    /**
     * RPM needed to land a ball at a horizontal distance and height, for our
     * fixed launch angle. Physics: projectile motion solved for the launch
     * speed, then divided by (exit speed factor x wheel radius). The factor
     * and extra fudge come from the simulator; re-tune on the real robot.
     *
     * @param distanceIn horizontal distance from the shooter to the target (inches)
     * @param heightIn   target height minus launch height (inches)
     */
    public static double rpmForShot(double distanceIn, double heightIn, double launchAngleDeg) {
        final double g = 386.09; // in/s^2
        final double exitSpeedFactor = 0.5;
        final double wheelRadiusIn = 96.0 / 25.4 / 2.0;
        final double dragFudge = 1.10; // the balls have holes; air drag costs ~10% at these ranges
        double a = Math.toRadians(launchAngleDeg);
        double denom = 2 * Math.cos(a) * Math.cos(a) * (distanceIn * Math.tan(a) - heightIn);
        if (denom <= 0) {
            return Double.NaN; // too close / too high for this angle
        }
        double v = Math.sqrt(g * distanceIn * distanceIn / denom) * dragFudge;
        double wheelSurface = v / exitSpeedFactor;
        return wheelSurface / wheelRadiusIn / (2 * Math.PI) * 60.0;
    }
}
