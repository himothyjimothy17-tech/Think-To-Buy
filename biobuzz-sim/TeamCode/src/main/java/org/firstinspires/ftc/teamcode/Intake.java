package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/**
 * Front intake: two 1620 RPM motors.
 *
 * JAM DETECTION: if two balls enter at once they wedge and stall the
 * rollers. A stalled motor draws a lot of current but barely turns, so we
 * watch for high current + low speed, then reverse briefly to spit the jam
 * out. (Current reads are an extra hub message, so we only check a few
 * times per second.)
 */
public class Intake {
    public static final double IN_POWER = 1.0;
    public static final double OUT_POWER = -0.8;
    /** Current above this while barely moving = jammed (goBILDA stall is ~9 A). */
    public static final double JAM_CURRENT_AMPS = 6.0;
    /** "Barely moving": 1620 RPM motor at 103.8 ticks/rev runs ~2800 ticks/s free. */
    public static final double JAM_MAX_TICKS_PER_SEC = 300;
    public static final double UNJAM_SECONDS = 0.35;
    public static final double CHECK_PERIOD_SECONDS = 0.1;

    private final DcMotorEx left;
    private final DcMotorEx right;
    private final ElapsedTime unjamTimer = new ElapsedTime();
    private final ElapsedTime checkTimer = new ElapsedTime();
    private boolean unjamming;
    private int jamCount;
    /** High-current readings in a row. One alone may just be the motor starting up. */
    private int stalledChecks;
    private boolean running;
    private final ElapsedTime runTimer = new ElapsedTime();
    /** Starting from rest draws stall current for a moment - ignore that. */
    public static final double STARTUP_IGNORE_SECONDS = 0.25;

    public Intake(RobotHardware robot) {
        left = robot.intakeLeft;
        right = robot.intakeRight;
    }

    /** Runs the intake inward, clearing jams automatically. Call every loop. */
    public void intake() {
        if (unjamming) {
            if (unjamTimer.seconds() < UNJAM_SECONDS) {
                setPower(OUT_POWER);
                return;
            }
            unjamming = false;
            running = false; // restarting: ignore the start-up current again
        }
        if (!running) {
            running = true;
            runTimer.reset();
        }
        setPower(IN_POWER);
        if (runTimer.seconds() > STARTUP_IGNORE_SECONDS && checkTimer.seconds() > CHECK_PERIOD_SECONDS) {
            checkTimer.reset();
            boolean stalled = left.getCurrent(CurrentUnit.AMPS) > JAM_CURRENT_AMPS
                    && Math.abs(left.getVelocity()) < JAM_MAX_TICKS_PER_SEC;
            stalledChecks = stalled ? stalledChecks + 1 : 0;
            if (stalledChecks >= 2) {
                unjamming = true;
                jamCount++;
                stalledChecks = 0;
                unjamTimer.reset();
            }
        }
    }

    public void outtake() {
        running = false;
        setPower(OUT_POWER);
    }

    public void stop() {
        running = false;
        setPower(0);
    }

    public boolean isUnjamming() {
        return unjamming;
    }

    public int getJamCount() {
        return jamCount;
    }

    private void setPower(double p) {
        left.setPower(p);
        right.setPower(p);
    }
}
