package org.biobuzz.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.biobuzz.sim.config.SimConfig;
import org.biobuzz.sim.core.Simulation;
import org.firstinspires.ftc.teamcode.RobotHardware;
import org.firstinspires.ftc.teamcode.Shooter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Paths;
import java.util.List;

/**
 * Experiment: flywheel velocity PIDF tuning. For each (P, I) it measures
 * time to reach +/-60 RPM of 3500, overshoot, and recovery after a shot.
 * Run with: ./gradlew :engine:test --tests '*FlywheelTune*' -i
 */
@Tag("experiment")
@Timeout(900)
class FlywheelTuneTest {
    static volatile double kp;
    static volatile double ki;
    static volatile double settle;
    static volatile double peak;
    static volatile double recover;
    static volatile double dip;

    public static class Tune extends LinearOpMode {
        @Override
        public void runOpMode() {
            RobotHardware r = new RobotHardware();
            r.init(hardwareMap);
            for (DcMotorEx m : new DcMotorEx[] {r.shooterLeft, r.shooterRight}) {
                m.setVelocityPIDFCoefficients(kp, ki, 0, Shooter.KF);
            }
            Shooter s = new Shooter(r);
            waitForStart();
            s.setTargetRpm(3500);
            ElapsedTime t = new ElapsedTime();
            settle = -1;
            peak = 0;
            double inBandSince = -1;
            while (opModeIsActive() && t.seconds() < 4) {
                double rpm = s.getRpm();
                peak = Math.max(peak, rpm);
                if (Math.abs(rpm - 3500) < 60) {
                    if (inBandSince < 0) {
                        inBandSince = t.seconds();
                    }
                    if (settle < 0 && t.seconds() - inBandSince > 0.3) {
                        settle = inBandSince;
                    }
                } else {
                    inBandSince = -1;
                }
            }
            // One shot, then time until back within 60 RPM.
            s.openGate();
            ElapsedTime shot = new ElapsedTime();
            dip = 99999;
            recover = -1;
            boolean dipped = false;
            while (opModeIsActive() && shot.seconds() < 3) {
                double rpm = s.getRpm();
                dip = Math.min(dip, rpm);
                if (rpm < 3400) {
                    dipped = true;
                    s.closeGate();
                }
                if (dipped && recover < 0 && Math.abs(rpm - 3500) < 60) {
                    recover = shot.seconds();
                }
            }
        }
    }

    @Test
    void sweepGains() throws Exception {
        System.out.println("    P     I   settle(s)  peakRPM  shotDip  recover(s)");
        for (double p : new double[] {10, 30, 60}) {
            for (double i : new double[] {0.3, 1, 3}) {
                kp = p;
                ki = i;
                Simulation sim = new Simulation(SimConfig.load(Paths.get("config"), List.of()), List.of());
                sim.initOpMode(Tune.class, false);
                sim.runFor(0.3);
                sim.command("start", null);
                sim.runFor(7.5);
                System.out.printf("%5.0f %5.1f %10.2f %8.0f %8.0f %10.2f%n", p, i, settle, peak, dip, recover);
                sim.shutdown();
            }
        }
    }
}
