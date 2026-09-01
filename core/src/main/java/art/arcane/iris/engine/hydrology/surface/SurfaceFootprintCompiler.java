package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydraulicSegment;
import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import art.arcane.iris.engine.hydrology.HydrologyGeometrySampler;
import art.arcane.iris.engine.hydrology.HydrologyHash;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyPoint;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;
import art.arcane.iris.engine.hydrology.RiverCourse;
import art.arcane.iris.engine.hydrology.RiverCourseType;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SurfaceFootprintCompiler {
    private static final long COURSE_SEED_SALT = 0x535552464143455fL;

    private final HydrologyPlannerSettings settings;
    private final HydrologyTerrainSampler sampler;
    private final HydrologyGeometrySampler geometry;

    public SurfaceFootprintCompiler(
            HydrologyPlannerSettings settings,
            HydrologyTerrainSampler sampler,
            HydrologyGeometrySampler geometry
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    public static boolean exposedSegment(HydraulicSegment segment) {
        return segment.type().isSurface() && !segment.fallingFluid();
    }

    public SurfaceFootprint compile(RiverCourse course) {
        if (course.type() != RiverCourseType.SURFACE) {
            return SurfaceFootprint.empty();
        }
        ArrayList<HydraulicSegment> exposed = new ArrayList<>();
        for (HydraulicSegment segment : course.segments()) {
            if (!exposedSegment(segment)) {
                break;
            }
            exposed.add(segment);
        }
        if (exposed.isEmpty()) {
            return SurfaceFootprint.empty();
        }
        Stations stations = stations(exposed);
        if (stations.count() < 1) {
            return SurfaceFootprint.empty();
        }
        SurfaceTerminal terminal = terminal(course, exposed);
        SurfaceCenterline centerline = SurfaceCenterline.densify(stations.points());
        ChannelProfile channel = new ChannelProfileBuilder(settings.surface(), sampler, geometry)
                .build(centerline, course.profileKey(), terminal == SurfaceTerminal.OCEAN_MOUTH);
        ValleyProfile valley = ValleyProfile.fromHeads(stations.head(), stations.exposedStations());
        ErosionField field = new ErosionFieldCompiler(settings.surface(), sampler, settings.seaLevel()).compile(
                HydrologyHash.mix(course.id(), COURSE_SEED_SALT),
                centerline,
                channel,
                valley,
                terminal,
                settings.outlets().maximumOceanApron()
        );
        ArrayList<SurfaceColumn> ordered = new ArrayList<>(field.columns().values());
        ordered.sort(Comparator
                .comparingInt(SurfaceColumn::station)
                .thenComparingLong((SurfaceColumn column) -> RiverFootprint.pack(column.x(), column.z())));
        SurfaceFeatureRefs features = new SurfaceFeatureRefs(course.id());
        ArrayList<SurfaceLayerColumn> columns = new ArrayList<>(ordered.size());
        for (SurfaceColumn column : ordered) {
            HydraulicSegment segment = exposed.get(stations.segmentIndex()[column.station()]);
            int flowX = (int) StrictMath.round(centerline.tangentX()[column.station()]);
            int flowZ = (int) StrictMath.round(centerline.tangentZ()[column.station()]);
            boolean source = column.station() == 0 && column.role() == SurfaceRole.CHANNEL && !column.apron();
            int y = column.role() == SurfaceRole.CHANNEL ? column.headY() : column.height();
            HydrologyFeatureRef feature = features.feature(segment, column.role(), source, column.x(), y, column.z(), flowX, flowZ);
            columns.add(new SurfaceLayerColumn(
                    column.x(),
                    column.z(),
                    column.terrain(),
                    layer(feature, column, course.profileKey()),
                    column.role(),
                    column.apron()
            ));
        }
        return new SurfaceFootprint(columns, field.uncontainedWetCells());
    }

    private static HydrologyColumnLayer layer(HydrologyFeatureRef feature, SurfaceColumn column, String profileKey) {
        HydrologyTerrainSample terrain = column.terrain();
        if (column.apron()) {
            return new HydrologyColumnLayer(
                    feature,
                    column.headY(),
                    column.headY(),
                    column.headY(),
                    true, false, false, true, false, false, false, false, true,
                    profileKey,
                    terrain.surfaceBiomeKey(),
                    terrain.mouthBiomeKey(),
                    terrain.shoreBiomeKey(),
                    terrain.bankBiomeKey(),
                    terrain.floodedCaveBiomeKey()
            );
        }
        boolean channel = column.role() == SurfaceRole.CHANNEL;
        boolean shore = column.role() == SurfaceRole.SHORE;
        return new HydrologyColumnLayer(
                feature,
                column.height(),
                column.headY(),
                column.headY(),
                channel,
                shore,
                !channel,
                channel,
                false,
                false,
                true,
                channel,
                false,
                profileKey,
                terrain.surfaceBiomeKey(),
                terrain.mouthBiomeKey(),
                terrain.shoreBiomeKey(),
                terrain.bankBiomeKey(),
                terrain.floodedCaveBiomeKey()
        );
    }

    private static SurfaceTerminal terminal(RiverCourse course, List<HydraulicSegment> exposed) {
        if (exposed.getLast().type() == HydrologyFeatureType.MOUTH) {
            return SurfaceTerminal.OCEAN_MOUTH;
        }
        for (HydraulicSegment segment : course.segments()) {
            if (segment.type() == HydrologyFeatureType.MOUTH) {
                return SurfaceTerminal.OCEAN_MOUTH;
            }
            if (segment.type() == HydrologyFeatureType.COASTAL_GROTTO) {
                return SurfaceTerminal.COASTAL_GROTTO;
            }
        }
        return SurfaceTerminal.SINKHOLE;
    }

    private static Stations stations(List<HydraulicSegment> exposed) {
        ArrayList<HydrologyPoint> points = new ArrayList<>();
        IntArrayList segmentIndices = new IntArrayList();
        IntArrayList heads = new IntArrayList();
        int exposedStations = 0;
        for (int segmentIndex = 0; segmentIndex < exposed.size(); segmentIndex++) {
            HydraulicSegment segment = exposed.get(segmentIndex);
            boolean mouth = segment.type() == HydrologyFeatureType.MOUTH;
            List<HydrologyPoint> centerline = segment.centerline();
            for (int pointIndex = 0; pointIndex < centerline.size(); pointIndex++) {
                HydrologyPoint current = centerline.get(pointIndex);
                if (pointIndex == 0) {
                    appendStation(points, segmentIndices, heads, current, segmentIndex);
                    continue;
                }
                HydrologyPoint previous = centerline.get(pointIndex - 1);
                int steps = Math.max(Math.abs(current.x() - previous.x()), Math.abs(current.z() - previous.z()));
                for (int step = 1; step <= steps; step++) {
                    double progress = step / (double) steps;
                    HydrologyPoint cell = new HydrologyPoint(
                            (int) StrictMath.round(previous.x() + (current.x() - previous.x()) * progress),
                            (int) StrictMath.round(previous.y() + (current.y() - previous.y()) * progress),
                            (int) StrictMath.round(previous.z() + (current.z() - previous.z()) * progress)
                    );
                    appendStation(points, segmentIndices, heads, cell, segmentIndex);
                }
            }
            if (!mouth) {
                exposedStations = points.size();
            }
        }
        return new Stations(points, segmentIndices.toIntArray(), heads.toIntArray(), exposedStations);
    }

    private static void appendStation(
            ArrayList<HydrologyPoint> points,
            IntArrayList segmentIndices,
            IntArrayList heads,
            HydrologyPoint cell,
            int segmentIndex
    ) {
        if (!points.isEmpty()) {
            HydrologyPoint last = points.getLast();
            if (last.x() == cell.x() && last.z() == cell.z()) {
                return;
            }
        }
        points.add(cell);
        segmentIndices.add(segmentIndex);
        heads.add(cell.y());
    }

    private record Stations(List<HydrologyPoint> points, int[] segmentIndex, int[] head, int exposedStations) {
        private int count() {
            return points.size();
        }
    }
}
