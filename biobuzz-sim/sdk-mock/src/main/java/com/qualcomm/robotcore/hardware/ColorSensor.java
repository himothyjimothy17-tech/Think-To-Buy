package com.qualcomm.robotcore.hardware;

/** A color sensor (for example a REV Color Sensor V3). */
public interface ColorSensor extends HardwareDevice {
    int red();

    int green();

    int blue();

    int alpha();

    int argb();

    void enableLed(boolean enable);

    void setI2cAddress(I2cAddr newAddress);

    I2cAddr getI2cAddress();
}
