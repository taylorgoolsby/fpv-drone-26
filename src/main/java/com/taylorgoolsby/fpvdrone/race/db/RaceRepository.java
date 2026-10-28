package com.taylorgoolsby.fpvdrone.race.db;

import com.taylorgoolsby.fpvdrone.race.Ids;
import com.taylorgoolsby.fpvdrone.race.RaceGate;
import com.taylorgoolsby.fpvdrone.race.RaceLap;
import com.taylorgoolsby.fpvdrone.race.RaceTrack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Every SQL statement of the racing feature lives here. All are PreparedStatements.
 *
 * Every method runs on the database thread and returns a future. Never call
 * .join() on one of these from the server thread.
 */
public final class RaceRepository {
    private RaceRepository() {}

    // ------------------------------------------------------------------ tracks

    private static final String TRACK_COLUMNS =
            "raceTrackId, ownerUserId, name, dimension, startPosX, startPosY, startPosZ, deleted, dateUpdated, dateCreated";

    private static RaceTrack readTrack(ResultSet rs) throws SQLException {
        RaceTrack t = new RaceTrack();
        t.raceTrackId = rs.getString("raceTrackId");
        t.ownerUserId = rs.getString("ownerUserId");
        t.name = rs.getString("name");
        t.dimension = rs.getString("dimension");
        t.startPosX = rs.getInt("startPosX");
        t.startPosY = rs.getInt("startPosY");
        t.startPosZ = rs.getInt("startPosZ");
        t.deleted = rs.getInt("deleted") != 0;
        t.dateUpdated = rs.getLong("dateUpdated");
        t.dateCreated = rs.getLong("dateCreated");
        return t;
    }

    private static RaceTrack selectTrackAt(Connection c, String dimension, BlockPos pos) throws SQLException {
        String sql = "SELECT " + TRACK_COLUMNS + " FROM RaceTrack "
                + "WHERE startPosX = ? AND startPosZ = ? AND startPosY = ? AND dimension = ? AND deleted = 0";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pos.getX());
            ps.setInt(2, pos.getZ());
            ps.setInt(3, pos.getY());
            ps.setString(4, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readTrack(rs) : null;
            }
        }
    }

    /** The live (non-deleted) track whose target block is at pos, or null. Gates are not loaded. */
    public static CompletableFuture<RaceTrack> getTrack(String dimension, BlockPos pos) {
        return Database.supply(c -> selectTrackAt(c, dimension, pos));
    }

    /** Same as getTrack but the gates are loaded (raw, boundaries not computed). */
    public static CompletableFuture<RaceTrack> getTrackWithGates(String dimension, BlockPos pos) {
        return Database.supply(c -> {
            RaceTrack track = selectTrackAt(c, dimension, pos);
            if (track != null) track.gates = selectGates(c, track.raceTrackId);
            return track;
        });
    }

    /** Case-insensitive (SQLite's default comparison is not). */
    public static CompletableFuture<RaceTrack> getTrackByName(String name) {
        return Database.supply(c -> {
            String sql = "SELECT " + TRACK_COLUMNS + " FROM RaceTrack WHERE LOWER(name) = LOWER(?) AND deleted = 0";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readTrack(rs) : null;
                }
            }
        });
    }

    /**
     * Inserts the track and all of its gates in a single transaction.
     * Fails (exceptionally) if a live track already exists at the start position.
     *
     * @return the new raceTrackId
     */
    public static CompletableFuture<String> insertTrackWithGates(String ownerUserId, String name, String dimension,
                                                                 BlockPos startPos, List<RaceGate> gates) {
        return Database.supply(c -> {
            if (selectTrackAt(c, dimension, startPos) != null) {
                throw new IllegalStateException("A track already exists at this position. "
                        + startPos.getX() + ", " + startPos.getY() + ", " + startPos.getZ());
            }

            String raceTrackId = Ids.newId();
            long now = System.currentTimeMillis();
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                String trackSql = "INSERT INTO RaceTrack (raceTrackId, ownerUserId, name, dimension, "
                        + "startPosX, startPosY, startPosZ, deleted, dateUpdated, dateCreated) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
                try (PreparedStatement ps = c.prepareStatement(trackSql)) {
                    ps.setString(1, raceTrackId);
                    ps.setString(2, ownerUserId);
                    ps.setString(3, name);
                    ps.setString(4, dimension);
                    ps.setInt(5, startPos.getX());
                    ps.setInt(6, startPos.getY());
                    ps.setInt(7, startPos.getZ());
                    ps.setLong(8, now);
                    ps.setLong(9, now);
                    ps.executeUpdate();
                }

                String gateSql = "INSERT INTO RaceGate (raceGateId, raceTrackId, gateIndex, dimension, "
                        + "aPosX, aPosY, aPosZ, face, bPosX, bPosY, bPosZ, "
                        + "originX, originY, originZ, farthestX, farthestY, farthestZ, "
                        + "deleted, dateUpdated, dateCreated) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
                try (PreparedStatement ps = c.prepareStatement(gateSql)) {
                    for (int i = 0; i < gates.size(); i++) {
                        RaceGate g = gates.get(i);
                        int n = 1;
                        ps.setString(n++, Ids.newId());
                        ps.setString(n++, raceTrackId);
                        ps.setInt(n++, i);
                        ps.setString(n++, g.dimension);
                        ps.setInt(n++, g.a.getX());
                        ps.setInt(n++, g.a.getY());
                        ps.setInt(n++, g.a.getZ());
                        ps.setInt(n++, g.face.ordinal());
                        ps.setInt(n++, g.b.getX());
                        ps.setInt(n++, g.b.getY());
                        ps.setInt(n++, g.b.getZ());
                        ps.setInt(n++, g.origin.getX());
                        ps.setInt(n++, g.origin.getY());
                        ps.setInt(n++, g.origin.getZ());
                        ps.setInt(n++, g.farthest.getX());
                        ps.setInt(n++, g.farthest.getY());
                        ps.setInt(n++, g.farthest.getZ());
                        ps.setLong(n++, now);
                        ps.setLong(n, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw t;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
            return raceTrackId;
        });
    }

    /**
     * Soft deletes the live track at the position, along with its gates.
     *
     * @return the deleted track (before deletion), or null if there was none
     */
    public static CompletableFuture<RaceTrack> softDeleteTrackAt(String dimension, BlockPos pos) {
        return Database.supply(c -> {
            RaceTrack track = selectTrackAt(c, dimension, pos);
            if (track == null) return null;

            long now = System.currentTimeMillis();
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE RaceTrack SET deleted = 1, dateUpdated = ? WHERE raceTrackId = ?")) {
                    ps.setLong(1, now);
                    ps.setString(2, track.raceTrackId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE RaceGate SET deleted = 1, dateUpdated = ? WHERE raceTrackId = ?")) {
                    ps.setLong(1, now);
                    ps.setString(2, track.raceTrackId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw t;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
            return track;
        });
    }

    // ------------------------------------------------------------------- gates

    private static ArrayList<RaceGate> selectGates(Connection c, String raceTrackId) throws SQLException {
        String sql = "SELECT gateIndex, dimension, aPosX, aPosY, aPosZ, face, bPosX, bPosY, bPosZ, "
                + "originX, originY, originZ, farthestX, farthestY, farthestZ "
                + "FROM RaceGate WHERE raceTrackId = ? AND deleted = 0 ORDER BY gateIndex";
        ArrayList<RaceGate> gates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, raceTrackId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Direction[] dirs = Direction.values();
                    int faceIndex = rs.getInt("face");
                    Direction face = faceIndex >= 0 && faceIndex < dirs.length ? dirs[faceIndex] : null;
                    RaceGate g = new RaceGate(
                            rs.getString("dimension"),
                            new BlockPos(rs.getInt("aPosX"), rs.getInt("aPosY"), rs.getInt("aPosZ")),
                            face,
                            new BlockPos(rs.getInt("bPosX"), rs.getInt("bPosY"), rs.getInt("bPosZ")));
                    g.raceTrackId = raceTrackId;
                    g.index = rs.getInt("gateIndex");
                    g.origin = new BlockPos(rs.getInt("originX"), rs.getInt("originY"), rs.getInt("originZ"));
                    g.farthest = new BlockPos(rs.getInt("farthestX"), rs.getInt("farthestY"), rs.getInt("farthestZ"));
                    gates.add(g);
                }
            }
        }
        return gates;
    }

    // -------------------------------------------------------------------- laps

    private static RaceLap readLap(ResultSet rs) throws SQLException {
        RaceLap lap = new RaceLap();
        lap.raceLapId = rs.getString("raceLapId");
        lap.raceTrackId = rs.getString("raceTrackId");
        lap.userId = rs.getString("userId");
        lap.userName = rs.getString("userName");
        lap.millis = rs.getInt("millis");
        lap.version = rs.getString("version");
        lap.dateCreated = rs.getLong("dateCreated");
        return lap;
    }

    /**
     * Each user's best lap on the track, fastest first.
     * Ties between a user's own laps are collapsed to one row.
     */
    public static CompletableFuture<ArrayList<RaceLap>> getBestTimes(String raceTrackId, int limit) {
        return Database.supply(c -> {
            String sql = "SELECT r.raceLapId, r.raceTrackId, r.userId, r.userName, r.millis, r.version, r.dateCreated "
                    + "FROM RaceLap r "
                    + "JOIN ("
                    + "  SELECT userId, MIN(millis) AS bestMillis FROM RaceLap WHERE raceTrackId = ? GROUP BY userId"
                    + ") m ON r.userId = m.userId AND r.millis = m.bestMillis "
                    + "WHERE r.raceTrackId = ? "
                    + "ORDER BY r.millis ASC, r.dateCreated ASC";
            // The limit is applied in Java after collapsing duplicate rows so that
            // ties do not push other racers off the board.
            Map<String, RaceLap> byUser = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, raceTrackId);
                ps.setString(2, raceTrackId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        RaceLap lap = readLap(rs);
                        byUser.putIfAbsent(lap.userId, lap);
                    }
                }
            }
            ArrayList<RaceLap> result = new ArrayList<>(byUser.values());
            if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
            return result;
        });
    }

    public static CompletableFuture<String> insertLap(String raceTrackId, String userId, String userName,
                                                      int millis, String version) {
        return Database.supply(c -> {
            String raceLapId = Ids.newId();
            String sql = "INSERT INTO RaceLap (raceLapId, raceTrackId, userId, userName, millis, version, dateCreated) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, raceLapId);
                ps.setString(2, raceTrackId);
                ps.setString(3, userId);
                ps.setString(4, userName);
                ps.setInt(5, millis);
                ps.setString(6, version);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return raceLapId;
        });
    }
}
