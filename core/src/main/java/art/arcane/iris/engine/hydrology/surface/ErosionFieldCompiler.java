package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Objects;

public final class ErosionFieldCompiler {
    private static final int BLEND_SMOOTHING_RADIUS = 12;
    private static final double THALWEG_FRACTION = 0.45D;
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final long OUTLINE_SALT = 0x4f55544c494e45L;
    private static final long BED_SALT = 0x424544L;
    private static final double EPSILON = 1.0E-9D;

    private final HydrologyPlannerSettings.Surface surface;
    private final HydrologyTerrainSampler sampler;
    private final int seaLevel;

    public ErosionFieldCompiler(
            HydrologyPlannerSettings.Surface surface,
            HydrologyTerrainSampler sampler,
            int seaLevel
    ) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.seaLevel = seaLevel;
    }

    public ErosionField compile(
            long courseSeed,
            SurfaceCenterline centerline,
            ChannelProfile channel,
            ValleyProfile valley,
            SurfaceTerminal terminal,
            int apronLimit
    ) {
        HydrologyPlannerSettings.Banks banks = surface.banks();
        int count = terminal == SurfaceTerminal.OCEAN_MOUTH ? centerline.size() : valley.exposedStations();
        double roughness = banks.roughness();
        double shoreWidth = surface.shoreWidth();
        int freeboard = banks.freeboard();
        boolean[] basin = basins(valley, channel, count);
        double[][] blendWidth = blendWidths(centerline, channel, valley, count);
        Long2IntOpenHashMap nearest = new Long2IntOpenHashMap();
        nearest.defaultReturnValue(-1);
        Long2DoubleOpenHashMap distance = new Long2DoubleOpenHashMap();
        for (int station = 0; station < count; station++) {
            double widest = Math.max(blendWidth[station][0], blendWidth[station][1]);
            int radius = (int) StrictMath.ceil(channel.halfWidth()[station] * (1D + roughness) + shoreWidth + widest) + 1;
            int stationX = centerline.x()[station];
            int stationZ = centerline.z()[station];
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                    int cellX = stationX + deltaX;
                    int cellZ = stationZ + deltaZ;
                    double cellDistance = centerline.distanceToSegment(station, cellX, cellZ);
                    if (cellDistance > radius) {
                        continue;
                    }
                    long key = RiverFootprint.pack(cellX, cellZ);
                    int existing = nearest.get(key);
                    // A cell on a station's cross-section row ties between the two segments meeting
                    // there; the later station owns it so the row uses the head its own ring bounded.
                    if (existing < 0
                            || cellDistance < distance.get(key) - EPSILON
                            || Math.abs(cellDistance - distance.get(key)) <= EPSILON && station > existing) {
                        nearest.put(key, station);
                        distance.put(key, cellDistance);
                    }
                }
            }
        }
        int oceanStart = oceanStart(centerline, valley, terminal);
        Long2ObjectOpenHashMap<SurfaceColumn> columns = new Long2ObjectOpenHashMap<>(nearest.size());
        LongArrayList wetKeys = new LongArrayList();
        for (long key : nearest.keySet()) {
            int station = nearest.get(key);
            double cellDistance = distance.get(key);
            int cellX = RiverFootprint.unpackX(key);
            int cellZ = RiverFootprint.unpackZ(key);
            HydrologyTerrainSample terrain = sampler.sample(cellX, cellZ);
            int head = valley.head()[station];
            double halfWidth = channel.halfWidth()[station];
            double outline = halfWidth * (1D + roughness * SurfaceNoise.signed(
                    courseSeed ^ OUTLINE_SALT, cellX, cellZ, banks.roughnessWavelength()));
            outline = Math.max(Math.max(0.5D, 0.6D * halfWidth), Math.min(1.4D * halfWidth, outline));
            boolean wet = cellDistance <= outline + 0.25D;
            if (!SurfaceCellAdmission.writable(terrain, seaLevel)) {
                if (terrain != null && wet && terminal == SurfaceTerminal.OCEAN_MOUTH
                        && station >= valley.exposedStations() - 1
                        && station - oceanStart < apronLimit
                        && cellDistance <= apronLimit + 0.25D) {
                    columns.put(key, new SurfaceColumn(
                            cellX, cellZ, terrain, station, SurfaceRole.CHANNEL, terrain.naturalHeight(), seaLevel, true));
                }
                continue;
            }
            int natural = terrain.naturalHeight();
            if (wet) {
                double normalized = Math.min(1D, cellDistance / Math.max(0.5D, outline));
                double depth = channel.depth()[station];
                double local = 1D + (depth - 1D) * bowl(normalized)
                        + 0.5D * roughness * SurfaceNoise.signed(courseSeed ^ BED_SALT, cellX, cellZ, banks.roughnessWavelength())
                        + (basin[station] ? 1D : 0D);
                int bed = Math.min(natural, head - Math.max(1, (int) StrictMath.round(local)));
                columns.put(key, new SurfaceColumn(cellX, cellZ, terrain, station, SurfaceRole.CHANNEL, bed, head, false));
                wetKeys.add(key);
                continue;
            }
            int bankTop = head + freeboard;
            if (cellDistance <= outline + shoreWidth + 0.25D) {
                int height = Math.min(natural, bankTop);
                columns.put(key, new SurfaceColumn(cellX, cellZ, terrain, station, SurfaceRole.SHORE, height, height, false));
                continue;
            }
            int cut = natural - bankTop;
            if (cut <= 0) {
                continue;
            }
            double side = side(centerline, station, cellX, cellZ);
            double width = blendWidth[station][side < 0D ? 0 : 1];
            double progress = (cellDistance - outline - shoreWidth) / width;
            if (progress >= 1D) {
                continue;
            }
            int height = Math.min(natural, (int) StrictMath.round(bankTop + cut * SurfaceNoise.smoothStep(progress)));
            columns.put(key, new SurfaceColumn(cellX, cellZ, terrain, station, SurfaceRole.BANK, height, height, false));
        }
        int uncontained = contain(columns, wetKeys, freeboard);
        connectSteps(columns, wetKeys);
        return new ErosionField(columns, uncontained);
    }

    /**
     * Where the head steps down between neighbouring wet cells, the upper bed is lowered so its water
     * column reaches the lower head; the water stays one connected body across every step.
     */
    private static void connectSteps(Long2ObjectOpenHashMap<SurfaceColumn> columns, LongArrayList wetKeys) {
        for (int index = 0; index < wetKeys.size(); index++) {
            long key = wetKeys.getLong(index);
            SurfaceColumn wet = columns.get(key);
            int bed = wet.height();
            for (int[] offset : CARDINALS) {
                SurfaceColumn neighbour = columns.get(RiverFootprint.pack(wet.x() + offset[0], wet.z() + offset[1]));
                if (neighbour == null || neighbour.role() != SurfaceRole.CHANNEL || neighbour.apron()) {
                    continue;
                }
                if (neighbour.headY() < wet.headY()) {
                    bed = Math.min(bed, neighbour.headY() - 1);
                }
            }
            if (bed < wet.height()) {
                columns.put(key, wet.withBed(bed));
            }
        }
    }

    /**
     * Every dry cell touching water, diagonals included, keeps the lip; only cardinal neighbours can
     * spill, so only they count as uncontained.
     */
    private int contain(Long2ObjectOpenHashMap<SurfaceColumn> columns, LongArrayList wetKeys, int freeboard) {
        int uncontained = 0;
        for (int index = 0; index < wetKeys.size(); index++) {
            SurfaceColumn wet = columns.get(wetKeys.getLong(index));
            uncontained += lip(columns, wet, CARDINALS, freeboard, true);
            lip(columns, wet, DIAGONALS, freeboard, false);
        }
        return uncontained;
    }

    private int lip(
            Long2ObjectOpenHashMap<SurfaceColumn> columns,
            SurfaceColumn wet,
            int[][] offsets,
            int freeboard,
            boolean countSpills
    ) {
        int uncontained = 0;
        for (int[] offset : offsets) {
            long key = RiverFootprint.pack(wet.x() + offset[0], wet.z() + offset[1]);
            SurfaceColumn neighbour = columns.get(key);
            if (neighbour == null) {
                if (countSpills && wet.headY() != seaLevel) {
                    uncontained++;
                }
                continue;
            }
            if (neighbour.role() == SurfaceRole.CHANNEL) {
                continue;
            }
            int required = wet.headY() + freeboard;
            if (neighbour.height() < required) {
                int raised = Math.min(neighbour.terrain().naturalHeight(), required);
                neighbour = neighbour.withHeight(raised);
                columns.put(key, neighbour);
            }
            if (countSpills && neighbour.height() < wet.headY()) {
                uncontained++;
            }
        }
        return uncontained;
    }

    /** First mouth station whose center is ocean; the apron is measured from there, not from the mouth segment start. */
    private int oceanStart(SurfaceCenterline centerline, ValleyProfile valley, SurfaceTerminal terminal) {
        int count = centerline.size();
        if (terminal != SurfaceTerminal.OCEAN_MOUTH) {
            return count;
        }
        for (int station = Math.max(0, valley.exposedStations() - 1); station < count; station++) {
            HydrologyTerrainSample center = sampler.sample(centerline.x()[station], centerline.z()[station]);
            if (!SurfaceCellAdmission.writable(center, seaLevel)) {
                return station;
            }
        }
        return count;
    }

    private boolean[] basins(ValleyProfile valley, ChannelProfile channel, int count) {
        boolean[] basin = new boolean[count];
        for (int station = 1; station < count; station++) {
            int drop = valley.head()[station - 1] - valley.head()[station];
            if (drop < 2) {
                continue;
            }
            int length = Math.max(2, (int) StrictMath.round(2D * channel.halfWidth()[station]));
            for (int following = station; following < Math.min(count, station + length); following++) {
                basin[following] = true;
            }
        }
        return basin;
    }

    private double[][] blendWidths(SurfaceCenterline centerline, ChannelProfile channel, ValleyProfile valley, int count) {
        HydrologyPlannerSettings.Banks banks = surface.banks();
        double[][] widths = new double[count][2];
        for (int station = 0; station < count; station++) {
            double halfWidth = channel.halfWidth()[station];
            double probe = halfWidth + surface.shoreWidth() + Math.max(2D, halfWidth);
            int bankTop = valley.head()[station] + banks.freeboard();
            for (int side = 0; side < 2; side++) {
                double direction = side == 0 ? -1D : 1D;
                int probeX = (int) StrictMath.round(centerline.x()[station] + centerline.normalX(station) * probe * direction);
                int probeZ = (int) StrictMath.round(centerline.z()[station] + centerline.normalZ(station) * probe * direction);
                HydrologyTerrainSample terrain = sampler.sample(probeX, probeZ);
                double cut = terrain == null ? 0D : Math.max(0D, terrain.naturalHeight() - bankTop);
                double width = cut * banks.blendSlope() * channel.bankMultiplier()[station];
                widths[station][side] = Math.max(banks.minimumBlendWidth(), Math.min(banks.maximumBlendWidth(), width));
            }
        }
        return smoothWidths(widths, BLEND_SMOOTHING_RADIUS);
    }

    /**
     * Moving average along the course so the valley outline widens and narrows gradually instead of
     * stepping between neighbouring stations.
     */
    static double[][] smoothWidths(double[][] widths, int radius) {
        int count = widths.length;
        double[][] smoothed = new double[count][2];
        for (int side = 0; side < 2; side++) {
            double sum = 0D;
            int window = 0;
            int head = -1;
            int tail = 0;
            for (int station = 0; station < count; station++) {
                while (head + 1 < count && head + 1 <= station + radius) {
                    head++;
                    sum += widths[head][side];
                    window++;
                }
                while (tail < station - radius) {
                    sum -= widths[tail][side];
                    tail++;
                    window--;
                }
                smoothed[station][side] = sum / window;
            }
        }
        return smoothed;
    }

    /**
     * Bed depth factor across the channel: a level thalweg over the inner part of the width that eases
     * up to a one-block edge, so the channel reads as a broad bowl rather than a V-shaped trough.
     */
    static double bowl(double normalized) {
        if (normalized <= THALWEG_FRACTION) {
            return 1D;
        }
        double t = Math.min(1D, (normalized - THALWEG_FRACTION) / (1D - THALWEG_FRACTION));
        return 1D - t * t * (3D - 2D * t);
    }

    private static double side(SurfaceCenterline centerline, int station, int cellX, int cellZ) {
        double deltaX = cellX - centerline.x()[station];
        double deltaZ = cellZ - centerline.z()[station];
        return deltaX * centerline.normalX(station) + deltaZ * centerline.normalZ(station);
    }
}
