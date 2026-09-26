package com.qualcomm.robotcore.hardware.configuration.typecontainers;

/**
 * Describes a motor model (ticks per revolution, max RPM...). Only the most
 * commonly used getters/setters are mocked.
 */
public class MotorConfigurationType {
    private double ticksPerRev;
    private double gearing;
    private double maxRPM;
    private double achieveableMaxRPMFraction = 0.85;

    @org.biobuzz.simhooks.SimOnly
    public MotorConfigurationType(double ticksPerRev, double gearing, double maxRPM) {
        this.ticksPerRev = ticksPerRev;
        this.gearing = gearing;
        this.maxRPM = maxRPM;
    }

    public double getTicksPerRev() { return ticksPerRev; }

    public void setTicksPerRev(double ticksPerRev) { this.ticksPerRev = ticksPerRev; }

    public double getGearing() { return gearing; }

    public void setGearing(double gearing) { this.gearing = gearing; }

    public double getMaxRPM() { return maxRPM; }

    public void setMaxRPM(double maxRPM) { this.maxRPM = maxRPM; }

    public double getAchieveableMaxRPMFraction() { return achieveableMaxRPMFraction; }

    public void setAchieveableMaxRPMFraction(double fraction) { this.achieveableMaxRPMFraction = fraction; }

    public double getAchieveableMaxTicksPerSecond() {
        return maxRPM / 60.0 * ticksPerRev * achieveableMaxRPMFraction;
    }

    public int getAchieveableMaxTicksPerSecondRounded() {
        return (int) Math.round(getAchieveableMaxTicksPerSecond());
    }
}
