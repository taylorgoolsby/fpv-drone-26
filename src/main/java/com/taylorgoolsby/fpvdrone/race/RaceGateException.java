package com.taylorgoolsby.fpvdrone.race;

public class RaceGateException extends Exception {
    public final String endUserReason;

    public RaceGateException(String devReason) {
        super(devReason);
        this.endUserReason = "";
    }

    public RaceGateException(String devReason, String endUserReason) {
        super(devReason);
        if (endUserReason.endsWith(".")) {
            endUserReason = endUserReason.substring(0, endUserReason.length() - 1);
        }
        this.endUserReason = endUserReason;
    }
}
