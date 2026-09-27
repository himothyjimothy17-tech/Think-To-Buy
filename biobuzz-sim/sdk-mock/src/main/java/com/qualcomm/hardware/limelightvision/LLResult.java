package com.qualcomm.hardware.limelightvision;

import org.biobuzz.simhooks.SimOnly;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;

import java.util.Collections;
import java.util.List;

/**
 * One frame of Limelight output. getTx()/getTy()/getTa() describe the
 * primary (biggest) target; the lists hold every target of that type.
 */
public class LLResult {
    private final long controlHubTimeStampNanos;
    private final int pipelineIndex;
    private final String pipelineType;
    private final double tx;
    private final double ty;
    private final double ta;
    private final boolean valid;
    private final double captureLatency;
    private final double targetingLatency;
    private final double timestamp;
    private final List<LLResultTypes.FiducialResult> fiducials;
    private final List<LLResultTypes.DetectorResult> detections;
    private final Pose3D botpose;
    private final Pose3D botposeMt2;
    private final int botposeTagCount;
    private final double botposeAvgDist;
    private final long nowNanosAtRead;

    @SimOnly
    public LLResult(long controlHubTimeStampNanos, long nowNanosAtRead, int pipelineIndex, String pipelineType,
                    double tx, double ty, double ta, boolean valid, double captureLatency, double targetingLatency,
                    List<LLResultTypes.FiducialResult> fiducials, List<LLResultTypes.DetectorResult> detections,
                    Pose3D botpose, Pose3D botposeMt2, int botposeTagCount, double botposeAvgDist) {
        this.controlHubTimeStampNanos = controlHubTimeStampNanos;
        this.nowNanosAtRead = nowNanosAtRead;
        this.pipelineIndex = pipelineIndex;
        this.pipelineType = pipelineType;
        this.tx = tx;
        this.ty = ty;
        this.ta = ta;
        this.valid = valid;
        this.captureLatency = captureLatency;
        this.targetingLatency = targetingLatency;
        this.timestamp = controlHubTimeStampNanos / 1e9;
        this.fiducials = fiducials;
        this.detections = detections;
        this.botpose = botpose;
        this.botposeMt2 = botposeMt2;
        this.botposeTagCount = botposeTagCount;
        this.botposeAvgDist = botposeAvgDist;
    }

    public long getControlHubTimeStamp() { return controlHubTimeStampNanos / 1_000_000L; }

    public long getControlHubTimeStampNanos() { return controlHubTimeStampNanos; }

    /** Milliseconds since this result was produced. */
    public long getStaleness() { return (nowNanosAtRead - controlHubTimeStampNanos) / 1_000_000L; }

    public List<LLResultTypes.BarcodeResult> getBarcodeResults() { return Collections.emptyList(); }

    public List<LLResultTypes.ClassifierResult> getClassifierResults() { return Collections.emptyList(); }

    public List<LLResultTypes.DetectorResult> getDetectorResults() { return detections; }

    public List<LLResultTypes.FiducialResult> getFiducialResults() { return fiducials; }

    public List<LLResultTypes.ColorResult> getColorResults() { return Collections.emptyList(); }

    public double getFocusMetric() { return 0; }

    /** Robot pose on the field from AprilTags (MegaTag 1), or null if no tags. */
    public Pose3D getBotpose() { return botpose; }

    /** Robot pose from AprilTags + the yaw you gave updateRobotOrientation() (MegaTag 2). */
    public Pose3D getBotpose_MT2() { return botposeMt2; }

    public double[] getStddevMt1() { return new double[6]; }

    public double[] getStddevMt2() { return new double[6]; }

    public int getBotposeTagCount() { return botposeTagCount; }

    public double getBotposeSpan() { return 0; }

    public double getBotposeAvgDist() { return botposeAvgDist; }

    public double getBotposeAvgArea() { return ta; }

    public double[] getPythonOutput() { return new double[0]; }

    public double getCaptureLatency() { return captureLatency; }

    public String getPipelineType() { return pipelineType; }

    public double getTx() { return tx; }

    public double getTy() { return ty; }

    public double getTxNC() { return tx; }

    public double getTyNC() { return ty; }

    public double getTa() { return ta; }

    public int getPipelineIndex() { return pipelineIndex; }

    public double getTargetingLatency() { return targetingLatency; }

    public double getTimestamp() { return timestamp; }

    public boolean isValid() { return valid; }

    public double getParseLatency() { return 0.1; }

    @Override
    public String toString() {
        return String.format("LLResult(valid=%s tx=%.2f ty=%.2f ta=%.2f tags=%d detections=%d)",
                valid, tx, ty, ta, fiducials.size(), detections.size());
    }
}
