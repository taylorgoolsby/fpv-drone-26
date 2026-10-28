package com.taylorgoolsby.fpvdrone.race;

public final class RaceTime {
    private RaceTime() {}

    /** m:ss.cc, e.g. 1:07.42 */
    public static String format(long millis) {
        if (millis < 0) millis = 0;
        long minutes = millis / 60000;
        long seconds = (millis / 1000) % 60;
        long centis = (millis % 1000) / 10;
        return String.format("%d:%02d.%02d", minutes, seconds, centis);
    }
}
