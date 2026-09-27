package com.qualcomm.hardware.limelightvision;

import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

/** Limelight health information. */
public class LLStatus {
    private final double fps;
    private final int pipelineIndex;
    private final String pipelineType;

    public LLStatus() {
        this(0, 0, "");
    }

    @org.biobuzz.simhooks.SimOnly
    public LLStatus(double fps, int pipelineIndex, String pipelineType) {
        this.fps = fps;
        this.pipelineIndex = pipelineIndex;
        this.pipelineType = pipelineType;
    }

    public Quaternion getCameraQuat() { return Quaternion.identityQuaternion(); }

    public int getCid() { return 0; }

    public double getCpu() { return 35; }

    public double getFinalYaw() { return 0; }

    public double getFps() { return fps; }

    public int getHwType() { return 3; }

    public String getName() { return "limelight"; }

    public int getPipeImgCount() { return 0; }

    public int getPipelineIndex() { return pipelineIndex; }

    public String getPipelineType() { return pipelineType; }

    public double getRam() { return 40; }

    public int getSnapshotMode() { return 0; }

    public double getTemp() { return 45; }

    @Override
    public String toString() {
        return "LLStatus(fps=" + fps + ", pipeline=" + pipelineIndex + ")";
    }
}
