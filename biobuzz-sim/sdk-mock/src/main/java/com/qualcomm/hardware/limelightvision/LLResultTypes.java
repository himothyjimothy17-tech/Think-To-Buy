package com.qualcomm.hardware.limelightvision;

import org.biobuzz.simhooks.SimOnly;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;

import java.util.Collections;
import java.util.List;

/** The kinds of target a Limelight pipeline can report. */
public class LLResultTypes {

    /** Shared fields for anything with a position in the image. */
    @SimOnly
    public abstract static class SimTarget {
        protected final double tx;
        protected final double ty;
        protected final double ta;

        SimTarget(double tx, double ty, double ta) {
            this.tx = tx;
            this.ty = ty;
            this.ta = ta;
        }
    }

    /** An AprilTag. */
    public static class FiducialResult {
        private final int id;
        private final double tx;
        private final double ty;
        private final double ta;
        private final Pose3D robotPoseField;
        private final Pose3D targetPoseCamera;
        private final Pose3D targetPoseRobot;

        @SimOnly
        public FiducialResult(int id, double tx, double ty, double ta, Pose3D robotPoseField,
                              Pose3D targetPoseCamera, Pose3D targetPoseRobot) {
            this.id = id;
            this.tx = tx;
            this.ty = ty;
            this.ta = ta;
            this.robotPoseField = robotPoseField;
            this.targetPoseCamera = targetPoseCamera;
            this.targetPoseRobot = targetPoseRobot;
        }

        public int getFiducialId() { return id; }

        public String getFamily() { return "36h11"; }

        public List<List<Double>> getTargetCorners() { return Collections.emptyList(); }

        public double getSkew() { return 0; }

        public Pose3D getCameraPoseTargetSpace() { return null; }

        public Pose3D getRobotPoseFieldSpace() { return robotPoseField; }

        public Pose3D getRobotPoseTargetSpace() { return null; }

        public Pose3D getTargetPoseCameraSpace() { return targetPoseCamera; }

        public Pose3D getTargetPoseRobotSpace() { return targetPoseRobot; }

        public double getTargetArea() { return ta; }

        public double getTargetXPixels() { return 320 + tx * 640 / 54.5; }

        public double getTargetYPixels() { return 240 - ty * 480 / 42.0; }

        public double getTargetXDegrees() { return tx; }

        public double getTargetYDegrees() { return ty; }

        public double getTargetXDegreesNoCrosshair() { return tx; }

        public double getTargetYDegreesNoCrosshair() { return ty; }
    }

    /** An object found by the neural-network detector (our POLLEN / NECTAR model). */
    public static class DetectorResult {
        private final String className;
        private final int classId;
        private final double confidence;
        private final double tx;
        private final double ty;
        private final double ta;

        @SimOnly
        public DetectorResult(String className, int classId, double confidence, double tx, double ty, double ta) {
            this.className = className;
            this.classId = classId;
            this.confidence = confidence;
            this.tx = tx;
            this.ty = ty;
            this.ta = ta;
        }

        public String getClassName() { return className; }

        public int getClassId() { return classId; }

        public double getConfidence() { return confidence; }

        public List<List<Double>> getTargetCorners() { return Collections.emptyList(); }

        public double getTargetArea() { return ta; }

        public double getTargetXPixels() { return 320 + tx * 640 / 54.5; }

        public double getTargetYPixels() { return 240 - ty * 480 / 42.0; }

        public double getTargetXDegrees() { return tx; }

        public double getTargetYDegrees() { return ty; }

        public double getTargetXDegreesNoCrosshair() { return tx; }

        public double getTargetYDegreesNoCrosshair() { return ty; }
    }

    /** A color-threshold blob (not simulated yet - always empty). */
    public static class ColorResult {
        @SimOnly
        public ColorResult() {
        }

        public List<List<Double>> getTargetCorners() { return Collections.emptyList(); }

        public Pose3D getCameraPoseTargetSpace() { return null; }

        public Pose3D getRobotPoseFieldSpace() { return null; }

        public Pose3D getRobotPoseTargetSpace() { return null; }

        public Pose3D getTargetPoseCameraSpace() { return null; }

        public Pose3D getTargetPoseRobotSpace() { return null; }

        public double getTargetArea() { return 0; }

        public double getTargetXPixels() { return 0; }

        public double getTargetYPixels() { return 0; }

        public double getTargetXDegrees() { return 0; }

        public double getTargetYDegrees() { return 0; }

        public double getTargetXDegreesNoCrosshair() { return 0; }

        public double getTargetYDegreesNoCrosshair() { return 0; }
    }

    /** Barcode results (not simulated - always empty). */
    public static class BarcodeResult {
        @SimOnly
        public BarcodeResult() {
        }
    }

    /** Classifier results (not simulated - always empty). */
    public static class ClassifierResult {
        @SimOnly
        public ClassifierResult() {
        }
    }
}
