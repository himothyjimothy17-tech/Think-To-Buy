package com.qualcomm.robotcore.hardware;

/** Base interface for every piece of hardware (matches the real FTC SDK). */
public interface HardwareDevice {

    enum Manufacturer {
        Unknown, Other, Lego, HiTechnic, ModernRobotics, Adafruit, Matrix, Lynx, AMS,
        STMicroelectronics, Broadcom, DFRobot, DigitalChickenLabs, SparkFun, MaxBotix,
        LimelightVision, GoBilda
    }

    Manufacturer getManufacturer();

    String getDeviceName();

    String getConnectionInfo();

    int getVersion();

    void resetDeviceConfigurationForOpMode();

    void close();
}
