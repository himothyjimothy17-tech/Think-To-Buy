package com.qualcomm.hardware.limelightvision;

import com.qualcomm.robotcore.hardware.HardwareDevice;

import org.biobuzz.simhooks.SimOnly;

/**
 * The Limelight 3A camera (plugged into the Control Hub's USB port).
 *
 * Typical use:
 *   Limelight3A ll = hardwareMap.get(Limelight3A.class, "limelight");
 *   ll.pipelineSwitch(0);
 *   ll.start();
 *   LLResult r = ll.getLatestResult();
 *   if (r != null && r.isValid()) { ... r.getTx() ... }
 *
 * In the simulator the camera "sees" the simulated field: AprilTags under
 * the HIVE CELLS and POLLEN/NECTAR balls, with a field of view, blocking by
 * robots, latency and noise.
 */
public abstract class Limelight3A implements HardwareDevice {

    @SimOnly
    protected Limelight3A() {
    }

    public abstract void start();

    public abstract void pause();

    public abstract void stop();

    public abstract boolean isRunning();

    public abstract void setPollRateHz(int rate);

    public abstract long getTimeSinceLastUpdate();

    public abstract boolean isConnected();

    public abstract LLResult getLatestResult();

    public abstract LLStatus getStatus();

    public boolean reloadPipeline() {
        return true;
    }

    public abstract boolean pipelineSwitch(int index);

    public boolean captureSnapshot(String name) {
        return true;
    }

    public boolean deleteSnapshots() {
        return true;
    }

    public boolean deleteSnapshot(String name) {
        return true;
    }

    public boolean updatePythonInputs(double a, double b, double c, double d, double e, double f, double g, double h) {
        return true;
    }

    public boolean updatePythonInputs(double[] inputs) {
        return true;
    }

    /** Gives MegaTag 2 the robot's yaw (degrees) from the IMU. */
    public abstract boolean updateRobotOrientation(double yaw);

    public boolean uploadPipeline(String pipeline, Integer index) {
        return true;
    }

    public boolean uploadPython(String python, Integer index) {
        return true;
    }

    public void shutdown() {
        stop();
    }
}
