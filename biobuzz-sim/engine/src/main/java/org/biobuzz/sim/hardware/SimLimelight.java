package org.biobuzz.sim.hardware;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;
import com.qualcomm.hardware.limelightvision.LLStatus;
import com.qualcomm.hardware.limelightvision.Limelight3A;

import org.biobuzz.sim.config.Cfg;
import org.biobuzz.sim.game.Ball;
import org.biobuzz.sim.game.GameWorld;
import org.biobuzz.sim.game.Hive;
import org.biobuzz.sim.robot.SimRobot;
import org.biobuzz.simhooks.SimHooks;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.biobuzz.sim.util.Units.inToM;

/**
 * A simulated Limelight 3A. Every frame it looks at the simulated field
 * from where it is mounted on our robot and reports what a real one would:
 *
 *   pipeline "apriltag": the tags under the HIVE CELLS (IDs 30-45, facing
 *       DOWN - you only see them from below), plus the robot's field pose
 *       ("botpose") computed from them. Pose noise grows with distance.
 *   pipeline "detector": POLLEN / NECTAR balls, as a neural-network
 *       detector would report them (class name, confidence, tx, ty, ta).
 *
 * Realistic limits: field of view (54.5 x 42 degrees), range, other robots
 * blocking the view, occasional missed detections, angle noise, and LATENCY:
 * a result describes the world as it was ~25 ms ago.
 *
 * FIELD COORDINATES for botpose: this simulator's frame (meters, origin at
 * the field center, +x toward the blue wall, +y away from the audience).
 * TODO: switch to FIRST's official BIOBUZZ field map once it's published.
 */
public final class SimLimelight extends Limelight3A {

    private final String configName;
    private final SimRobot robot;
    private final Supplier<List<SimRobot>> robots;
    private final GameWorld world;
    private final Cfg tagsCfg;
    private final Random rng;
    private final List<String> pipelines = new ArrayList<>();
    private final double mountForward;
    private final double mountHeight;
    private final double pitch;
    private final double hFov;
    private final double vFov;
    private final double framePeriod;
    private final double latency;
    private final double angleNoise;
    private final double poseNoisePerMeter;
    private final double tagRange;
    private final double ballRange;
    private final double missRate;
    private final double tagSize;

    private boolean running;
    private int pipeline;
    private double nextFrameTime;
    private double lastProducedTime = -1;
    private double mt2Yaw = Double.NaN;
    /** Results computed but not yet "arrived" (latency). */
    private final Deque<Object[]> inFlight = new ArrayDeque<>();
    private LLResult latest;

    public SimLimelight(String configName, SimRobot robot, Supplier<List<SimRobot>> robots, GameWorld world,
                        Cfg camera, Cfg tagsCfg, Random rng) {
        this.configName = configName;
        this.robot = robot;
        this.robots = robots;
        this.world = world;
        this.tagsCfg = tagsCfg;
        this.rng = rng;
        for (Object o : (List<?>) camera.raw().get("pipelines")) {
            pipelines.add(String.valueOf(o));
        }
        mountForward = inToM(camera.num("mountForward"));
        mountHeight = inToM(camera.num("mountHeight"));
        pitch = Math.toRadians(camera.num("pitchDeg"));
        hFov = Math.toRadians(camera.num("horizontalFovDeg"));
        vFov = Math.toRadians(camera.num("verticalFovDeg"));
        framePeriod = 1.0 / camera.num("fps");
        latency = camera.num("latencyMs") / 1000.0;
        angleNoise = camera.num("angleNoiseDeg");
        poseNoisePerMeter = inToM(camera.num("poseNoiseIn"));
        tagRange = inToM(camera.num("tagMaxRangeIn"));
        ballRange = inToM(camera.num("ballMaxRangeIn"));
        missRate = camera.num("missRate");
        tagSize = inToM(tagsCfg.num("size"));
    }

    public String configName() {
        return configName;
    }

    /** Called every physics step by the simulation. */
    public void step(double now) {
        if (running && now >= nextFrameTime) {
            nextFrameTime = now + framePeriod;
            inFlight.add(new Object[] {now + latency, computeFrame(now)});
        }
        while (!inFlight.isEmpty() && (double) inFlight.peek()[0] <= now) {
            Object[] f = inFlight.poll();
            latest = (LLResult) f[1];
            lastProducedTime = (double) f[0];
        }
    }

    // =====================================================================
    // What the camera sees
    // =====================================================================

    private LLResult computeFrame(double now) {
        double c = Math.cos(robot.heading);
        double s = Math.sin(robot.heading);
        double camX = robot.x + c * mountForward;
        double camY = robot.y + s * mountForward;
        double camZ = mountHeight;
        // Camera axes (field frame).
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double[] fwd = {c * cp, s * cp, sp};
        double[] left = {-s, c, 0};
        double[] up = {-c * sp, -s * sp, cp};

        String type = pipeline < pipelines.size() ? pipelines.get(pipeline) : "apriltag";
        List<LLResultTypes.FiducialResult> tags = new ArrayList<>();
        List<LLResultTypes.DetectorResult> dets = new ArrayList<>();
        double bestTa = -1;
        double bestTx = 0;
        double bestTy = 0;
        Pose3D botpose = null;
        Pose3D botposeMt2 = null;
        double tagDistSum = 0;

        if (type.equals("apriltag")) {
            for (Hive h : world.hives.values()) {
                for (double[] t : h.aprilTags(tagsCfg)) {
                    double dx = t[1] - camX;
                    double dy = t[2] - camY;
                    double dz = t[3] - camZ;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    // The tag must face the camera (normal pointing back at us).
                    double facing = -(dx * t[4] + dy * t[5] + dz * t[6]) / dist;
                    if (dist > tagRange || facing < Math.cos(Math.toRadians(70))) {
                        continue;
                    }
                    double[] ang = project(dx, dy, dz, fwd, left, up);
                    if (ang == null || occluded(camX, camY, camZ, t[1], t[2], t[3]) || rng.nextDouble() < missRate) {
                        continue;
                    }
                    double ta = 100.0 * (tagSize * tagSize * facing / (dist * dist)) / (hFov * vFov);
                    double tx = ang[0] + rng.nextGaussian() * angleNoise;
                    double ty = ang[1] + rng.nextGaussian() * angleNoise;
                    Pose3D camSpace = new Pose3D(new Position(DistanceUnit.METER,
                            -(dx * left[0] + dy * left[1]), -(dx * up[0] + dy * up[1] + dz * up[2]),
                            dx * fwd[0] + dy * fwd[1] + dz * fwd[2], 0),
                            new YawPitchRollAngles(AngleUnit.DEGREES, 0, 0, 0, 0));
                    double rx = t[1] - robot.x;
                    double ry = t[2] - robot.y;
                    Pose3D robotSpace = new Pose3D(new Position(DistanceUnit.METER,
                            rx * c + ry * s, -rx * s + ry * c, t[3], 0),
                            new YawPitchRollAngles(AngleUnit.DEGREES, 0, 0, 0, 0));
                    Pose3D pose = noisyPose(dist, false);
                    tags.add(new LLResultTypes.FiducialResult((int) t[0], tx, ty, ta, pose, camSpace, robotSpace));
                    tagDistSum += dist;
                    if (ta > bestTa) {
                        bestTa = ta;
                        bestTx = tx;
                        bestTy = ty;
                    }
                }
            }
            if (!tags.isEmpty()) {
                double avg = tagDistSum / tags.size();
                // More tags in view = a better fix.
                botpose = noisyPose(avg / Math.sqrt(tags.size()), false);
                botposeMt2 = Double.isNaN(mt2Yaw) ? null : noisyPose(avg * 0.6 / Math.sqrt(tags.size()), true);
            }
        } else {
            for (Ball b : world.balls) {
                if (b.state != Ball.State.FREE && b.state != Ball.State.IN_FLOWER) {
                    continue;
                }
                double dx = b.x - camX;
                double dy = b.y - camY;
                double dz = b.z - camZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > ballRange) {
                    continue;
                }
                double[] ang = project(dx, dy, dz, fwd, left, up);
                if (ang == null || occluded(camX, camY, camZ, b.x, b.y, b.z) || rng.nextDouble() < missRate) {
                    continue;
                }
                double angular = 2 * Math.atan(b.radius / dist);
                double ta = 100.0 * (Math.PI / 4 * angular * angular) / (hFov * vFov);
                double conf = Math.max(0.3, Math.min(0.99, 0.9 + rng.nextGaussian() * 0.05 - dist * 0.03));
                int classId = b.kind.ordinal();
                double tx = ang[0] + rng.nextGaussian() * angleNoise;
                double ty = ang[1] + rng.nextGaussian() * angleNoise;
                dets.add(new LLResultTypes.DetectorResult(b.kind.label(), classId, conf, tx, ty, ta));
                if (ta > bestTa) {
                    bestTa = ta;
                    bestTx = tx;
                    bestTy = ty;
                }
            }
            dets.sort((a, b) -> Double.compare(b.getTargetArea(), a.getTargetArea()));
        }
        long stamp = (long) (now * 1e9);
        boolean valid = bestTa >= 0;
        return new LLResult(stamp, stamp, pipeline, type.equals("apriltag") ? "pipe_fiducial" : "pipe_neuraldetector",
                valid ? bestTx : 0, valid ? bestTy : 0, valid ? bestTa : 0, valid,
                latency * 700, latency * 300, tags, dets, botpose, botposeMt2, tags.size(),
                tags.isEmpty() ? 0 : tagDistSum / tags.size());
    }

    /** Angles (tx right-positive, ty up-positive, degrees) of a direction, or null if out of view. */
    private double[] project(double dx, double dy, double dz, double[] fwd, double[] left, double[] up) {
        double f = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];
        if (f <= 0) {
            return null;
        }
        double l = dx * left[0] + dy * left[1] + dz * left[2];
        double u = dx * up[0] + dy * up[1] + dz * up[2];
        double tx = Math.atan2(-l, f);
        double ty = Math.atan2(u, f);
        if (Math.abs(tx) > hFov / 2 || Math.abs(ty) > vFov / 2) {
            return null;
        }
        return new double[] {Math.toDegrees(tx), Math.toDegrees(ty)};
    }

    /** Is the straight line from the camera to the target blocked by another robot? */
    private boolean occluded(double ax, double ay, double az, double bx, double by, double bz) {
        for (SimRobot r : robots.get()) {
            if (r == robot) {
                continue;
            }
            // Segment vs the robot's box, in the robot's frame (slab test).
            double c = Math.cos(r.heading);
            double s = Math.sin(r.heading);
            double[] p0 = {(ax - r.x) * c + (ay - r.y) * s, -(ax - r.x) * s + (ay - r.y) * c, az};
            double[] p1 = {(bx - r.x) * c + (by - r.y) * s, -(bx - r.x) * s + (by - r.y) * c, bz};
            double[] lo = {-r.length / 2, -r.width / 2, 0};
            double[] hi = {r.length / 2, r.width / 2, r.height};
            double t0 = 0;
            double t1 = 1;
            boolean hit = true;
            for (int k = 0; k < 3 && hit; k++) {
                double d = p1[k] - p0[k];
                if (Math.abs(d) < 1e-12) {
                    if (p0[k] < lo[k] || p0[k] > hi[k]) {
                        hit = false;
                    }
                } else {
                    double ta = (lo[k] - p0[k]) / d;
                    double tb = (hi[k] - p0[k]) / d;
                    t0 = Math.max(t0, Math.min(ta, tb));
                    t1 = Math.min(t1, Math.max(ta, tb));
                    if (t0 > t1) {
                        hit = false;
                    }
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** The robot's true pose plus noise that grows with distance (meters, degrees). */
    private Pose3D noisyPose(double distance, boolean megaTag2) {
        double sigma = poseNoisePerMeter * Math.max(0.3, distance);
        double x = robot.x + rng.nextGaussian() * sigma;
        double y = robot.y + rng.nextGaussian() * sigma;
        double yawDeg = megaTag2 ? mt2Yaw
                : Math.toDegrees(robot.heading) + rng.nextGaussian() * 1.5 * Math.max(0.3, distance);
        return new Pose3D(new Position(DistanceUnit.METER, x, y, 0, 0),
                new YawPitchRollAngles(AngleUnit.DEGREES, AngleUnit.normalizeDegrees(yawDeg), 0, 0, 0));
    }

    // =====================================================================
    // Limelight3A API
    // =====================================================================

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void pause() {
        running = false;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void setPollRateHz(int rate) {
    }

    @Override
    public long getTimeSinceLastUpdate() {
        return lastProducedTime < 0 ? Long.MAX_VALUE : (long) ((SimHooks.nanoTime() / 1e9 - lastProducedTime) * 1000);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public LLResult getLatestResult() {
        SimHooks.charge(50_000L); // reading the cached result is quick (ESTIMATE 0.05 ms)
        if (latest == null) {
            return null;
        }
        // Rebuild with the current time so getStaleness() is right.
        LLResult r = latest;
        return new LLResult(r.getControlHubTimeStampNanos(), SimHooks.nanoTime(), r.getPipelineIndex(),
                r.getPipelineType(), r.getTx(), r.getTy(), r.getTa(), r.isValid(), r.getCaptureLatency(),
                r.getTargetingLatency(), r.getFiducialResults(), r.getDetectorResults(), r.getBotpose(),
                r.getBotpose_MT2(), r.getBotposeTagCount(), r.getBotposeAvgDist());
    }

    @Override
    public LLStatus getStatus() {
        return new LLStatus(1.0 / framePeriod, pipeline, pipelines.get(Math.min(pipeline, pipelines.size() - 1)));
    }

    @Override
    public boolean pipelineSwitch(int index) {
        if (index < 0 || index >= pipelines.size()) {
            return false;
        }
        pipeline = index;
        inFlight.clear();
        return true;
    }

    @Override
    public boolean updateRobotOrientation(double yaw) {
        mt2Yaw = yaw;
        return true;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.LimelightVision;
    }

    @Override
    public String getDeviceName() {
        return "Limelight 3A";
    }

    @Override
    public String getConnectionInfo() {
        return "USB (simulated)";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        running = false;
        pipeline = 0;
        latest = null;
        inFlight.clear();
    }

    @Override
    public void close() {
        stop();
    }
}
