package com.qualcomm.hardware.rev;

import org.biobuzz.simhooks.SimOnly;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

/**
 * Describes how the Control Hub is mounted: which way the REV logo faces and
 * which way the USB ports face. Getting this wrong makes the IMU report the
 * wrong heading - the simulator models that, so you'll notice in the sim.
 */
public class RevHubOrientationOnRobot extends RevImuOrientationOnRobot {

    public enum LogoFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

    public enum UsbFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

    private final LogoFacingDirection logo;
    private final UsbFacingDirection usb;

    public RevHubOrientationOnRobot(LogoFacingDirection logoFacingDirection, UsbFacingDirection usbFacingDirection) {
        super(Quaternion.identityQuaternion());
        if (axis(logoFacingDirection.name()) == axis(usbFacingDirection.name())) {
            // The real SDK throws the same error for impossible combinations.
            throw new IllegalArgumentException(
                    "The specified REV Hub orientation is invalid: logo and USB can't face along the same axis");
        }
        this.logo = logoFacingDirection;
        this.usb = usbFacingDirection;
    }

    /** Which way the logo faces (the simulator uses this to check the IMU mounting). */
    @SimOnly
    public LogoFacingDirection simLogoFacing() {
        return logo;
    }

    /** Which way the USB ports face. */
    @SimOnly
    public UsbFacingDirection simUsbFacing() {
        return usb;
    }

    private static int axis(String dir) {
        switch (dir) {
            case "UP": case "DOWN": return 0;
            case "FORWARD": case "BACKWARD": return 1;
            default: return 2;
        }
    }
}
