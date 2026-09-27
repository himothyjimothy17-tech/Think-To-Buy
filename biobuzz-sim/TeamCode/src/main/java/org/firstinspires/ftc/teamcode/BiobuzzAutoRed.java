package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;

/** RED version of {@link BiobuzzAuto}. */
@Autonomous(name = "BIOBUZZ Auto RED", group = "match")
public class BiobuzzAutoRed extends BiobuzzAuto {
    @Override
    protected boolean isBlue() {
        return false;
    }
}
