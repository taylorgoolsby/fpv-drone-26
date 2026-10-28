package com.taylorgoolsby.fpvdrone.race.net;

import java.util.UUID;

public record LeaderboardEntry(UUID userId, String name, int millis) {}
