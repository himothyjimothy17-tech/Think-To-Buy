package com.qualcomm.robotcore.hardware;

/** An I2C bus address. */
public final class I2cAddr {
    private final int i2cAddr7Bit;

    public I2cAddr(int i2cAddr7Bit) {
        this.i2cAddr7Bit = i2cAddr7Bit & 0x7F;
    }

    public static I2cAddr zero() { return new I2cAddr(0); }

    public static I2cAddr create7bit(int i2cAddr7Bit) { return new I2cAddr(i2cAddr7Bit); }

    public static I2cAddr create8bit(int i2cAddr8Bit) { return new I2cAddr(i2cAddr8Bit / 2); }

    public int get8Bit() { return i2cAddr7Bit * 2; }

    public int get7Bit() { return i2cAddr7Bit; }
}
