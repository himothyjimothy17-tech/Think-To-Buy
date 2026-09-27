package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;

/** BLUE version of {@link BiobuzzAuto}: the same plan turned 180 degrees. */
@Autonomous(name = "BIOBUZZ Auto BLUE", group = "match")
public class BiobuzzAutoBlue extends BiobuzzAuto {
    @Override
    protected boolean isBlue() {
        return true;
    }
}
