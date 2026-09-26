package com.qualcomm.robotcore.hardware;

/** Reads the main 12V battery voltage from a hub. */
public interface VoltageSensor extends HardwareDevice {
    double getVoltage();
}
