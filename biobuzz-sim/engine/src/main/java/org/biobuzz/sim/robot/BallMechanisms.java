package org.biobuzz.sim.robot;

import org.biobuzz.sim.util.FastMath;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.field.Alliance;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.Flower;
import org.biobuzz.sim.game.GameWorld;
import org.biobuzz.sim.hardware.SimServo;
import org.biobuzz.sim.physics.MotorState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.biobuzz.sim.util.Units.inToM;
import static org.biobuzz.sim.util.Units.mmToM;

/**
 * Our robot's ball handling: front intake -> storage -> gate/hood -> flywheel.
 *
 * INTAKE (2 x 1620 RPM): rollers spin; a ball on the floor in front of the
 * bumper is pulled in if the rollers move fast enough. If TWO balls enter at
 * the same moment the intake JAMS: the rollers stall (the motors draw stall
 * current - our code can see it with getCurrent() / getVelocity()) until we
 * reverse the intake, which spits both balls out. It also pulls POLLEN out of
 * a FLOWER's bottom pocket when the mouth is right at the FLOWER (G418.B).
 *
 * STORAGE: up to 4 balls (G407), in order. A new ball takes a moment to roll
 * up to the gate.
 *
 * SERVO: moves at its real speed (slower at the hubs' 5 V than the 6 V spec).
 *   gate mode - a ball passes into the flywheel while the gate is open
 *   hood mode - the servo sets the launch angle; the intake feeds the ball
 *
 * FLYWHEEL (2 x 6000 RPM on one shaft): both motors' torques add up on one
 * heavy wheel. Each shot takes energy out of the wheel (RPM drop), and the
 * motors spin it back up (recovery time). Launch speed ~ wheel surface
 * speed x exitSpeedFactor, plus a little random spread per shot.
 */
public final class BallMechanisms implements ElementCarrier {

    public enum IntakeState { IDLE, RUNNING, JAMMED }

    private final SimRobot robot;
    private final GameWorld world;
    private final Alliance alliance;
    private final Random rng;

    // Intake
    private final MotorState intakeLeft;
    private final MotorState intakeRight;
    private final double intakeMountLeft;
    private final double intakeMountRight;
    private final double rollerRadius;
    private final double rollerInertia;
    private final double mouthWidth;
    private final double mouthDepth;
    private final double minCaptureSpeed;
    private final double jamWindow;
    private final double jamClearTime;
    private final double transferTime;
    private final double flowerReach;
    private final int capacity;

    // Storage: balls in order, with the time each reaches the gate.
    private final List<Ball> storage = new ArrayList<>();
    private final List<Double> readyAt = new ArrayList<>();
    /** Balls stuck in the mouth by a jam. */
    private final List<Ball> jammed = new ArrayList<>();
    private double lastCaptureTime = -10;
    private double reverseTime;
    private IntakeState intakeState = IntakeState.IDLE;

    // Servo
    private final SimServo servo;
    private final boolean hoodMode;
    private final double servoSpeedPerSec;
    private double servoActual;
    private final double gateClosed;
    private final double gateOpen;
    private final double gateOpenFraction;
    private final double hoodMinDeg;
    private final double hoodMaxDeg;

    // Flywheel
    private final MotorState shooterLeft;
    private final MotorState shooterRight;
    private final double shooterMountLeft;
    private final double shooterMountRight;
    private final double flywheelRadius;
    private final double flywheelInertia;
    private final double exitSpeedFactor;
    private final double lossFactor;
    private final double fixedAngleDeg;
    private final double exitForward;
    private final double exitHeight;
    private final double minShotInterval;
    private final double speedSpread;
    private final double angleSpread;
    private double flywheelSpeed; // rad/s, positive = launching direction
    private double lastShotTime = -10;

    // Stats
    public int pickups;
    public int shots;
    public int jams;

    public BallMechanisms(SimRobot robot, GameWorld world, Cfg robotCfg, MotorState intakeLeft, double intakeMountLeft,
                          MotorState intakeRight, double intakeMountRight, MotorState shooterLeft,
                          double shooterMountLeft, MotorState shooterRight, double shooterMountRight,
                          SimServo servo, Random rng) {
        this.robot = robot;
        this.world = world;
        this.alliance = robot.alliance;
        this.rng = rng;
        Cfg in = robotCfg.obj("intake");
        this.intakeLeft = intakeLeft;
        this.intakeRight = intakeRight;
        this.intakeMountLeft = intakeMountLeft;
        this.intakeMountRight = intakeMountRight;
        rollerRadius = mmToM(in.num("rollerDiameterMm")) / 2;
        rollerInertia = in.num("rollerInertia");
        mouthWidth = inToM(in.num("mouthWidth"));
        mouthDepth = inToM(in.num("mouthDepth"));
        minCaptureSpeed = in.num("minCaptureSurfaceSpeed");
        jamWindow = in.num("jamWindowSeconds");
        jamClearTime = in.num("jamClearReverseSeconds");
        transferTime = in.num("transferSeconds");
        flowerReach = inToM(in.num("flowerReachIn"));
        capacity = in.integer("capacity");

        Cfg sh = robotCfg.obj("shooter");
        this.servo = servo;
        hoodMode = sh.str("servoMode").equals("hood");
        Cfg sv = sh.obj("servo");
        // Servo speed: 60 degrees per secondsPer60 at 6 V, slower at the hub's 5 V.
        double degPerSec = 60.0 / sv.num("secondsPer60Deg") * sh.num("hubServoVoltage") / 6.0;
        servoSpeedPerSec = degPerSec / sv.num("rangeDeg");
        gateClosed = sv.num("gateClosedPosition");
        gateOpen = sv.num("gateOpenPosition");
        hoodMinDeg = sv.num("hoodMinAngleDeg");
        hoodMaxDeg = sv.num("hoodMaxAngleDeg");
        gateOpenFraction = sh.num("gateOpenFraction");
        servoActual = hoodMode ? 0.0 : gateClosed;

        this.shooterLeft = shooterLeft;
        this.shooterRight = shooterRight;
        this.shooterMountLeft = shooterMountLeft;
        this.shooterMountRight = shooterMountRight;
        flywheelRadius = mmToM(sh.num("flywheelDiameterMm")) / 2;
        flywheelInertia = sh.num("flywheelInertia");
        exitSpeedFactor = sh.num("exitSpeedFactor");
        lossFactor = sh.num("shotEnergyLossFactor");
        fixedAngleDeg = sh.num("launchAngleDeg");
        exitForward = inToM(sh.num("exitForward"));
        exitHeight = inToM(sh.num("exitHeight"));
        minShotInterval = sh.num("minShotIntervalSeconds");
        speedSpread = sh.num("speedSpreadPct") / 100.0;
        angleSpread = Math.toRadians(sh.num("angleSpreadDeg"));
    }

    /** Pre-loaded balls start in storage, ready at the gate (§10.3.4). */
    public void preload(List<Ball> balls) {
        for (Ball b : balls) {
            storage.add(b);
            readyAt.add(0.0);
        }
    }

    // =====================================================================
    // Step
    // =====================================================================

    public void step(double now, double dt, double batteryVolts) {
        stepServo(dt);
        stepIntake(now, dt, batteryVolts);
        stepFlywheel(dt, batteryVolts);
        tryShoot(now);
        // Held balls ride along with the robot (for drawing and the camera).
        double i = 0;
        for (Ball b : storage) {
            b.setPosition(robot.x - Math.cos(robot.heading) * i * 0.02, robot.y - Math.sin(robot.heading) * i * 0.02,
                    0.12 + i * 0.05);
            i++;
        }
        for (Ball b : jammed) {
            double f = robot.length / 2 + b.radius * 0.6;
            b.setPosition(robot.x + Math.cos(robot.heading) * f, robot.y + Math.sin(robot.heading) * f, b.radius);
        }
    }

    private void stepServo(double dt) {
        double target = servo == null ? Double.NaN : servo.commandedRaw();
        if (Double.isNaN(target)) {
            return; // never commanded: the servo stays limp where it is
        }
        double maxMove = servoSpeedPerSec * dt;
        servoActual += Math.max(-maxMove, Math.min(maxMove, target - servoActual));
    }

    /** Roller surface speed pulling balls in (m/s; negative = spitting out). */
    public double rollerInwardSpeed() {
        double w = (intakeLeft.velocityRadPerSec * intakeMountLeft + intakeRight.velocityRadPerSec * intakeMountRight) / 2;
        return w * rollerRadius;
    }

    private void stepIntake(double now, double dt, double volts) {
        // Roller physics: each motor spins its own roller.
        for (int side = 0; side < 2; side++) {
            MotorState m = side == 0 ? intakeLeft : intakeRight;
            double mount = side == 0 ? intakeMountLeft : intakeMountRight;
            double torque = m.torque(volts);
            if (intakeState == IntakeState.JAMMED && torque * mount > 0) {
                // Jammed: the balls wedge the rollers - they can't turn inward. Stall.
                m.velocityRadPerSec = 0;
                continue;
            }
            double drag = 0.002 * m.velocityRadPerSec; // ESTIMATE: roller bearing/compression drag
            m.velocityRadPerSec += (torque - drag) / rollerInertia * dt;
            m.angleRad += m.velocityRadPerSec * dt;
        }
        double inward = rollerInwardSpeed();

        if (intakeState == IntakeState.JAMMED) {
            if (intakeLeft.appliedPower * intakeMountLeft + intakeRight.appliedPower * intakeMountRight < -0.4) {
                reverseTime += dt;
                if (reverseTime >= jamClearTime) {
                    clearJam();
                }
            } else {
                reverseTime = 0;
            }
            return;
        }
        intakeState = Math.abs(inward) > 0.2 ? IntakeState.RUNNING : IntakeState.IDLE;
        if (inward < minCaptureSpeed) {
            return;
        }
        // Balls on the floor in front of the bumper, inside the mouth.
        double c = Math.cos(robot.heading);
        double s = Math.sin(robot.heading);
        List<Ball> entering = new ArrayList<>();
        for (Ball b : world.balls) {
            if (b.state != Ball.State.FREE || b.z > b.radius * 1.6) {
                continue;
            }
            double dx = b.x - robot.x;
            double dy = b.y - robot.y;
            double lx = dx * c + dy * s;
            double ly = -dx * s + dy * c;
            if (lx > robot.length / 2 - b.radius * 0.5 && lx < robot.length / 2 + mouthDepth + b.radius * 0.5
                    && Math.abs(ly) < mouthWidth / 2 - b.radius * 0.3) {
                entering.add(b);
            }
        }
        // POLLEN in a FLOWER's bottom pocket, right in front of the mouth.
        Flower flower = flowerAtMouth();
        if (entering.isEmpty() && flower != null && flower.pocket() != null && heldCount() < capacity) {
            Ball b = world.takeFromFlower(flower, robot.id);
            if (b != null) {
                accept(b, now);
            }
            return;
        }
        for (Ball b : entering) {
            if (heldCount() >= capacity) {
                return; // full: the ball just gets pushed along (bulldozing, not CONTROL)
            }
            if (now - lastCaptureTime < jamWindow) {
                // Two balls entering at once: JAM.
                world.pickUp(b, robot.id);
                jammed.add(b);
                if (!storage.isEmpty() && readyAt.get(readyAt.size() - 1) > now) {
                    // The one that was still on its way in is stuck too.
                    Ball last = storage.remove(storage.size() - 1);
                    readyAt.remove(readyAt.size() - 1);
                    jammed.add(last);
                }
                intakeState = IntakeState.JAMMED;
                reverseTime = 0;
                jams++;
                return;
            }
            world.pickUp(b, robot.id);
            accept(b, now);
        }
    }

    private void accept(Ball b, double now) {
        storage.add(b);
        readyAt.add(now + transferTime);
        lastCaptureTime = now;
        pickups++;
    }

    private void clearJam() {
        double c = Math.cos(robot.heading);
        double s = Math.sin(robot.heading);
        double f = robot.length / 2 + 0.07;
        int k = 0;
        for (Ball b : jammed) {
            double side = (k++ == 0 ? -1 : 1) * 0.05;
            world.release(b, robot.x + c * f - s * side, robot.y + s * f + c * side, robot.vx + c * 0.6, robot.vy + s * 0.6);
        }
        jammed.clear();
        intakeState = IntakeState.IDLE;
        reverseTime = 0;
    }

    /** A FLOWER whose center is just in front of the intake mouth, or null. */
    private Flower flowerAtMouth() {
        double mx = robot.x + Math.cos(robot.heading) * (robot.length / 2 + mouthDepth / 2);
        double my = robot.y + Math.sin(robot.heading) * (robot.length / 2 + mouthDepth / 2);
        for (Flower f : world.flowers) {
            if (FastMath.hypot(f.x - mx, f.y - my) < flowerReach) {
                return f;
            }
        }
        return null;
    }

    private void stepFlywheel(double dt, double volts) {
        // Both motors turn with the wheel (through their mounting).
        shooterLeft.velocityRadPerSec = flywheelSpeed * shooterMountLeft;
        shooterRight.velocityRadPerSec = flywheelSpeed * shooterMountRight;
        double torque = shooterLeft.torque(volts) * shooterMountLeft + shooterRight.torque(volts) * shooterMountRight;
        // ESTIMATE: air drag (a few watts at full speed) + bearing friction on the wheel.
        double drag = 2e-8 * flywheelSpeed * Math.abs(flywheelSpeed) + 0.002 * Math.tanh(flywheelSpeed);
        flywheelSpeed += (torque - drag) / flywheelInertia * dt;
        shooterLeft.velocityRadPerSec = flywheelSpeed * shooterMountLeft;
        shooterRight.velocityRadPerSec = flywheelSpeed * shooterMountRight;
        shooterLeft.angleRad += shooterLeft.velocityRadPerSec * dt;
        shooterRight.angleRad += shooterRight.velocityRadPerSec * dt;
    }

    /** Is a ball waiting at the gate/flywheel? */
    public boolean ballAtGate(double now) {
        return !storage.isEmpty() && readyAt.get(0) <= now;
    }

    private void tryShoot(double now) {
        if (!ballAtGate(now) || now - lastShotTime < minShotInterval) {
            return;
        }
        boolean feeding;
        if (hoodMode) {
            feeding = rollerInwardSpeed() > minCaptureSpeed * 0.5; // the intake pushes the ball in
        } else {
            double range = gateOpen - gateClosed;
            double openness = range == 0 ? 0 : (servoActual - gateClosed) / range;
            feeding = openness >= gateOpenFraction;
        }
        if (!feeding) {
            return;
        }
        Ball b = storage.remove(0);
        readyAt.remove(0);
        launch(b, now);
    }

    private void launch(Ball b, double now) {
        double surface = Math.max(0, flywheelSpeed) * flywheelRadius;
        double speed = exitSpeedFactor * surface * (1 + rng.nextGaussian() * speedSpread);
        double pitch = Math.toRadians(currentLaunchAngleDeg()) + rng.nextGaussian() * angleSpread;
        double yaw = robot.heading + rng.nextGaussian() * angleSpread;
        double c = Math.cos(robot.heading);
        double s = Math.sin(robot.heading);
        double px = robot.x + c * exitForward;
        double py = robot.y + s * exitForward;
        double horiz = speed * Math.cos(pitch);
        world.launch(b, px, py, exitHeight,
                horiz * Math.cos(yaw) + robot.vx, horiz * Math.sin(yaw) + robot.vy, speed * Math.sin(pitch),
                robot.id, alliance, now);
        // The shot takes energy out of the flywheel: the ball's motion + spin, plus losses.
        double ballEnergy = (5.0 / 6.0) * b.mass * speed * speed; // translation + spin of a hollow ball
        double wheelEnergy = 0.5 * flywheelInertia * flywheelSpeed * flywheelSpeed;
        double left = Math.max(0, wheelEnergy - lossFactor * ballEnergy);
        flywheelSpeed = Math.signum(flywheelSpeed) * Math.sqrt(2 * left / flywheelInertia);
        lastShotTime = now;
        shots++;
        launchLog.add(new double[] {now, b.id, flywheelRpm(), robot.x, robot.y, robot.heading, speed, Math.toDegrees(pitch),
            Math.toDegrees(yaw)});
    }

    /** Every launch: {time, ballId, rpm after, x, y, heading, speed m/s, pitch deg, yaw deg} (for analysis). */
    public final List<double[]> launchLog = new ArrayList<>();

    /** Launch angle right now: fixed in gate mode, set by the hood servo in hood mode. */
    public double currentLaunchAngleDeg() {
        if (!hoodMode) {
            return fixedAngleDeg;
        }
        return hoodMinDeg + Math.max(0, Math.min(1, servoActual)) * (hoodMaxDeg - hoodMinDeg);
    }

    // =====================================================================
    // Readouts
    // =====================================================================

    /** Everything this robot CONTROLS right now (G407: max 4). */
    @Override
    public int heldCount() {
        return storage.size() + jammed.size();
    }

    @Override
    public List<Ball> heldBalls() {
        List<Ball> all = new ArrayList<>(storage);
        all.addAll(jammed);
        return all;
    }

    public IntakeState intakeState() {
        return intakeState;
    }

    public double flywheelRpm() {
        return flywheelSpeed * 60 / (2 * Math.PI);
    }

    public double servoActual() {
        return servoActual;
    }

    public boolean hoodMode() {
        return hoodMode;
    }

    public double flywheelRadius() {
        return flywheelRadius;
    }

    public double exitHeight() {
        return exitHeight;
    }

    /** Test helper: empties storage onto the floor behind the robot. */
    public void dumpAll() {
        double c = Math.cos(robot.heading);
        double s = Math.sin(robot.heading);
        int k = 0;
        for (Ball b : heldBalls()) {
            double back = robot.length / 2 + 0.05 + k++ * 0.08;
            world.release(b, robot.x - c * back, robot.y - s * back, 0, 0);
        }
        storage.clear();
        readyAt.clear();
        jammed.clear();
    }
}
