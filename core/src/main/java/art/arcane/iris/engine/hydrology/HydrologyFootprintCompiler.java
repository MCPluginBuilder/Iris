package art.arcane.iris.engine.hydrology;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class HydrologyFootprintCompiler {
    private static final byte CHANNEL_ROLE = 1;
    private static final byte SHORE_ROLE = 2;
    private static final byte GRADING_ROLE = 3;
    private static final int FEATURE_ROLE_COUNT = 7;
    private static final int COURSE_FOOTPRINT_CACHE_SIZE = 8;
    private static final int VALIDATION_RASTER_CACHE_SIZE = 8;
    private static final int MINIMUM_ENDPOINT_PROFILE_LENGTH = 48;
    private static final int MAXIMUM_ENDPOINT_PROFILE_LENGTH = 64;
    private static final double MINIMUM_ENDPOINT_RAMP_WIDTHS = 2D;
    private static final int SURFACE_SHAPE_PROFILE_LENGTH = 32;
    private static final int[][] HORIZONTAL_NEIGHBORS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final double MOUTH_FLARE_RATIO = 1.5D;
    private static final double SURFACE_THALWEG_RATIO = 0.65D;
    private static final long ORGANIC_SHAPE_FIRST_PHASE_SALT = 0x4f5247414e31L;
    private static final long ORGANIC_SHAPE_SECOND_PHASE_SALT = 0x4f5247414e32L;
    private static final long ORGANIC_BED_VARIATION_SALT = 0x424544564152L;

    private final HydrologyPlannerSettings settings;
    private final HydrologyTerrainSampler sampler;
    private final HydrologyNaturalTerrainSampler naturalSampler;
    private final HydrologyGeometrySampler geometrySampler;
    private final Map<String, HydrologyPlannerSettings.DeepFluid> deepFluids;
    private final Long2ObjectOpenHashMap<HydrologyTerrainSample> terrainSamples;
    private final Long2ObjectOpenHashMap<HydrologyTerrainSample> terrainBases;
    private final Long2ObjectOpenHashMap<HydrologyRoutingTerrainSampler.NaturalClassification> naturalClassifications;
    private final Map<FeatureKey, HydrologyFeatureRef> features;
    private final LinkedHashMap<CourseRasterKey, RiverFootprint> courseFootprints;
    private final LinkedHashMap<CourseRasterKey, ValidationCourseRaster> validationCourseRasters;
    private final Map<RasterStencilKey, RasterStencil> rasterStencils;
    private int fullMaterializationCount;

    HydrologyFootprintCompiler(
            HydrologyPlannerSettings settings,
            HydrologyTerrainSampler sampler
    ) {
        this(settings, sampler, HydrologyGeometrySampler.deterministic(0L, sampler));
    }

    HydrologyFootprintCompiler(
            HydrologyPlannerSettings settings,
            HydrologyTerrainSampler sampler,
            HydrologyGeometrySampler geometrySampler
    ) {
        this(settings, new Sampling(sampler, geometrySampler, null));
    }

    HydrologyFootprintCompiler(
            HydrologyPlannerSettings settings,
            Sampling sampling
    ) {
        this.settings = settings;
        this.sampler = sampling.sampler();
        this.naturalSampler = sampling.naturalSampler();
        this.geometrySampler = sampling.geometrySampler();
        HashMap<String, HydrologyPlannerSettings.DeepFluid> indexed = new HashMap<>();
        for (HydrologyPlannerSettings.DeepFluid deepFluid : settings.deepFluids()) {
            indexed.put(deepFluid.id(), deepFluid);
        }
        this.deepFluids = Map.copyOf(indexed);
        this.terrainSamples = new Long2ObjectOpenHashMap<>();
        this.terrainBases = new Long2ObjectOpenHashMap<>();
        this.naturalClassifications = new Long2ObjectOpenHashMap<>();
        this.features = new HashMap<>();
        this.courseFootprints = new LinkedHashMap<>(COURSE_FOOTPRINT_CACHE_SIZE, 1F, true);
        this.validationCourseRasters = new LinkedHashMap<>(VALIDATION_RASTER_CACHE_SIZE, 1F, true);
        this.rasterStencils = new HashMap<>();
        this.fullMaterializationCount = 0;
    }

    RiverFootprint compile(List<RiverCourse> courses) {
        fullMaterializationCount++;
        if (courses.isEmpty()) {
            return RiverFootprint.empty();
        }
        if (courses.size() == 1) {
            return compileCourse(courses.getFirst());
        }
        Long2ObjectLinkedOpenHashMap<MutableColumn> columns = new Long2ObjectLinkedOpenHashMap<>();
        for (RiverCourse course : courses) {
            RiverFootprint footprint = compileCourse(course);
            for (Map.Entry<Long, HydrologyColumnSample> entry : footprint.columns().entrySet()) {
                HydrologyColumnSample sample = entry.getValue();
                MutableColumn column = columns.get(entry.getKey());
                if (column == null) {
                    columns.put(entry.getKey(), new MutableColumn(sample));
                    continue;
                }
                column.merge(sample);
            }
        }
        containSurfaceBanks(columns);
        return build(columns);
    }

    ValidationRaster compileValidation(List<RiverCourse> courses) {
        boolean caveCandidatePresent = false;
        for (RiverCourse course : courses) {
            if (hasCaveSegment(course)) {
                caveCandidatePresent = true;
                break;
            }
        }
        if (!caveCandidatePresent) {
            return new ValidationRaster(
                    List.of(),
                    new SurfaceRasterIndex(List.of(), new Long2ObjectOpenHashMap<>())
            );
        }

        ArrayList<ValidationCourseRaster> courseRasters = new ArrayList<>(courses.size());
        ArrayList<SurfaceSweep> surfaceSweeps = new ArrayList<>();
        Long2ObjectOpenHashMap<HydrologyColumnSample> firstSamples = new Long2ObjectOpenHashMap<>();
        for (RiverCourse course : courses) {
            ValidationCourseRaster raster = compileValidationCourse(course);
            courseRasters.add(raster);
            surfaceSweeps.addAll(raster.surfaceSweeps());
            for (HydrologyColumnSample sample : raster.columns()) {
                long packed = RiverFootprint.pack(sample.x(), sample.z());
                HydrologyColumnSample first = firstSamples.putIfAbsent(packed, sample);
                if (first != null) {
                    validateMatchingTerrainMetadata(first, sample);
                }
            }
        }
        return new ValidationRaster(
                List.copyOf(courseRasters),
                new SurfaceRasterIndex(surfaceSweeps, firstSamples)
        );
    }

    int fullMaterializationCount() {
        return fullMaterializationCount;
    }

    boolean surfaceHeadwaterRampSupported(RiverCourse course) {
        Objects.requireNonNull(course, "course");
        if (course.type() != RiverCourseType.SURFACE) {
            return true;
        }
        if (settings.routing().branching().minimumSurfaceCourseLength()
                < MINIMUM_ENDPOINT_PROFILE_LENGTH) {
            return true;
        }
        RiverFootprint footprint = compileCourse(course);
        List<HydrologyPoint> raster = surfaceCourseCenterline(course);
        if (raster.size() < 2) {
            return false;
        }
        HashSet<Long> ownedCells = new HashSet<>();
        for (HydrologyColumnSample sample : footprint.columns().values()) {
            HydrologyColumnLayer fluid = sample.primarySurfaceFluidLayer().orElse(null);
            if (fluid != null
                    && fluid.feature().courseId() == course.id()
                    && fluid.feature().type().isSurface()
                    && fluid.channel()
                    && fluid.connectedFluid()
                    && fluid.fluidOwned()
                    && !fluid.oceanApron()) {
                ownedCells.add(RiverFootprint.pack(sample.x(), sample.z()));
            }
        }
        int maximumWidth = 1;
        for (HydraulicSegment segment : course.segments()) {
            if (segment.type().isSurface()) {
                maximumWidth = Math.max(maximumWidth, segment.width());
            }
        }
        HydrologyPoint source = raster.getFirst();
        if (!ownedNear(ownedCells, source.x(), source.z(), maximumWidth)) {
            return false;
        }
        int cruiseWidth = cruiseWidth(ownedCells, raster, maximumWidth);
        double transitionLength = endpointTransitionLength(
                ownedCells,
                raster,
                maximumWidth,
                cruiseWidth
        );
        return transitionLength >= cruiseWidth * MINIMUM_ENDPOINT_RAMP_WIDTHS;
    }

    boolean surfaceIncisionContained(RiverCourse course) {
        Objects.requireNonNull(course, "course");
        if (course.type() != RiverCourseType.SURFACE) {
            return true;
        }
        RiverFootprint footprint = compileCourse(course);
        for (HydrologyColumnSample sample : footprint.columns().values()) {
            HydrologyColumnLayer fluid = sample.primarySurfaceFluidLayer().orElse(null);
            if (fluid == null
                    || fluid.feature().courseId() != course.id()
                    || fluid.feature().type() == HydrologyFeatureType.MOUTH
                    || fluid.oceanApron()
                    || fluid.fallingFluid()
                    || !fluid.channel()
                    || !fluid.fluidOwned()) {
                continue;
            }
            HydrologyTerrainSample basis = sampleTerrainBasis(sample.x(), sample.z());
            HydrologyTerrainSample terrain = sampleTerrain(sample.x(), sample.z());
            if (terrain == null
                    || basis == null
                    || (terrain.naturalHeight() - fluid.fluidHeadY() > permittedSurfaceIncision(terrain)
                    && basis.naturalHeight() - fluid.fluidHeadY() <= permittedSurfaceIncision(basis))) {
                return false;
            }
        }
        return true;
    }

    boolean surfaceIncisionContained(RiverCourse course, RiverFootprint footprint) {
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(footprint, "footprint");
        if (course.type() != RiverCourseType.SURFACE) {
            return true;
        }
        for (HydrologyColumnSample sample : footprint.columns().values()) {
            for (HydrologyColumnLayer fluid : sample.layers()) {
                if (fluid.feature().courseId() != course.id()
                        || fluid.feature().type() == HydrologyFeatureType.MOUTH
                        || fluid.oceanApron()
                        || fluid.fallingFluid()
                        || !fluid.channel()
                        || !fluid.fluidOwned()) {
                    continue;
                }
                HydrologyTerrainSample basis = sampleTerrainBasis(sample.x(), sample.z());
                HydrologyTerrainSample terrain = sampleTerrain(sample.x(), sample.z());
                if (basis == null
                        || terrain == null
                        || (terrain.naturalHeight() - fluid.fluidHeadY()
                        > settings.surface().maximumIncision()
                        && basis.naturalHeight() - fluid.fluidHeadY()
                        <= permittedSurfaceIncision(basis))) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean surfaceBanksContained(RiverCourse course) {
        Objects.requireNonNull(course, "course");
        if (course.type() != RiverCourseType.SURFACE) {
            return true;
        }
        RiverFootprint footprint = compileCourse(course);
        if (!surfaceCenterlinePublished(course, footprint)
                || !surfaceTransitionCenterlinePublished(course, footprint)) {
            return false;
        }
        for (HydrologyColumnSample sample : footprint.columns().values()) {
            HydrologyColumnLayer fluid = sample.primarySurfaceFluidLayer().orElse(null);
            if (fluid == null || fluid.oceanApron() || fluid.fallingFluid()) {
                continue;
            }
            for (int[] offset : HORIZONTAL_NEIGHBORS) {
                HydrologyColumnSample neighbor = footprint.sample(
                        sample.x() + offset[0],
                        sample.z() + offset[1]
                ).orElse(null);
                if (ownsSurfaceFluid(neighbor, course.id())) {
                    continue;
                }
                if (neighbor != null
                        && fluid.fluidHeadY() <= neighbor.seaLevel()
                        && (neighbor.ocean() || neighbor.naturalHeight() <= neighbor.seaLevel())) {
                    continue;
                }
                if (neighbor == null || neighbor.terrainHeight() <= fluid.fluidHeadY()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean surfaceCenterlinePublished(RiverCourse course, RiverFootprint footprint) {
        for (HydraulicSegment segment : course.segments()) {
            if (!segment.type().isSurface()) {
                continue;
            }
            for (HydrologyPoint point : segment.centerline()) {
                HydrologyColumnSample sample = footprint.sample(point.x(), point.z()).orElse(null);
                if (ownsSurfaceFluid(sample, course.id())
                        || segment.type() == HydrologyFeatureType.MOUTH
                        && ownsOceanApron(sample, course.id())) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private boolean surfaceTransitionCenterlinePublished(RiverCourse course, RiverFootprint footprint) {
        for (int segmentIndex = 0; segmentIndex < course.segments().size(); segmentIndex++) {
            HydraulicSegment segment = course.segments().get(segmentIndex);
            if (!terrainRoofedSurfaceTransition(course, segment)) {
                continue;
            }
            List<HydrologyPoint> centerline = continuousCenterline(segment);
            for (int pointIndex = 0; pointIndex < centerline.size(); pointIndex++) {
                HydrologyPoint point = centerline.get(pointIndex);
                HydrologyColumnSample sample = footprint.sample(point.x(), point.z()).orElse(null);
                if (ownsTransitionFluid(sample, course.id(), segment.id(), point.y())) {
                    continue;
                }
                boolean joinedStart = pointIndex == 0
                        && segmentIndex > 0
                        && segmentsJoin(course.segments().get(segmentIndex - 1), segment);
                boolean joinedEnd = pointIndex == centerline.size() - 1
                        && segmentIndex + 1 < course.segments().size()
                        && segmentsJoin(segment, course.segments().get(segmentIndex + 1));
                if ((joinedStart || joinedEnd) && ownsCourseFluid(sample, course.id(), point.y())) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private boolean ownsTransitionFluid(
            HydrologyColumnSample sample,
            long courseId,
            long segmentId,
            int fluidHead
    ) {
        if (sample == null) {
            return false;
        }
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.feature().courseId() == courseId
                    && layer.feature().segmentId() == segmentId
                    && layer.fluidHeadY() == fluidHead
                    && layer.channel()
                    && layer.connectedFluid()
                    && layer.fluidOwned()) {
                return true;
            }
        }
        return false;
    }

    private boolean ownsCourseFluid(HydrologyColumnSample sample, long courseId, int fluidHead) {
        if (sample == null) {
            return false;
        }
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.feature().courseId() == courseId
                    && layer.fluidHeadY() == fluidHead
                    && layer.channel()
                    && layer.connectedFluid()
                    && layer.fluidOwned()) {
                return true;
            }
        }
        return false;
    }

    private int permittedSurfaceIncision(HydrologyTerrainSample terrain) {
        int maximumIncision = settings.surface().maximumIncision();
        return Math.min(
                maximumIncision,
                (int) StrictMath.floor(maximumIncision * terrain.incisionMultiplier())
        );
    }

    private boolean ownsSurfaceFluid(HydrologyColumnSample sample, long courseId) {
        if (sample == null) {
            return false;
        }
        HydrologyColumnLayer fluid = sample.primarySurfaceFluidLayer().orElse(null);
        return fluid != null
                && fluid.fluidOwned()
                && fluid.feature().courseId() == courseId;
    }

    private boolean ownsOceanApron(HydrologyColumnSample sample, long courseId) {
        if (sample == null) {
            return false;
        }
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.oceanApron() && layer.feature().courseId() == courseId) {
                return true;
            }
        }
        return false;
    }

    private List<HydrologyPoint> surfaceCourseCenterline(RiverCourse course) {
        ArrayList<HydrologyPoint> points = new ArrayList<>();
        for (HydraulicSegment segment : course.segments()) {
            if (!segment.type().isSurface() && segment.type() != HydrologyFeatureType.RIDGE_BORE) {
                break;
            }
            for (HydrologyPoint point : segment.centerline()) {
                if (points.isEmpty()
                        || point.x() != points.getLast().x()
                        || point.z() != points.getLast().z()) {
                    points.add(point);
                }
            }
        }
        return rasterCenterline(points);
    }

    private List<HydrologyPoint> rasterCenterline(List<HydrologyPoint> points) {
        ArrayList<HydrologyPoint> raster = new ArrayList<>();
        for (int pairIndex = 0; pairIndex < points.size() - 1; pairIndex++) {
            HydrologyPoint start = points.get(pairIndex);
            HydrologyPoint end = points.get(pairIndex + 1);
            int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.z() - start.z()));
            if (steps == 0) {
                continue;
            }
            int firstStep = raster.isEmpty() ? 0 : 1;
            for (int step = firstStep; step <= steps; step++) {
                double progress = step / (double) steps;
                raster.add(new HydrologyPoint(
                        (int) StrictMath.round(start.x() + (end.x() - start.x()) * progress),
                        (int) StrictMath.round(start.y() + (end.y() - start.y()) * progress),
                        (int) StrictMath.round(start.z() + (end.z() - start.z()) * progress)
                ));
            }
        }
        return List.copyOf(raster);
    }

    private boolean ownedNear(Set<Long> cells, int x, int z, int radius) {
        for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                if (cells.contains(RiverFootprint.pack(x + deltaX, z + deltaZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int cruiseWidth(Set<Long> cells, List<HydrologyPoint> raster, int maximumWidth) {
        int start = Math.min(raster.size() - 2, Math.max(48, maximumWidth * 4));
        int end = Math.min(raster.size() - 1, Math.max(start + 16, maximumWidth * 6));
        ArrayList<Integer> widths = new ArrayList<>();
        for (int offset = start; offset <= end; offset++) {
            if (offset <= 0 || offset >= raster.size() - 1) {
                continue;
            }
            int width = crossSectionWidth(
                    cells,
                    raster.get(offset),
                    raster.get(offset - 1),
                    raster.get(offset + 1),
                    maximumWidth
            );
            if (width > 0) {
                widths.add(width);
            }
        }
        if (widths.isEmpty()) {
            return maximumWidth;
        }
        widths.sort(Integer::compare);
        return Math.max(1, widths.get(widths.size() / 2));
    }

    private double endpointTransitionLength(
            Set<Long> cells,
            List<HydrologyPoint> raster,
            int maximumWidth,
            int cruiseWidth
    ) {
        int endpointWidth = crossSectionWidth(
                cells,
                raster.getFirst(),
                raster.getFirst(),
                raster.get(1),
                maximumWidth
        );
        int widthTolerance = Math.max(1, (int) StrictMath.ceil(cruiseWidth * 0.1D));
        if (Math.abs(endpointWidth - cruiseWidth) <= widthTolerance) {
            return 0D;
        }
        int search = Math.max(32, cruiseWidth * 6);
        double distance = 0D;
        int pointIndex = 1;
        while (pointIndex < raster.size() && distance <= search) {
            int previousIndex = pointIndex - 1;
            int nextIndex = Math.min(raster.size() - 1, pointIndex + 1);
            HydrologyPoint previous = raster.get(previousIndex);
            HydrologyPoint station = raster.get(pointIndex);
            HydrologyPoint next = raster.get(nextIndex);
            distance += StrictMath.hypot(station.x() - previous.x(), station.z() - previous.z());
            int width = crossSectionWidth(cells, station, previous, next, maximumWidth);
            if (Math.abs(width - cruiseWidth) <= widthTolerance) {
                return distance;
            }
            pointIndex++;
        }
        return distance;
    }

    private int crossSectionWidth(
            Set<Long> cells,
            HydrologyPoint center,
            HydrologyPoint before,
            HydrologyPoint after,
            int maximumWidth
    ) {
        double tangentX = after.x() - before.x();
        double tangentZ = after.z() - before.z();
        double tangentLength = StrictMath.hypot(tangentX, tangentZ);
        if (tangentLength == 0D) {
            tangentX = 1D;
            tangentLength = 1D;
        }
        double perpendicularX = -tangentZ / tangentLength;
        double perpendicularZ = tangentX / tangentLength;
        int limit = Math.max(4, maximumWidth * 2);
        HashSet<Long> sampled = new HashSet<>();
        for (int offset = -limit; offset <= limit; offset++) {
            int x = (int) StrictMath.round(center.x() + perpendicularX * offset);
            int z = (int) StrictMath.round(center.z() + perpendicularZ * offset);
            long packed = RiverFootprint.pack(x, z);
            if (cells.contains(packed)) {
                sampled.add(packed);
            }
        }
        return sampled.size();
    }

    private ValidationCourseRaster compileValidationCourse(RiverCourse course) {
        CourseRasterKey rasterKey = new CourseRasterKey(
                course.id(),
                course.type(),
                course.profileKey(),
                course.segments()
        );
        ValidationCourseRaster cached = validationCourseRasters.get(rasterKey);
        if (cached != null) {
            return cached;
        }

        boolean caveCourse = hasCaveSegment(course);
        features.clear();
        Long2ObjectLinkedOpenHashMap<MutableColumn> columns = new Long2ObjectLinkedOpenHashMap<>();
        List<SurfaceSweep> surfaceSweeps = surfaceSweeps(course, true);
        SurfaceRasterIndex courseSurface = new SurfaceRasterIndex(
                surfaceSweeps,
                new Long2ObjectOpenHashMap<>()
        );
        for (SurfaceSweep sweep : surfaceSweeps) {
            rasterizeSurfaceSweep(columns, sweep, true);
        }
        for (int segmentIndex = 0; segmentIndex < course.segments().size(); segmentIndex++) {
            HydraulicSegment segment = course.segments().get(segmentIndex);
            boolean firstSegment = segmentIndex == 0;
            boolean clipStart = segmentIndex > 0
                    && segmentsJoin(course.segments().get(segmentIndex - 1), segment);
            boolean clipEnd = segmentIndex + 1 < course.segments().size()
                    && segmentsJoin(segment, course.segments().get(segmentIndex + 1));
            if (caveCourse && (segment.type().isUnderground()
                    || segment.type().isDeepFluid())) {
                rasterizeSegment(
                        columns,
                        course,
                        segment,
                        firstSegment,
                        clipStart,
                        clipEnd,
                        true,
                        courseSurface
                );
            }
        }
        ValidationCourseRaster raster = new ValidationCourseRaster(
                course.id(),
                buildValidationColumns(columns),
                surfaceSweeps
        );
        validationCourseRasters.put(rasterKey, raster);
        if (validationCourseRasters.size() > VALIDATION_RASTER_CACHE_SIZE) {
            validationCourseRasters.remove(validationCourseRasters.sequencedKeySet().getFirst());
        }
        return raster;
    }

    private boolean hasCaveSegment(RiverCourse course) {
        for (HydraulicSegment segment : course.segments()) {
            if (segment.type().isUnderground() || segment.type().isDeepFluid()) {
                return true;
            }
        }
        return false;
    }

    private List<HydrologyColumnSample> buildValidationColumns(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns
    ) {
        ArrayList<HydrologyColumnSample> built = new ArrayList<>(columns.size());
        for (MutableColumn column : columns.values()) {
            built.add(column.build());
        }
        built.sort(Comparator.comparingLong(
                (HydrologyColumnSample sample) -> RiverFootprint.pack(sample.x(), sample.z())
        ));
        return List.copyOf(built);
    }

    private static void validateMatchingTerrainMetadata(
            HydrologyColumnSample first,
            HydrologyColumnSample second
    ) {
        if (first.x() != second.x()
                || first.z() != second.z()
                || first.naturalHeight() != second.naturalHeight()
                || first.seaLevel() != second.seaLevel()
                || first.ocean() != second.ocean()
                || !first.parentBiomeKey().equals(second.parentBiomeKey())) {
            throw new IllegalStateException("Hydrology course footprints disagree on terrain metadata at "
                    + first.x() + "," + first.z() + ".");
        }
    }

    private RiverFootprint compileCourse(RiverCourse course) {
        CourseRasterKey rasterKey = new CourseRasterKey(
                course.id(),
                course.type(),
                course.profileKey(),
                course.segments()
        );
        RiverFootprint cached = courseFootprints.get(rasterKey);
        if (cached != null) {
            return cached;
        }
        features.clear();
        Long2ObjectLinkedOpenHashMap<MutableColumn> columns = new Long2ObjectLinkedOpenHashMap<>();
        List<SurfaceSweep> surfaceSweeps = surfaceSweeps(course, true);
        SurfaceRasterIndex courseSurface = new SurfaceRasterIndex(
                surfaceSweeps,
                new Long2ObjectOpenHashMap<>()
        );
        for (SurfaceSweep sweep : surfaceSweeps) {
            rasterizeSurfaceSweep(columns, sweep, false);
        }
        materializeSurfaceBankTerrain(columns);
        containSurfaceBanks(columns);
        for (int segmentIndex = 0; segmentIndex < course.segments().size(); segmentIndex++) {
            HydraulicSegment segment = course.segments().get(segmentIndex);
            if (segment.type().isSurface()) {
                continue;
            }
            boolean clipStart = segmentIndex > 0
                    && segmentsJoin(course.segments().get(segmentIndex - 1), segment);
            boolean clipEnd = segmentIndex + 1 < course.segments().size()
                    && segmentsJoin(segment, course.segments().get(segmentIndex + 1));
            rasterizeSegment(
                    columns,
                    course,
                    segment,
                    segmentIndex == 0,
                    clipStart,
                    clipEnd,
                    false,
                    courseSurface
            );
        }
        RiverFootprint footprint = build(columns);
        courseFootprints.put(rasterKey, footprint);
        if (courseFootprints.size() > COURSE_FOOTPRINT_CACHE_SIZE) {
            courseFootprints.remove(courseFootprints.sequencedKeySet().getFirst());
        }
        return footprint;
    }

    private RiverFootprint build(Long2ObjectLinkedOpenHashMap<MutableColumn> columns) {
        LinkedHashMap<Long, HydrologyColumnSample> immutable = new LinkedHashMap<>();
        for (Long2ObjectMap.Entry<MutableColumn> entry : columns.long2ObjectEntrySet()) {
            immutable.put(entry.getLongKey(), entry.getValue().build());
        }
        return new RiverFootprint(immutable);
    }

    private void containSurfaceBanks(Long2ObjectLinkedOpenHashMap<MutableColumn> columns) {
        for (MutableColumn column : columns.values()) {
            if (column.maximumSurfaceFluidHead() != Integer.MIN_VALUE) {
                continue;
            }
            int minimumBankHeight = Integer.MIN_VALUE;
            for (int[] offset : HORIZONTAL_NEIGHBORS) {
                MutableColumn neighbor = columns.get(RiverFootprint.pack(
                        column.x + offset[0],
                        column.z + offset[1]
                ));
                if (neighbor == null) {
                    continue;
                }
                int fluidHead = neighbor.maximumSurfaceFluidHead();
                if (fluidHead != Integer.MIN_VALUE) {
                    minimumBankHeight = Math.max(minimumBankHeight, Math.addExact(fluidHead, 1));
                }
            }
            if (minimumBankHeight != Integer.MIN_VALUE) {
                column.raiseDrySurfaceBed(minimumBankHeight);
            }
        }
    }

    private void materializeSurfaceBankTerrain(Long2ObjectLinkedOpenHashMap<MutableColumn> columns) {
        ArrayList<Long> wetColumns = new ArrayList<>();
        for (Long2ObjectMap.Entry<MutableColumn> entry : columns.long2ObjectEntrySet()) {
            if (entry.getValue().maximumSurfaceFluidHead() != Integer.MIN_VALUE) {
                wetColumns.add(entry.getLongKey());
            }
        }
        for (long packed : wetColumns) {
            int x = RiverFootprint.unpackX(packed);
            int z = RiverFootprint.unpackZ(packed);
            for (int[] offset : HORIZONTAL_NEIGHBORS) {
                int bankX = Math.addExact(x, offset[0]);
                int bankZ = Math.addExact(z, offset[1]);
                long bankKey = RiverFootprint.pack(bankX, bankZ);
                if (columns.containsKey(bankKey)) {
                    continue;
                }
                classifyNatural(bankX, bankZ);
                HydrologyTerrainSample terrain = sampleTerrain(bankX, bankZ);
                if (terrain != null) {
                    columns.put(bankKey, new MutableColumn(bankX, bankZ, terrain, settings.seaLevel()));
                }
            }
        }
    }

    private static HydrologyColumnLayer raisedDrySurfaceLayer(
            HydrologyColumnLayer layer,
            int naturalHeight,
            int minimumBed
    ) {
        if (minimumBed == Integer.MIN_VALUE
                || !layer.feature().type().isSurface()
                || !layer.terrainOwned()
                || layer.channel()) {
            return layer;
        }
        int bed = Math.min(naturalHeight, Math.max(layer.bedY(), minimumBed));
        if (bed == layer.bedY()) {
            return layer;
        }
        return new HydrologyColumnLayer(
                layer.feature(),
                bed,
                bed,
                bed,
                false,
                layer.shore(),
                layer.grading(),
                false,
                false,
                false,
                true,
                false,
                false,
                layer.profileKey(),
                layer.surfaceBiomeKey(),
                layer.mouthBiomeKey(),
                layer.shoreBiomeKey(),
                layer.dryBiomeKey(),
                layer.floodedCaveBiomeKey()
        );
    }

    private List<SurfaceSweep> surfaceSweeps(RiverCourse course, boolean includeFalling) {
        ArrayList<SurfaceSweep> sweeps = new ArrayList<>();
        int runStart = -1;
        for (int segmentIndex = 0; segmentIndex <= course.segments().size(); segmentIndex++) {
            boolean sweptSurface = segmentIndex < course.segments().size()
                    && course.segments().get(segmentIndex).type().isSurface()
                    && !course.segments().get(segmentIndex).fallingFluid();
            if (sweptSurface && runStart < 0) {
                runStart = segmentIndex;
            }
            if (sweptSurface || runStart < 0) {
                continue;
            }
            sweeps.add(surfaceSweep(course, runStart, segmentIndex));
            runStart = -1;
        }
        if (includeFalling) {
            for (int segmentIndex = 0; segmentIndex < course.segments().size(); segmentIndex++) {
                HydraulicSegment segment = course.segments().get(segmentIndex);
                if (!segment.type().isSurface() || !segment.fallingFluid()) {
                    continue;
                }
                sweeps.addAll(fallingSurfaceSweeps(course, segmentIndex));
            }
        }
        return List.copyOf(sweeps);
    }

    private List<SurfaceSweep> fallingSurfaceSweeps(RiverCourse course, int segmentIndex) {
        HydraulicSegment segment = course.segments().get(segmentIndex);
        List<HydrologyPoint> centerline = continuousCenterline(segment);
        HydrologyPoint throat = centerline.getFirst();
        int flowX = flowDelta(centerline, 0, true);
        int flowZ = flowDelta(centerline, 0, false);
        SurfaceSweepPoint throatPoint = new SurfaceSweepPoint(
                segment,
                throat,
                shape(
                        course,
                        segment,
                        throat,
                        segment.downstreamHeadY() - 1,
                        segment.upstreamHeadY(),
                        true,
                        false
                ),
                segmentIndex == 0,
                false,
                true
        );
        ArrayList<SurfaceSweep> sweeps = new ArrayList<>();
        sweeps.add(new SurfaceSweep(
                course,
                List.of(throatPoint),
                segmentIndex == 0,
                false,
                flowX,
                flowZ
        ));
        if (centerline.size() == 1) {
            return List.copyOf(sweeps);
        }
        ArrayList<SurfaceSweepPoint> downstream = new ArrayList<>(centerline.size() - 1);
        for (int pointIndex = 1; pointIndex < centerline.size(); pointIndex++) {
            HydrologyPoint point = centerline.get(pointIndex);
            boolean receiving = segment.receivingPool() && pointIndex == centerline.size() - 1;
            downstream.add(new SurfaceSweepPoint(
                    segment,
                    point,
                    shape(
                            course,
                            segment,
                            point,
                            point.y() - segment.depth(),
                            point.y(),
                            false,
                            receiving
                    ),
                    false,
                    receiving,
                    false
            ));
        }
        boolean roundEnd = segmentIndex + 1 >= course.segments().size()
                || !course.segments().get(segmentIndex + 1).fallingFluid();
        sweeps.add(new SurfaceSweep(course, List.copyOf(downstream), false, roundEnd, 0, 0));
        return List.copyOf(sweeps);
    }

    private SurfaceSweep surfaceSweep(RiverCourse course, int startSegmentIndex, int endSegmentIndex) {
        ArrayList<SurfaceSweepPoint> points = new ArrayList<>();
        for (int segmentIndex = startSegmentIndex; segmentIndex < endSegmentIndex; segmentIndex++) {
            HydraulicSegment segment = course.segments().get(segmentIndex);
            List<HydrologyPoint> centerline = continuousCenterline(segment);
            for (int pointIndex = 0; pointIndex < centerline.size(); pointIndex++) {
                HydrologyPoint point = centerline.get(pointIndex);
                boolean receiving = segment.receivingPool() && pointIndex == centerline.size() - 1;
                LayerShape pointShape = shape(
                        course,
                        segment,
                        point,
                        point.y() - segment.depth(),
                        point.y(),
                        false,
                        receiving
                );
                SurfaceSweepPoint sweepPoint = new SurfaceSweepPoint(
                        segment,
                        point,
                        pointShape,
                        false,
                        receiving,
                        false
                );
                if (!points.isEmpty()
                        && points.getLast().point().x() == point.x()
                        && points.getLast().point().z() == point.z()) {
                    SurfaceSweepPoint existing = points.getLast();
                    points.set(points.size() - 1, existing.receiving() ? existing : sweepPoint);
                } else {
                    points.add(sweepPoint);
                }
            }
        }
        if (points.isEmpty()) {
            throw new IllegalStateException("A surface sweep requires at least one centerline point.");
        }
        HydraulicSegment preceding = startSegmentIndex > 0
                ? course.segments().get(startSegmentIndex - 1)
                : null;
        HydraulicSegment following = endSegmentIndex < course.segments().size()
                ? course.segments().get(endSegmentIndex)
                : null;
        SurfaceTerminal terminal = surfaceTerminal(points.getLast().segment());
        boolean source = startSegmentIndex == 0;
        boolean roundStart = source;
        boolean roundEnd = terminal != SurfaceTerminal.NONE;
        return new SurfaceSweep(
                course,
                profileSurfaceSweep(points, source, preceding, terminal, following),
                roundStart,
                roundEnd,
                0,
                0
        );
    }

    private SurfaceTerminal surfaceTerminal(HydraulicSegment finalSurface) {
        if (finalSurface.type() == HydrologyFeatureType.MOUTH) {
            return SurfaceTerminal.OCEAN_MOUTH;
        }
        return SurfaceTerminal.NONE;
    }

    private List<SurfaceSweepPoint> profileSurfaceSweep(
            List<SurfaceSweepPoint> rawPoints,
            boolean source,
            HydraulicSegment preceding,
            SurfaceTerminal terminal,
            HydraulicSegment following
    ) {
        double[] distances = new double[rawPoints.size()];
        for (int pointIndex = 1; pointIndex < rawPoints.size(); pointIndex++) {
            HydrologyPoint previous = rawPoints.get(pointIndex - 1).point();
            HydrologyPoint current = rawPoints.get(pointIndex).point();
            distances[pointIndex] = distances[pointIndex - 1]
                    + StrictMath.hypot(current.x() - previous.x(), current.z() - previous.z());
        }
        double totalLength = distances[distances.length - 1];
        boolean profileSource = source && totalLength > 0D;
        boolean profileBoreEntry = following != null
                && following.type().isUnderground()
                && segmentsJoin(rawPoints.getLast().segment(), following)
                && totalLength > 0D;
        boolean profileBoreExit = preceding != null
                && preceding.type().isUnderground()
                && preceding.type() != HydrologyFeatureType.SINKHOLE
                && segmentsJoin(preceding, rawPoints.getFirst().segment())
                && totalLength > 0D;
        boolean profileTerminal = terminal != SurfaceTerminal.NONE
                && totalLength >= MINIMUM_ENDPOINT_PROFILE_LENGTH;
        int portalLength = Math.min(
                SURFACE_SHAPE_PROFILE_LENGTH,
                Math.max(1, (int) StrictMath.floor(totalLength))
        );
        List<LayerShape> transitionShapes = smoothSurfaceSweepShapes(rawPoints, distances);
        ArrayList<SurfaceSweepPoint> profiled = new ArrayList<>(rawPoints.size());
        for (int pointIndex = 0; pointIndex < rawPoints.size(); pointIndex++) {
            SurfaceSweepPoint point = rawPoints.get(pointIndex);
            LayerShape pointShape = transitionShapes.get(pointIndex);
            if (profileSource) {
                int sourceLength = Math.min(
                        endpointProfileLength(pointShape.channelRadius()),
                        Math.max(1, (int) StrictMath.floor(totalLength))
                );
                if (profileTerminal) {
                    sourceLength = Math.min(
                            sourceLength,
                            Math.max(1, (int) StrictMath.floor(totalLength / 2D))
                    );
                }
                double sourceWeight = 1D - smoothStep(Math.min(1D, distances[pointIndex] / sourceLength));
                pointShape = sourceTaper(pointShape, sourceWeight);
            }
            if (profileBoreExit) {
                double exitWeight = 1D - smoothStep(Math.min(1D, distances[pointIndex] / portalLength));
                pointShape = borePortalShape(pointShape, exitWeight);
            }
            if (profileBoreEntry) {
                double remaining = totalLength - distances[pointIndex];
                double entryWeight = 1D - smoothStep(Math.min(1D, remaining / portalLength));
                pointShape = borePortalShape(pointShape, entryWeight);
            }
            boolean receiving = point.receiving();
            if (profileTerminal) {
                int terminalLength = endpointProfileLength(pointShape.channelRadius());
                if (profileSource) {
                    terminalLength = Math.min(
                            terminalLength,
                            Math.max(1, (int) StrictMath.floor(totalLength / 2D))
                    );
                }
                double remaining = totalLength - distances[pointIndex];
                double terminalWeight = 1D - smoothStep(Math.min(1D, remaining / terminalLength));
                pointShape = terminalShape(pointShape, terminal, terminalWeight);
            }
            profiled.add(new SurfaceSweepPoint(
                    point.segment(),
                    point.point(),
                    pointShape,
                    point.source(),
                    receiving,
                    point.falling()
            ));
        }
        return List.copyOf(profiled);
    }

    private LayerShape borePortalShape(LayerShape shape, double weight) {
        return resizedSurfaceShape(
                shape,
                shape.channelRadius(),
                shape.shoreWidth() * (1D - weight),
                shape.gradingWidth() * (1D - weight),
                shape.fluidHead() - shape.bed()
        );
    }

    private List<LayerShape> smoothSurfaceSweepShapes(
            List<SurfaceSweepPoint> points,
            double[] distances
    ) {
        ArrayList<LayerShape> smoothed = new ArrayList<>(points.size());
        for (int pointIndex = 0; pointIndex < points.size(); pointIndex++) {
            LayerShape current = points.get(pointIndex).shape();
            double channelRadius = current.channelRadius();
            double shoreWidth = current.shoreWidth();
            double gradingWidth = current.gradingWidth();
            double depth = current.fluidHead() - current.bed();
            int firstCandidate = pointIndex;
            while (firstCandidate > 0
                    && distances[pointIndex] - distances[firstCandidate - 1] <= SURFACE_SHAPE_PROFILE_LENGTH) {
                firstCandidate--;
            }
            int lastCandidate = pointIndex;
            while (lastCandidate + 1 < points.size()
                    && distances[lastCandidate + 1] - distances[pointIndex] <= SURFACE_SHAPE_PROFILE_LENGTH) {
                lastCandidate++;
            }
            for (int candidateIndex = firstCandidate; candidateIndex <= lastCandidate; candidateIndex++) {
                double distance = StrictMath.abs(distances[candidateIndex] - distances[pointIndex]);
                LayerShape candidate = points.get(candidateIndex).shape();
                double influence = smoothStep(1D - distance / SURFACE_SHAPE_PROFILE_LENGTH);
                channelRadius = Math.max(
                        channelRadius,
                        channelRadius + (candidate.channelRadius() - channelRadius) * influence
                );
                shoreWidth = Math.max(
                        shoreWidth,
                        shoreWidth + (candidate.shoreWidth() - shoreWidth) * influence
                );
                gradingWidth = Math.max(
                        gradingWidth,
                        gradingWidth + (candidate.gradingWidth() - gradingWidth) * influence
                );
                int candidateDepth = candidate.fluidHead() - candidate.bed();
                depth = Math.max(depth, depth + (candidateDepth - depth) * influence);
            }
            smoothed.add(resizedSurfaceShape(
                    current,
                    (int) StrictMath.round(channelRadius),
                    shoreWidth,
                    gradingWidth,
                    (int) StrictMath.round(depth)
            ));
        }
        return List.copyOf(smoothed);
    }

    private int endpointProfileLength(int channelRadius) {
        return Math.max(
                MINIMUM_ENDPOINT_PROFILE_LENGTH,
                Math.min(MAXIMUM_ENDPOINT_PROFILE_LENGTH, Math.multiplyExact(channelRadius, 6))
        );
    }

    private LayerShape sourceTaper(LayerShape shape, double weight) {
        return resizedSurfaceShape(
                shape,
                blend(shape.channelRadius(), 1, weight),
                shape.shoreWidth(),
                shape.gradingWidth(),
                shape.fluidHead() - shape.bed()
        );
    }

    private LayerShape terminalShape(
            LayerShape shape,
            SurfaceTerminal terminal,
            double weight
    ) {
        return switch (terminal) {
            case NONE -> shape;
            case OCEAN_MOUTH -> {
                int targetRadius = mouthFlareRadius(shape);
                yield resizedSurfaceShape(
                        shape,
                        blend(shape.channelRadius(), targetRadius, weight),
                        shape.shoreWidth() + weight,
                        shape.gradingWidth() + weight * Math.max(2D, shape.channelRadius() * 0.5D),
                        blend(shape.fluidHead() - shape.bed(), 1, weight)
                );
            }
        };
    }

    private int mouthFlareRadius(LayerShape shape) {
        return Math.max(
                Math.addExact(shape.channelRadius(), 2),
                (int) StrictMath.ceil(shape.channelRadius() * MOUTH_FLARE_RATIO)
        );
    }

    private LayerShape resizedSurfaceShape(
            LayerShape shape,
            int channelRadius,
            double shoreWidth,
            double gradingWidth,
            int depth
    ) {
        return new LayerShape(
                channelRadius,
                shoreWidth,
                gradingWidth,
                shape.fluidHead() - depth,
                shape.fluidHead(),
                shape.ceiling(),
                shape.ellipsoid(),
                shape.archedChannel(),
                shape.roundedSurfaceBed(),
                shape.organicBoundary(),
                shape.fallingThroat()
        );
    }

    private int blend(int start, int end, double progress) {
        return (int) StrictMath.round(start + (end - start) * progress);
    }

    private double smoothStep(double progress) {
        return progress * progress * (3D - 2D * progress);
    }

    private void rasterizeSurfaceSweep(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            SurfaceSweep sweep,
            boolean validationOnly
    ) {
        Long2ObjectOpenHashMap<SurfaceProjection> projections = new Long2ObjectOpenHashMap<>();
        if (sweep.points().size() == 1) {
            collectSurfaceEdge(projections, sweep, -1);
        } else {
            for (int edgeIndex = 0; edgeIndex < sweep.points().size() - 1; edgeIndex++) {
                collectSurfaceEdge(projections, sweep, edgeIndex);
            }
        }
        long[] positions = projections.keySet().toLongArray();
        Arrays.sort(positions);
        for (long position : positions) {
            int x = RiverFootprint.unpackX(position);
            int z = RiverFootprint.unpackZ(position);
            rasterizeSurfaceProjection(columns, projections.get(position), x, z, validationOnly);
        }
    }

    private void collectSurfaceEdge(
            Long2ObjectOpenHashMap<SurfaceProjection> projections,
            SurfaceSweep sweep,
            int edgeIndex
    ) {
        SurfaceSweepPoint start = sweep.points().get(Math.max(0, edgeIndex));
        SurfaceSweepPoint end = edgeIndex < 0 ? start : sweep.points().get(edgeIndex + 1);
        int radius = (int) StrictMath.ceil(Math.max(start.shape().totalRadius(), end.shape().totalRadius()));
        int minimumX = Math.min(start.point().x(), end.point().x()) - radius;
        int maximumX = Math.max(start.point().x(), end.point().x()) + radius;
        int minimumZ = Math.min(start.point().z(), end.point().z()) - radius;
        int maximumZ = Math.max(start.point().z(), end.point().z()) + radius;
        for (int z = minimumZ; z <= maximumZ; z++) {
            for (int x = minimumX; x <= maximumX; x++) {
                SurfaceProjection candidate = surfaceProjection(sweep, edgeIndex, x, z);
                if (candidate == null) {
                    continue;
                }
                long packed = RiverFootprint.pack(x, z);
                SurfaceProjection selected = projections.get(packed);
                if (selected == null || prefersSurfaceProjection(candidate, selected)) {
                    projections.put(packed, candidate);
                }
            }
        }
    }

    private SurfaceProjection surfaceProjection(SurfaceSweep sweep, int edgeIndex, int x, int z) {
        if (edgeIndex < 0) {
            SurfaceSweepPoint point = sweep.points().getFirst();
            double distance = StrictMath.hypot(x - point.point().x(), z - point.point().z());
            if (distance > point.shape().totalRadius() + 0.25D) {
                return null;
            }
            return new SurfaceProjection(
                    sweep,
                    edgeIndex,
                    point.segment(),
                    point.point(),
                    point.shape(),
                    point.source() || sweep.roundStart(),
                    point.receiving(),
                    point.falling(),
                    point.segment().type() == HydrologyFeatureType.MOUTH,
                    point.point().x(),
                    point.point().z(),
                    distance,
                    sweep.singletonFlowX(),
                    sweep.singletonFlowZ(),
                    false
            );
        }
        SurfaceSweepPoint start = sweep.points().get(edgeIndex);
        SurfaceSweepPoint end = sweep.points().get(edgeIndex + 1);
        double deltaX = end.point().x() - start.point().x();
        double deltaZ = end.point().z() - start.point().z();
        double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (lengthSquared == 0D) {
            return null;
        }
        double rawProgress = ((x - start.point().x()) * deltaX + (z - start.point().z()) * deltaZ)
                / lengthSquared;
        boolean extendStart = edgeIndex == 0 && sweep.roundStart();
        boolean extendEnd = edgeIndex == sweep.points().size() - 2 && sweep.roundEnd();
        if (rawProgress < 0D && !extendStart || rawProgress > 1D && !extendEnd) {
            return null;
        }
        double progress = Math.max(0D, Math.min(1D, rawProgress));
        double projectedX = start.point().x() + deltaX * progress;
        double projectedZ = start.point().z() + deltaZ * progress;
        double distance = StrictMath.hypot(x - projectedX, z - projectedZ);
        LayerShape projectedShape = interpolateSurfaceShape(start.shape(), end.shape(), progress);
        boolean secondHalf = progress >= 0.5D;
        SurfaceSweepPoint selected = secondHalf ? end : start;
        if (distance > projectedShape.totalRadius() + 0.25D) {
            return null;
        }
        int tangentIndex = secondHalf ? edgeIndex + 1 : edgeIndex;
        int[] tangent = surfaceTangent(sweep.points(), tangentIndex);
        return new SurfaceProjection(
                sweep,
                edgeIndex,
                selected.segment(),
                selected.point(),
                projectedShape,
                sweep.roundStart() && edgeIndex == 0 && progress < 0.5D,
                selected.receiving(),
                selected.falling(),
                selected.segment().type() == HydrologyFeatureType.MOUTH
                        && edgeIndex == sweep.points().size() - 2
                        && progress >= 0.5D,
                projectedX,
                projectedZ,
                distance,
                tangent[0],
                tangent[1],
                rawProgress < 0D || rawProgress > 1D
        );
    }

    private LayerShape interpolateSurfaceShape(LayerShape start, LayerShape end, double progress) {
        LayerShape selected = progress < 0.5D ? start : end;
        return new LayerShape(
                blend(start.channelRadius(), end.channelRadius(), progress),
                start.shoreWidth() + (end.shoreWidth() - start.shoreWidth()) * progress,
                start.gradingWidth() + (end.gradingWidth() - start.gradingWidth()) * progress,
                blend(start.bed(), end.bed(), progress),
                blend(start.fluidHead(), end.fluidHead(), progress),
                blend(start.ceiling(), end.ceiling(), progress),
                selected.ellipsoid(),
                selected.archedChannel(),
                selected.roundedSurfaceBed(),
                selected.organicBoundary(),
                selected.fallingThroat()
        );
    }

    private int[] surfaceTangent(List<SurfaceSweepPoint> points, int pointIndex) {
        int reach = Math.max(2, settings.routing().refinementSpacing() * 2);
        HydrologyPoint start = points.get(Math.max(0, pointIndex - reach)).point();
        HydrologyPoint end = points.get(Math.min(points.size() - 1, pointIndex + reach)).point();
        int flowX = end.x() - start.x();
        int flowZ = end.z() - start.z();
        return flowX == 0 && flowZ == 0 ? new int[]{1, 0} : new int[]{flowX, flowZ};
    }

    private boolean prefersSurfaceProjection(SurfaceProjection candidate, SurfaceProjection selected) {
        int distanceComparison = Double.compare(candidate.distance(), selected.distance());
        if (distanceComparison != 0) {
            return distanceComparison < 0;
        }
        if (candidate.receiving() != selected.receiving()) {
            return candidate.receiving();
        }
        return candidate.edgeIndex() < selected.edgeIndex();
    }

    private void rasterizeSurfaceProjection(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            SurfaceProjection projection,
            int x,
            int z,
            boolean validationOnly
    ) {
        SurfaceCell cell = surfaceCell(projection, x, z);
        if (cell == null) {
            return;
        }
        if (cell.layer() != null) {
            if (!validationOnly) {
                addLayer(columns, x, z, cell.terrain(), cell.layer());
            }
            return;
        }
        HydrologyFeatureRef[] pointFeatures = new HydrologyFeatureRef[FEATURE_ROLE_COUNT];
        double distance = surfaceDistance(projection, x, z);
        if (cell.naturalOcean() && oceanApronEligible(projection.segment(), distance)) {
            addOceanApron(
                    columns,
                    projection.sweep().course(),
                    projection.segment(),
                    projection.anchor(),
                    x,
                    z,
                    distance,
                    projection.flowX(),
                    projection.flowZ(),
                    cell.terrain(),
                    pointFeatures
            );
        }
    }

    private void rasterizeSegment(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            RiverCourse course,
            HydraulicSegment segment,
            boolean firstSegment,
            boolean clipStart,
            boolean clipEnd,
            boolean validationOnly,
            SurfaceRasterIndex plannedSurface
    ) {
        List<HydrologyPoint> centerline = continuousCenterline(segment);
        if (segment.fallingFluid() && centerline.size() > 1) {
            HydrologyPoint throat = centerline.getFirst();
            int fluidHead = segment.upstreamHeadY();
            LayerShape throatShape = shape(
                    course,
                    segment,
                    throat,
                    segment.downstreamHeadY() - 1,
                    fluidHead,
                    true,
                    false
            );
            rasterizePoint(
                    columns,
                    course,
                    segment,
                    throat,
                    0,
                    throatShape,
                    flowDelta(centerline, 0, true),
                    flowDelta(centerline, 0, false),
                    firstSegment,
                    true,
                    false,
                    false,
                    validationOnly,
                    plannedSurface
            );
            rasterizeSweptSegment(
                    columns,
                    course,
                    segment,
                    List.copyOf(centerline.subList(1, centerline.size())),
                    false,
                    true,
                    clipEnd,
                    validationOnly,
                    plannedSurface
            );
            return;
        }
        if (sweptChannel(segment, centerline)) {
            rasterizeSweptSegment(
                    columns,
                    course,
                    segment,
                    centerline,
                    firstSegment,
                    clipStart,
                    clipEnd,
                    validationOnly,
                    plannedSurface
            );
            return;
        }
        for (int pointIndex = 0; pointIndex < centerline.size(); pointIndex++) {
            HydrologyPoint point = centerline.get(pointIndex);
            boolean oceanConnection = (segment.type() == HydrologyFeatureType.MOUTH
                    || segment.type() == HydrologyFeatureType.COASTAL_GROTTO)
                    && pointIndex == centerline.size() - 1;
            boolean falling = segment.fallingFluid() && pointIndex == 0;
            boolean receiving = segment.receivingPool() && pointIndex == centerline.size() - 1;
            int fluidHead = falling ? segment.upstreamHeadY() : point.y();
            int bed = falling
                    ? segment.downstreamHeadY() - 1
                    : fluidHead - segment.depth();
            LayerShape shape = shape(course, segment, point, bed, fluidHead, falling, receiving);
            int flowX = flowDelta(centerline, pointIndex, true);
            int flowZ = flowDelta(centerline, pointIndex, false);
            rasterizePoint(
                    columns,
                    course,
                    segment,
                    point,
                    pointIndex,
                    shape,
                    flowX,
                    flowZ,
                    firstSegment && pointIndex == 0,
                    falling,
                    receiving,
                    oceanConnection,
                    validationOnly,
                    plannedSurface
            );
        }
    }

    private boolean sweptChannel(
            HydraulicSegment segment,
            List<HydrologyPoint> centerline
    ) {
        if (centerline.size() < 2 || segment.fallingFluid()) {
            return false;
        }
        return segment.type().isSurface()
                || segment.type() == HydrologyFeatureType.RIDGE_BORE
                || segment.type() == HydrologyFeatureType.UNDERGROUND_POOL
                || segment.type() == HydrologyFeatureType.UNDERGROUND_DROP
                || segment.type() == HydrologyFeatureType.SINKHOLE
                || segment.type() == HydrologyFeatureType.DEEP_CHANNEL;
    }

    private void rasterizeSweptSegment(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            RiverCourse course,
            HydraulicSegment segment,
            List<HydrologyPoint> centerline,
            boolean firstSegment,
            boolean clipStart,
            boolean clipEnd,
            boolean validationOnly,
            SurfaceRasterIndex plannedSurface
    ) {
        LayerShape[] shapes = new LayerShape[centerline.size()];
        int maximumRadius = 1;
        int minimumX = Integer.MAX_VALUE;
        int maximumX = Integer.MIN_VALUE;
        int minimumZ = Integer.MAX_VALUE;
        int maximumZ = Integer.MIN_VALUE;
        for (int pointIndex = 0; pointIndex < centerline.size(); pointIndex++) {
            HydrologyPoint point = centerline.get(pointIndex);
            int fluidHead = point.y();
            boolean receiving = segment.receivingPool() && pointIndex == centerline.size() - 1;
            LayerShape shape = shape(
                    course,
                    segment,
                    point,
                    fluidHead - segment.depth(),
                    fluidHead,
                    false,
                    receiving
            );
            shapes[pointIndex] = shape;
            int rasterRadius = segment.type().isSurface()
                    ? (int) StrictMath.ceil(shape.totalRadius())
                    : shape.channelRadius();
            maximumRadius = Math.max(maximumRadius, rasterRadius);
            minimumX = Math.min(minimumX, point.x());
            maximumX = Math.max(maximumX, point.x());
            minimumZ = Math.min(minimumZ, point.z());
            maximumZ = Math.max(maximumZ, point.z());
        }
        minimumX = Math.subtractExact(minimumX, maximumRadius);
        maximumX = Math.addExact(maximumX, maximumRadius);
        minimumZ = Math.subtractExact(minimumZ, maximumRadius);
        maximumZ = Math.addExact(maximumZ, maximumRadius);
        HydrologyFeatureRef[][] pointFeatures = new HydrologyFeatureRef[centerline.size()][FEATURE_ROLE_COUNT];
        for (int z = minimumZ; z <= maximumZ; z++) {
            for (int x = minimumX; x <= maximumX; x++) {
                if (!withinLongitudinalBounds(centerline, x, z, clipStart, clipEnd)) {
                    continue;
                }
                CenterlineProjection projection = projectCenterline(centerline, x, z);
                int pointIndex = projection.pointIndex();
                HydrologyPoint point = centerline.get(pointIndex);
                boolean receiving = segment.receivingPool() && pointIndex == centerline.size() - 1;
                LayerShape shape = shapes[pointIndex];
                int deltaX = x - point.x();
                int deltaZ = z - point.z();
                double rawDistance = projection.distance();
                double rasterRadius = segment.type().isSurface()
                        ? shape.totalRadius()
                        : shape.channelRadius();
                if (rawDistance > rasterRadius + 0.25D) {
                    continue;
                }
                int flowX = projection.flowX();
                int flowZ = projection.flowZ();
                double channelDistance = shapedDistance(
                        shape,
                        segment,
                        x,
                        z,
                        x - projection.x(),
                        z - projection.z(),
                        flowX,
                        flowZ,
                        rawDistance
                );
                boolean channel = channelDistance <= shape.channelRadius() + 0.25D;
                if (!segment.type().isSurface() && !channel) {
                    continue;
                }
                boolean shore = !channel
                        && channelDistance <= shape.channelRadius() + shape.shoreWidth() + 0.25D;
                boolean grading = !channel && !shore && rawDistance <= shape.totalRadius() + 0.25D;
                if (!channel && !shore && !grading) {
                    continue;
                }
                double distance = grading ? rawDistance : channelDistance;
                HydrologyRoutingTerrainSampler.NaturalClassification classification = classifyNatural(x, z);
                boolean oceanConnection = (segment.type() == HydrologyFeatureType.MOUTH
                        || segment.type() == HydrologyFeatureType.COASTAL_GROTTO)
                        && pointIndex == centerline.size() - 1;
                boolean apronEligible = oceanApronEligible(segment, distance);
                if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                        && !apronEligible) {
                    continue;
                }
                if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.LAND
                        && oceanConnection) {
                    continue;
                }
                boolean exactSlope = classification != HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                        && !oceanConnection
                        && !channel
                        && segment.type() != HydrologyFeatureType.WATERFALL
                        && segment.type() != HydrologyFeatureType.CASCADE;
                HydrologyTerrainSample terrain = exactSlope ? sampleTerrain(x, z) : sampleTerrainBasis(x, z);
                if (terrain == null) {
                    continue;
                }
                boolean naturalOcean = classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                        || terrain.ocean()
                        || naturallySubmergedSurfaceColumn(segment, terrain);
                if (oceanConnection || naturalOcean) {
                    if (naturalOcean && apronEligible) {
                        addOceanApron(columns, course, segment, point, x, z, distance, flowX, flowZ,
                                terrain, pointFeatures[pointIndex]);
                    }
                    continue;
                }
                boolean source = firstSegment && pointIndex == 0;
                if (validationOnly && segment.type().isSurface()) {
                    feature(course, segment, x, shape.fluidHead(), z, flowX, flowZ,
                            source && deltaX == 0 && deltaZ == 0, channel, shore, grading,
                            false, false, pointFeatures[pointIndex]);
                    continue;
                }
                HydrologyColumnLayer layer = regularLayer(
                        course,
                        segment,
                        point,
                        shape,
                        terrain,
                        distance,
                        deltaX,
                        deltaZ,
                        flowX,
                        flowZ,
                        source,
                        channel,
                        shore,
                        grading,
                        false,
                        receiving,
                        pointFeatures[pointIndex]
                );
                layer = terrainContainedCaveLayer(course, segment, terrain, layer, x, z, plannedSurface);
                if (layer == null) {
                    continue;
                }
                addLayer(columns, x, z, terrain, layer);
            }
        }
    }

    private CenterlineProjection projectCenterline(List<HydrologyPoint> centerline, int x, int z) {
        if (centerline.size() == 1) {
            HydrologyPoint point = centerline.getFirst();
            return new CenterlineProjection(
                    0,
                    point.x(),
                    point.z(),
                    StrictMath.hypot(x - point.x(), z - point.z()),
                    1,
                    0
            );
        }
        int selectedPoint = 0;
        double selectedX = centerline.getFirst().x();
        double selectedZ = centerline.getFirst().z();
        double selectedDistanceSquared = Double.POSITIVE_INFINITY;
        int selectedFlowX = 1;
        int selectedFlowZ = 0;
        for (int segmentIndex = 0; segmentIndex < centerline.size() - 1; segmentIndex++) {
            HydrologyPoint start = centerline.get(segmentIndex);
            HydrologyPoint end = centerline.get(segmentIndex + 1);
            double flowX = end.x() - start.x();
            double flowZ = end.z() - start.z();
            double lengthSquared = flowX * flowX + flowZ * flowZ;
            if (lengthSquared == 0D) {
                continue;
            }
            double progress = ((x - start.x()) * flowX + (z - start.z()) * flowZ) / lengthSquared;
            progress = Math.max(0D, Math.min(1D, progress));
            double projectedX = start.x() + flowX * progress;
            double projectedZ = start.z() + flowZ * progress;
            double deltaX = x - projectedX;
            double deltaZ = z - projectedZ;
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared >= selectedDistanceSquared) {
                continue;
            }
            selectedDistanceSquared = distanceSquared;
            selectedX = projectedX;
            selectedZ = projectedZ;
            selectedPoint = progress < 0.5D ? segmentIndex : segmentIndex + 1;
            selectedFlowX = end.x() - start.x();
            selectedFlowZ = end.z() - start.z();
        }
        int tangentReach = Math.max(2, settings.routing().refinementSpacing() * 2);
        HydrologyPoint tangentStart = centerline.get(Math.max(0, selectedPoint - tangentReach));
        HydrologyPoint tangentEnd = centerline.get(Math.min(centerline.size() - 1, selectedPoint + tangentReach));
        int tangentX = tangentEnd.x() - tangentStart.x();
        int tangentZ = tangentEnd.z() - tangentStart.z();
        if (tangentX != 0 || tangentZ != 0) {
            selectedFlowX = tangentX;
            selectedFlowZ = tangentZ;
        }
        return new CenterlineProjection(
                selectedPoint,
                selectedX,
                selectedZ,
                StrictMath.sqrt(selectedDistanceSquared),
                selectedFlowX,
                selectedFlowZ
        );
    }

    private boolean withinLongitudinalBounds(
            List<HydrologyPoint> centerline,
            int x,
            int z,
            boolean clipStart,
            boolean clipEnd
    ) {
        if (centerline.size() < 2) {
            return true;
        }
        HydrologyPoint start = centerline.getFirst();
        HydrologyPoint afterStart = centerline.get(1);
        long startDot = (long) (x - start.x()) * (afterStart.x() - start.x())
                + (long) (z - start.z()) * (afterStart.z() - start.z());
        if (clipStart && startDot < 0L) {
            return false;
        }
        if (!clipEnd) {
            return true;
        }
        HydrologyPoint end = centerline.getLast();
        HydrologyPoint beforeEnd = centerline.get(centerline.size() - 2);
        long endDot = (long) (x - end.x()) * (end.x() - beforeEnd.x())
                + (long) (z - end.z()) * (end.z() - beforeEnd.z());
        return endDot <= 0L;
    }

    private boolean segmentsJoin(HydraulicSegment upstream, HydraulicSegment downstream) {
        HydrologyPoint upstreamEnd = upstream.centerline().getLast();
        HydrologyPoint downstreamStart = downstream.centerline().getFirst();
        return upstreamEnd.x() == downstreamStart.x()
                && upstreamEnd.y() == downstreamStart.y()
                && upstreamEnd.z() == downstreamStart.z();
    }

    private List<HydrologyPoint> continuousCenterline(HydraulicSegment segment) {
        List<HydrologyPoint> configured = segment.centerline();
        if (configured.size() == 1) {
            return configured;
        }
        ArrayList<HydrologyPoint> continuous = new ArrayList<>();
        for (int pairIndex = 0; pairIndex < configured.size() - 1; pairIndex++) {
            HydrologyPoint start = configured.get(pairIndex);
            HydrologyPoint end = configured.get(pairIndex + 1);
            int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.z() - start.z()));
            if (steps == 0) {
                if (continuous.isEmpty()) {
                    continuous.add(start);
                }
                continue;
            }
            int firstStep = continuous.isEmpty() ? 0 : 1;
            for (int step = firstStep; step <= steps; step++) {
                double progress = step / (double) steps;
                int x = (int) StrictMath.round(start.x() + (end.x() - start.x()) * progress);
                int z = (int) StrictMath.round(start.z() + (end.z() - start.z()) * progress);
                int y = segment.fallingFluid() && !continuous.isEmpty()
                        ? segment.downstreamHeadY()
                        : (int) StrictMath.round(start.y() + (end.y() - start.y()) * progress);
                HydrologyPoint previous = continuous.isEmpty() ? null : continuous.getLast();
                if (previous == null || previous.x() != x || previous.z() != z) {
                    continuous.add(new HydrologyPoint(x, y, z));
                }
            }
        }
        return List.copyOf(continuous);
    }

    private LayerShape shape(
            RiverCourse course,
            HydraulicSegment segment,
            HydrologyPoint point,
            int bed,
            int fluidHead,
            boolean falling,
            boolean receiving
    ) {
        HydrologyFeatureType type = segment.type();
        int channelRadius = Math.max(1, segment.width() / 2);
        double shoreWidth = 0D;
        double gradingWidth = 0D;
        int ceiling = fluidHead;
        boolean ellipsoid = false;
        boolean archedChannel = false;
        boolean roundedSurfaceBed = false;
        boolean organicBoundary = type.isSurface() || type.isUnderground() || type.isDeepFluid() || falling || receiving;
        if (course.type() == RiverCourseType.SURFACE && type.isSurface()) {
            roundedSurfaceBed = !falling;
            shoreWidth = settings.surface().shoreWidth();
            gradingWidth = geometrySampler.sample(
                    HydrologyGeometrySampler.Field.SURFACE_BLEND_WIDTH,
                    course.profileKey(),
                    point.x(),
                    point.z(),
                    segment.id(),
                    settings.surface().minimumTerrainBlendWidth(),
                    settings.surface().maximumTerrainBlendWidth()
            );
            if (type == HydrologyFeatureType.WATERFALL) {
                shoreWidth = Math.min(2.5D, shoreWidth);
                gradingWidth = Math.min(gradingWidth, Math.max(8D, channelRadius * 4D));
            }
        }
        if (type == HydrologyFeatureType.RIDGE_BORE
                || course.type() == RiverCourseType.SURFACE
                && type == HydrologyFeatureType.UNDERGROUND_DROP) {
            ceiling = fluidHead + settings.surface().ridgeTunnelHeadroom();
            archedChannel = !falling;
            organicBoundary = true;
        } else if (type == HydrologyFeatureType.UNDERGROUND_POOL
                || type == HydrologyFeatureType.UNDERGROUND_DROP
                || type == HydrologyFeatureType.SINKHOLE) {
            ceiling = fluidHead + geometrySampler.sample(
                    HydrologyGeometrySampler.Field.UNDERGROUND_HEADROOM,
                    course.profileKey(),
                    point.x(),
                    point.z(),
                    course.id(),
                    settings.underground().minimumHeadroom(),
                    settings.underground().maximumHeadroom()
            );
            archedChannel = !falling;
        } else if (type == HydrologyFeatureType.COASTAL_GROTTO) {
            HydrologyPlannerSettings.Grotto grotto = settings.outlets().coastalGrotto();
            channelRadius = grotto.horizontalRadius();
            bed = fluidHead - grotto.verticalRadius();
            ceiling = fluidHead + grotto.headroom();
            ellipsoid = true;
            organicBoundary = true;
        } else if (type == HydrologyFeatureType.INLAND_GROTTO) {
            HydrologyPlannerSettings.Grotto grotto = settings.outlets().inlandGrotto();
            channelRadius = grotto.horizontalRadius();
            bed = fluidHead - grotto.verticalRadius();
            ceiling = fluidHead + grotto.headroom();
            ellipsoid = true;
            organicBoundary = true;
        } else if (type.isDeepFluid()) {
            HydrologyPlannerSettings.DeepFluid deepFluid = deepFluids.get(course.profileKey());
            if (deepFluid == null) {
                throw new IllegalStateException("Missing deep-fluid planner settings for " + course.profileKey() + ".");
            }
            if (type == HydrologyFeatureType.DEEP_POOL) {
                channelRadius = Math.max(1, segment.width() / 2);
                bed = fluidHead - Math.max(segment.depth(), deepFluid.minimumVerticalRadius());
                ellipsoid = true;
            } else {
                archedChannel = !falling;
            }
            ceiling = fluidHead + deepFluid.headroom();
            organicBoundary = true;
        }
        if (receiving) {
            HydrologyPlannerSettings.Drops drops = settings.geometry().drops();
            if (course.type() != RiverCourseType.SURFACE || !type.isSurface()) {
                channelRadius = Math.max(
                        channelRadius,
                        (int) StrictMath.ceil(drops.basinWidth(segment.width()) / 2D)
                );
            }
            bed = Math.min(bed, fluidHead - drops.basinDepth(segment.depth(), segment.drop()));
            if (course.type() == RiverCourseType.SURFACE && type.isSurface()) {
                gradingWidth = Math.max(
                        gradingWidth,
                        Math.min(settings.surface().maximumTerrainBlendWidth(), channelRadius * 2D)
                );
            }
        }
        if (type == HydrologyFeatureType.UNDERGROUND_POOL && segment.centerline().size() > 1) {
            int depthVariation = Math.min(
                    2,
                    Math.max(0, settings.underground().maximumDepth() - (fluidHead - bed))
            );
            bed -= organicVerticalVariation(
                    HydrologyHash.mix(segment.id(), ORGANIC_BED_VARIATION_SALT),
                    point.x(),
                    point.z(),
                    depthVariation
            );
        }
        if (falling) {
            bed = Math.min(bed, segment.downstreamHeadY() - 1);
            ceiling = Math.max(ceiling, segment.upstreamHeadY());
        }
        return new LayerShape(
                channelRadius,
                shoreWidth,
                gradingWidth,
                bed,
                fluidHead,
                ceiling,
                ellipsoid,
                archedChannel,
                roundedSurfaceBed,
                organicBoundary,
                falling
        );
    }

    private int organicVerticalVariation(
            long seed,
            int x,
            int z,
            int maximum
    ) {
        if (maximum == 0) {
            return 0;
        }
        double sampled = organicNoise(seed, x, z, 12);
        return (int) StrictMath.round(sampled * maximum);
    }

    private void rasterizePoint(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            RiverCourse course,
            HydraulicSegment segment,
            HydrologyPoint point,
            int pointIndex,
            LayerShape shape,
            int flowX,
            int flowZ,
            boolean source,
            boolean falling,
            boolean receiving,
            boolean oceanConnection,
            boolean validationOnly,
            SurfaceRasterIndex plannedSurface
    ) {
        RasterStencil stencil = rasterStencil(shape);
        HydrologyFeatureRef[] pointFeatures = new HydrologyFeatureRef[FEATURE_ROLE_COUNT];
        boolean underground = segment.type().isUnderground() || segment.type().isDeepFluid();
        for (int offsetIndex = 0; offsetIndex < stencil.size(); offsetIndex++) {
            int deltaX = stencil.deltaXs()[offsetIndex];
            int deltaZ = stencil.deltaZs()[offsetIndex];
            int x = point.x() + deltaX;
            int z = point.z() + deltaZ;
            double channelDistance = shapedDistance(
                    shape,
                    segment,
                    x,
                    z,
                    deltaX,
                    deltaZ,
                    flowX,
                    flowZ,
                    stencil.distances()[offsetIndex]
            );
            boolean channel = channelDistance <= shape.channelRadius() + 0.25D;
            if (underground && !channel) {
                continue;
            }
            boolean shore = !channel
                    && stencil.distances()[offsetIndex] <= shape.channelRadius() + shape.shoreWidth() + 0.25D;
            boolean grading = !channel
                    && !shore
                    && stencil.distances()[offsetIndex] <= shape.totalRadius() + 0.25D;
            double distance = grading ? stencil.distances()[offsetIndex] : channelDistance;
            HydrologyRoutingTerrainSampler.NaturalClassification classification = classifyNatural(x, z);
            boolean apronEligible = oceanApronEligible(segment, distance);
            if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                    && !apronEligible) {
                continue;
            }
            if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.LAND
                    && oceanConnection) {
                continue;
            }
            boolean exactSlope = classification != HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                    && !oceanConnection
                    && !channel
                    && segment.type() != HydrologyFeatureType.WATERFALL
                    && segment.type() != HydrologyFeatureType.CASCADE;
            HydrologyTerrainSample terrain = exactSlope
                    ? sampleTerrain(x, z)
                    : sampleTerrainBasis(x, z);
            if (terrain == null) {
                continue;
            }
            boolean naturalOcean = classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                    || terrain.ocean()
                    || naturallySubmergedSurfaceColumn(segment, terrain);
            if (oceanConnection) {
                if (naturalOcean) {
                    addOceanApron(
                            columns,
                            course,
                            segment,
                            point,
                            x,
                            z,
                            distance,
                            flowX,
                            flowZ,
                            terrain,
                            pointFeatures
                    );
                }
                continue;
            }
            if (naturalOcean) {
                addOceanApron(
                        columns,
                        course,
                        segment,
                        point,
                        x,
                        z,
                        distance,
                        flowX,
                        flowZ,
                        terrain,
                        pointFeatures
                );
                continue;
            }
            if (validationOnly && !underground) {
                feature(
                        course,
                        segment,
                        x,
                        shape.fluidHead(),
                        z,
                        flowX,
                        flowZ,
                        source && deltaX == 0 && deltaZ == 0,
                        channel,
                        shore,
                        grading,
                        channel && falling,
                        channel && receiving,
                        pointFeatures
                );
                continue;
            }
            HydrologyColumnLayer layer = regularLayer(
                    course,
                    segment,
                    point,
                    shape,
                    terrain,
                    distance,
                    deltaX,
                    deltaZ,
                    flowX,
                    flowZ,
                    source,
                    channel,
                    shore,
                    grading,
                    falling,
                    receiving,
                    pointFeatures
            );
            layer = terrainContainedCaveLayer(course, segment, terrain, layer, x, z, plannedSurface);
            if (layer == null) {
                continue;
            }
            addLayer(columns, x, z, terrain, layer);
        }
    }

    private HydrologyColumnLayer terrainContainedCaveLayer(
            RiverCourse course,
            HydraulicSegment segment,
            HydrologyTerrainSample terrain,
            HydrologyColumnLayer layer,
            int x,
            int z,
            SurfaceRasterIndex plannedSurface
    ) {
        if (!terrainRoofedSurfaceTransition(course, segment)) {
            return layer;
        }
        int plannedTerrainHeight = plannedSurface.resolve(
                x,
                z,
                terrain.naturalHeight()
        );
        boolean surfaceChannel = plannedSurface.ownsSurfaceChannelAt(
                x,
                z,
                course.id()
        );
        int maximumCeiling = surfaceChannel ? Integer.MAX_VALUE : plannedTerrainHeight - 1;
        for (int[] offset : HORIZONTAL_NEIGHBORS) {
            int neighborX = x + offset[0];
            int neighborZ = z + offset[1];
            HydrologyTerrainSample neighborTerrain = sampleTerrainBasis(neighborX, neighborZ);
            if (neighborTerrain == null) {
                continue;
            }
            if (plannedSurface.ownsSurfaceChannelAt(neighborX, neighborZ, course.id())) {
                continue;
            }
            int neighborSurface = plannedSurface.resolve(
                    neighborX,
                    neighborZ,
                    neighborTerrain.naturalHeight()
            );
            maximumCeiling = Math.min(maximumCeiling, neighborSurface - 1);
        }
        if (maximumCeiling == Integer.MAX_VALUE || layer.ceilingY() <= maximumCeiling) {
            return layer;
        }
        if (maximumCeiling < layer.fluidHeadY()) {
            return null;
        }
        return withCeiling(layer, maximumCeiling);
    }

    private HydrologyColumnLayer withCeiling(HydrologyColumnLayer layer, int ceiling) {
        return new HydrologyColumnLayer(
                layer.feature(),
                layer.bedY(),
                layer.fluidHeadY(),
                Math.min(layer.ceilingY(), ceiling),
                layer.channel(),
                layer.shore(),
                layer.grading(),
                layer.connectedFluid(),
                layer.fallingFluid(),
                layer.receivingPool(),
                layer.terrainOwned(),
                layer.fluidOwned(),
                layer.oceanApron(),
                layer.profileKey(),
                layer.surfaceBiomeKey(),
                layer.mouthBiomeKey(),
                layer.shoreBiomeKey(),
                layer.dryBiomeKey(),
                layer.floodedCaveBiomeKey()
        );
    }

    private HydrologyColumnLayer regularLayer(
            RiverCourse course,
            HydraulicSegment segment,
            HydrologyPoint point,
            LayerShape shape,
            HydrologyTerrainSample terrain,
            double distance,
            int deltaX,
            int deltaZ,
            int flowX,
            int flowZ,
            boolean source,
            boolean channel,
            boolean shore,
            boolean grading,
            boolean falling,
            boolean receiving,
            HydrologyFeatureRef[] pointFeatures
    ) {
        HydrologyFeatureRef feature = feature(
                course,
                segment,
                point.x() + deltaX,
                shape.fluidHead(),
                point.z() + deltaZ,
                flowX,
                flowZ,
                source && deltaX == 0 && deltaZ == 0,
                channel,
                shore,
                grading,
                channel && falling,
                channel && receiving,
                pointFeatures
        );
        return regularLayer(
                course,
                segment,
                shape,
                terrain,
                distance,
                point.x() + deltaX,
                point.z() + deltaZ,
                channel,
                shore,
                grading,
                falling,
                receiving,
                feature
        );
    }

    private HydrologyColumnLayer regularLayer(
            RiverCourse course,
            HydraulicSegment segment,
            LayerShape shape,
            HydrologyTerrainSample terrain,
            double distance,
            int worldX,
            int worldZ,
            boolean channel,
            boolean shore,
            boolean grading,
            boolean falling,
            boolean receiving,
            HydrologyFeatureRef feature
    ) {
        HydrologyPlannerSettings.ChannelShape channelShape = channelShape(segment.type());
        double ellipsoidScale = channel && (shape.ellipsoid() || shape.archedChannel())
                ? ellipsoidScale(shape.channelRadius(), distance, channelShape.bedRoundness())
                : 0D;
        int bed = resolvedBed(
                segment,
                shape,
                channelShape,
                terrain,
                distance,
                worldX,
                worldZ,
                channel,
                ellipsoidScale
        );
        int fluidHead = channel ? shape.fluidHead() : bed;
        int ceiling = channel ? localCeiling(shape, ellipsoidScale) : fluidHead;
        if (channel && terrainRoofedSurfaceTransition(course, segment)) {
            ceiling = Math.max(fluidHead, Math.min(ceiling, terrain.naturalHeight() - 1));
        }
        boolean terrainOwned = !falling;
        return new HydrologyColumnLayer(
                feature,
                bed,
                fluidHead,
                ceiling,
                channel,
                terrainOwned && shore,
                terrainOwned && (grading || shore),
                channel,
                channel && falling,
                channel && receiving,
                terrainOwned,
                channel,
                false,
                course.profileKey(),
                terrain.surfaceBiomeKey(),
                terrain.mouthBiomeKey(),
                terrain.shoreBiomeKey(),
                terrain.dryBiomeKey(),
                terrain.floodedCaveBiomeKey()
        );
    }

    private SurfaceCell surfaceCell(SurfaceProjection projection, int x, int z) {
        LayerShape shape = projection.shape();
        double channelDistance = surfaceDistance(projection, x, z);
        boolean channel = channelDistance <= shape.channelRadius() + 0.25D;
        boolean shore = !channel
                && channelDistance <= shape.channelRadius() + shape.shoreWidth() + 0.25D;
        boolean grading = !channel && !shore && projection.distance() <= shape.totalRadius() + 0.25D;
        if (!channel && !shore && !grading) {
            return null;
        }
        double distance = grading ? projection.distance() : channelDistance;
        HydraulicSegment segment = projection.segment();
        HydrologyRoutingTerrainSampler.NaturalClassification classification = classifyNatural(x, z);
        boolean apronEligible = oceanApronEligible(segment, distance);
        if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN && !apronEligible) {
            return null;
        }
        boolean surfaceCourse = projection.sweep().course().type() == RiverCourseType.SURFACE;
        if (!surfaceCourse
                && classification == HydrologyRoutingTerrainSampler.NaturalClassification.LAND
                && projection.oceanConnection()) {
            return null;
        }
        boolean exactSlope = classification != HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                && (!projection.oceanConnection() || surfaceCourse)
                && !channel
                && segment.type() != HydrologyFeatureType.WATERFALL
                && segment.type() != HydrologyFeatureType.CASCADE;
        HydrologyTerrainSample terrain = exactSlope ? sampleTerrain(x, z) : sampleTerrainBasis(x, z);
        if (terrain == null) {
            return null;
        }
        boolean naturalOcean = classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN
                || terrain.ocean()
                || naturallySubmergedSurfaceColumn(segment, terrain);
        if (naturalOcean) {
            return apronEligible ? new SurfaceCell(terrain, null, true) : null;
        }
        if (channel && !projection.falling() && !surfaceChannelTerrainSupported(shape, terrain, x, z)) {
            return null;
        }
        if (!surfaceCourse && projection.oceanConnection()) {
            return null;
        }
        int deltaX = x - projection.anchor().x();
        int deltaZ = z - projection.anchor().z();
        HydrologyFeatureRef[] pointFeatures = new HydrologyFeatureRef[FEATURE_ROLE_COUNT];
        HydrologyColumnLayer layer = regularLayer(
                projection.sweep().course(),
                segment,
                projection.anchor(),
                shape,
                terrain,
                distance,
                deltaX,
                deltaZ,
                projection.flowX(),
                projection.flowZ(),
                projection.source(),
                channel,
                shore,
                grading,
                projection.falling(),
                projection.receiving(),
                pointFeatures
        );
        return new SurfaceCell(terrain, layer, false);
    }

    private boolean surfaceChannelTerrainSupported(
            LayerShape shape,
            HydrologyTerrainSample terrain,
            int x,
            int z
    ) {
        if (terrain.naturalHeight() <= shape.fluidHead()) {
            return false;
        }
        for (int[] offset : HORIZONTAL_NEIGHBORS) {
            HydrologyTerrainSample neighbor = sampleTerrainBasis(x + offset[0], z + offset[1]);
            if (neighbor == null) {
                return false;
            }
            if (shape.fluidHead() <= settings.seaLevel()
                    && (neighbor.ocean() || neighbor.naturalHeight() <= settings.seaLevel())) {
                continue;
            }
            if (neighbor.naturalHeight() <= shape.fluidHead()) {
                return false;
            }
        }
        return true;
    }

    private double surfaceDistance(SurfaceProjection projection, int x, int z) {
        if (projection.roundedCap()) {
            return projection.distance();
        }
        return shapedDistance(
                projection.shape(),
                projection.segment(),
                x,
                z,
                x - projection.x(),
                z - projection.z(),
                projection.flowX(),
                projection.flowZ(),
                projection.distance()
        );
    }

    private RasterStencil rasterStencil(LayerShape shape) {
        RasterStencilKey key = new RasterStencilKey(
                shape.channelRadius(),
                shape.shoreWidth(),
                shape.gradingWidth()
        );
        RasterStencil cached = rasterStencils.get(key);
        if (cached != null) {
            return cached;
        }
        RasterStencil stencil = buildRasterStencil(key);
        rasterStencils.put(key, stencil);
        return stencil;
    }

    private RasterStencil buildRasterStencil(RasterStencilKey key) {
        double totalRadius = key.channelRadius() + key.shoreWidth() + key.gradingWidth();
        int blockRadius = (int) StrictMath.ceil(totalRadius);
        RasterStencilBuilder builder = new RasterStencilBuilder();
        for (int deltaZ = -blockRadius; deltaZ <= blockRadius; deltaZ++) {
            for (int deltaX = -blockRadius; deltaX <= blockRadius; deltaX++) {
                double distance = StrictMath.hypot(deltaX, deltaZ);
                if (distance > totalRadius + 0.25D) {
                    continue;
                }
                boolean channel = distance <= key.channelRadius() + 0.25D;
                boolean shore = !channel && distance <= key.channelRadius() + key.shoreWidth() + 0.25D;
                boolean grading = !channel && !shore && distance <= totalRadius + 0.25D;
                if (!channel && !shore && !grading) {
                    continue;
                }
                byte role = channel ? CHANNEL_ROLE : shore ? SHORE_ROLE : GRADING_ROLE;
                builder.add(deltaX, deltaZ, distance, role);
            }
        }
        return builder.build();
    }

    private void addOceanApron(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            RiverCourse course,
            HydraulicSegment segment,
            HydrologyPoint point,
            int x,
            int z,
            double distance,
            int flowX,
            int flowZ,
            HydrologyTerrainSample terrain,
            HydrologyFeatureRef[] pointFeatures
    ) {
        if (segment.type() != HydrologyFeatureType.MOUTH
                && segment.type() != HydrologyFeatureType.COASTAL_GROTTO) {
            return;
        }
        if (distance > settings.outlets().maximumOceanApron() + 0.25D) {
            return;
        }
        int fluidHead = Math.min(settings.seaLevel(), point.y());
        HydrologyFeatureRef feature = feature(
                course,
                segment,
                x,
                fluidHead,
                z,
                flowX,
                flowZ,
                false,
                true,
                false,
                false,
                false,
                false,
                pointFeatures
        );
        HydrologyColumnLayer layer = new HydrologyColumnLayer(
                feature,
                fluidHead,
                fluidHead,
                segment.type() == HydrologyFeatureType.COASTAL_GROTTO
                        ? fluidHead + settings.outlets().coastalGrotto().headroom()
                        : fluidHead,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                course.profileKey(),
                terrain.surfaceBiomeKey(),
                terrain.mouthBiomeKey(),
                terrain.shoreBiomeKey(),
                terrain.dryBiomeKey(),
                terrain.floodedCaveBiomeKey()
        );
        addLayer(columns, x, z, terrain, layer);
    }

    private int gradedBed(
            LayerShape shape,
            HydrologyTerrainSample terrain,
            double distance
    ) {
        double transitionWidth = shape.shoreWidth() + shape.gradingWidth();
        if (transitionWidth <= 0D) {
            return terrain.naturalHeight();
        }
        double bankDistance = Math.max(0D, distance - shape.channelRadius());
        int targetBed = Math.addExact(shape.fluidHead(), 1);
        double progress = smoothStep(Math.min(1D, bankDistance / transitionWidth));
        int blendedBed = (int) StrictMath.round(
                targetBed + (terrain.naturalHeight() - targetBed) * progress
        );
        return Math.min(terrain.naturalHeight(), blendedBed);
    }

    private int resolvedBed(
            HydraulicSegment segment,
            LayerShape shape,
            HydrologyPlannerSettings.ChannelShape channelShape,
            HydrologyTerrainSample terrain,
            double distance,
            int worldX,
            int worldZ,
            boolean channel,
            double ellipsoidScale
    ) {
        int resolved;
        if (shape.ellipsoid() || shape.archedChannel()) {
            resolved = channel
                    ? localBed(segment, shape, channelShape, ellipsoidScale, worldX, worldZ)
                    : terrain.naturalHeight();
        } else if (!shape.roundedSurfaceBed()) {
            resolved = channel ? shape.bed() : gradedBed(shape, terrain, distance);
        } else if (channel) {
            double normalized = Math.min(1D, distance / Math.max(1D, shape.channelRadius()));
            double shoulder = Math.max(
                    0D,
                    (normalized - SURFACE_THALWEG_RATIO) / (1D - SURFACE_THALWEG_RATIO)
            );
            int maximumDepth = Math.max(1, shape.fluidHead() - shape.bed());
            double roughness = signedOrganicNoise(
                    HydrologyHash.mix(segment.id(), ORGANIC_BED_VARIATION_SALT),
                    worldX,
                    worldZ,
                    channelShape.roughnessWavelength()
            ) * channelShape.bedRoughness();
            double depthScale = Math.max(0.55D, 1D + roughness);
            int localDepth = 1 + (int) StrictMath.floor(
                    (maximumDepth - 1D)
                            * (1D - StrictMath.pow(shoulder, channelShape.bedRoundness()))
                            * depthScale
                            + 1.0E-9D
            );
            localDepth = Math.min(maximumDepth, localDepth);
            if (segment.type().isDrop()) {
                localDepth = Math.min(
                        maximumDepth,
                        Math.max(localDepth, settings.geometry().drops().stepLimit(segment.type()) + 1)
                );
            }
            resolved = shape.fluidHead() - localDepth;
        } else {
            resolved = gradedBed(shape, terrain, distance);
        }
        return Math.min(resolved, terrain.naturalHeight());
    }

    private int localBed(
            HydraulicSegment segment,
            LayerShape shape,
            HydrologyPlannerSettings.ChannelShape channelShape,
            double scale,
            int worldX,
            int worldZ
    ) {
        if (!shape.ellipsoid() && !shape.archedChannel()) {
            return shape.bed();
        }
        int lowerExtent = shape.fluidHead() - shape.bed();
        double roughness = signedOrganicNoise(
                HydrologyHash.mix(segment.id(), ORGANIC_BED_VARIATION_SALT),
                worldX,
                worldZ,
                channelShape.roughnessWavelength()
        ) * channelShape.bedRoughness();
        int localExtent = (int) StrictMath.ceil(lowerExtent * scale * Math.max(0.55D, 1D + roughness));
        localExtent = Math.min(lowerExtent, localExtent);
        if (shape.archedChannel()) {
            localExtent = Math.max(1, localExtent);
        }
        if (segment.type().isDrop()) {
            localExtent = Math.max(localExtent, settings.geometry().drops().stepLimit(segment.type()) + 1);
        }
        return shape.fluidHead() - localExtent;
    }

    private int localCeiling(LayerShape shape, double scale) {
        if (!shape.ellipsoid() && !shape.archedChannel()) {
            return shape.ceiling();
        }
        int upperExtent = shape.ceiling() - shape.fluidHead();
        int localExtent = (int) StrictMath.floor(upperExtent * scale);
        if (shape.archedChannel()) {
            localExtent = Math.max(1, localExtent);
        }
        return shape.fluidHead() + localExtent;
    }

    private boolean terrainRoofedSurfaceTransition(RiverCourse course, HydraulicSegment segment) {
        return course.type() == RiverCourseType.SURFACE
                && (segment.type() == HydrologyFeatureType.RIDGE_BORE
                || segment.type() == HydrologyFeatureType.UNDERGROUND_POOL
                || segment.type() == HydrologyFeatureType.UNDERGROUND_DROP);
    }

    private double ellipsoidScale(int radius, double distance, double roundness) {
        double normalized = Math.min(1D, distance / Math.max(1D, radius));
        return StrictMath.pow(
                Math.max(0D, 1D - StrictMath.pow(normalized, roundness)),
                1D / roundness
        );
    }

    private double shapedDistance(
            LayerShape shape,
            HydraulicSegment segment,
            int worldX,
            int worldZ,
            double deltaX,
            double deltaZ,
            int flowX,
            int flowZ,
            double rawDistance
    ) {
        if (segment.type() == HydrologyFeatureType.DEEP_POOL) {
            return deepPoolDistance(shape, segment, worldX, worldZ, deltaX, deltaZ);
        }
        if (segment.type().isSurface() && !shape.fallingThroat()) {
            return surfaceChannelDistance(shape, segment, worldX, worldZ, deltaX, deltaZ, flowX, flowZ);
        }
        if (rawDistance == 0D) {
            return 0D;
        }
        double distance = rawDistance;
        if (shape.fallingThroat() && (flowX != 0 || flowZ != 0)) {
            double flowLength = StrictMath.hypot(flowX, flowZ);
            double along = StrictMath.abs(deltaX * flowX + deltaZ * flowZ) / flowLength;
            double across = StrictMath.abs(deltaX * -flowZ + deltaZ * flowX) / flowLength;
            if (along > 0.75D) {
                return shape.totalRadius() + 1D;
            }
            distance = across;
        }
        if (!shape.organicBoundary()) {
            return distance;
        }
        if (segment.type() == HydrologyFeatureType.COASTAL_GROTTO
                || segment.type() == HydrologyFeatureType.INLAND_GROTTO) {
            double orientation = HydrologyHash.unit(HydrologyHash.mix(
                    segment.courseId(),
                    ORGANIC_SHAPE_FIRST_PHASE_SALT
            )) * StrictMath.PI * 2D;
            double cosine = StrictMath.cos(orientation);
            double sine = StrictMath.sin(orientation);
            double rotatedX = deltaX * cosine + deltaZ * sine;
            double rotatedZ = -deltaX * sine + deltaZ * cosine;
            double aspect = 0.62D + HydrologyHash.unit(HydrologyHash.mix(
                    segment.courseId(),
                    ORGANIC_SHAPE_SECOND_PHASE_SALT
            )) * 0.2D;
            distance = StrictMath.hypot(rotatedX, rotatedZ / aspect);
        }
        double angle = StrictMath.atan2(deltaZ, deltaX);
        double firstPhase = HydrologyHash.unit(HydrologyHash.mix(
                segment.courseId(),
                ORGANIC_SHAPE_FIRST_PHASE_SALT
        )) * StrictMath.PI * 2D;
        double secondPhase = HydrologyHash.unit(HydrologyHash.mix(
                segment.courseId(),
                ORGANIC_SHAPE_SECOND_PHASE_SALT
        )) * StrictMath.PI * 2D;
        double firstLobe = 0.5D + 0.5D * StrictMath.sin(angle * 3D + firstPhase);
        double secondLobe = 0.5D + 0.5D * StrictMath.sin(angle * 5D + secondPhase);
        HydrologyPlannerSettings.ChannelShape channelShape = channelShape(segment.type());
        double coherent = signedOrganicNoise(
                segment.courseId(),
                worldX,
                worldZ,
                channelShape.roughnessWavelength()
        );
        double detail = signedOrganicNoise(
                HydrologyHash.mix(segment.courseId(), ORGANIC_SHAPE_SECOND_PHASE_SALT),
                worldX,
                worldZ,
                Math.max(3, channelShape.roughnessWavelength() / 2)
        );
        double radialScale = 0.86D
                + (firstLobe - 0.5D) * 0.08D
                + (secondLobe - 0.5D) * 0.06D
                + coherent * channelShape.wallRoughness() * 0.7D
                + detail * channelShape.wallRoughness() * 0.3D;
        radialScale = Math.max(0.58D, Math.min(1.18D, radialScale));
        return distance / radialScale;
    }

    private double surfaceChannelDistance(
            LayerShape shape,
            HydraulicSegment segment,
            int worldX,
            int worldZ,
            double deltaX,
            double deltaZ,
            int flowX,
            int flowZ
    ) {
        double flowLength = StrictMath.hypot(flowX, flowZ);
        if (flowLength == 0D) {
            return deepPoolDistance(shape, segment, worldX, worldZ, deltaX, deltaZ);
        }
        double signedCross = (deltaX * -flowZ + deltaZ * flowX) / flowLength;
        HydrologyPlannerSettings.ChannelShape channelShape = settings.geometry().surface();
        double thalweg = signedOrganicNoise(
                HydrologyHash.mix(segment.courseId(), ORGANIC_BED_VARIATION_SALT),
                worldX,
                worldZ,
                channelShape.roughnessWavelength()
        ) * shape.channelRadius() * channelShape.wallRoughness();
        long bankSeed = HydrologyHash.mix(segment.courseId(), ORGANIC_SHAPE_SECOND_PHASE_SALT);
        double bankNoise = signedOrganicNoise(
                bankSeed,
                worldX,
                worldZ,
                channelShape.roughnessWavelength()
        );
        double sideBias = signedCross < thalweg
                ? HydrologyHash.unit(HydrologyHash.mix(bankSeed, 1L))
                : HydrologyHash.unit(HydrologyHash.mix(bankSeed, 2L));
        double widthScale = 0.86D
                + bankNoise * channelShape.wallRoughness()
                + (sideBias - 0.5D) * 0.08D;
        widthScale = Math.max(1D, Math.min(1.18D, widthScale));
        double organicDistance = StrictMath.abs(signedCross - thalweg) / widthScale;
        return Math.min(StrictMath.abs(signedCross), organicDistance);
    }

    private HydrologyPlannerSettings.ChannelShape channelShape(HydrologyFeatureType type) {
        if (type.isGrotto() || type == HydrologyFeatureType.DEEP_POOL
                || type == HydrologyFeatureType.RIDGE_BORE) {
            return settings.geometry().grottos();
        }
        if (type.isUnderground() || type == HydrologyFeatureType.DEEP_CHANNEL) {
            return settings.geometry().underground();
        }
        return settings.geometry().surface();
    }

    private double signedOrganicNoise(long seed, int x, int z, int scale) {
        return organicNoise(seed, x, z, scale) * 2D - 1D;
    }

    private double deepPoolDistance(
            LayerShape shape,
            HydraulicSegment segment,
            int worldX,
            int worldZ,
            double deltaX,
            double deltaZ
    ) {
        double radius = Math.max(1D, shape.channelRadius());
        long seed = HydrologyHash.mix(segment.courseId(), ORGANIC_SHAPE_FIRST_PHASE_SALT);
        double orientation = HydrologyHash.unit(seed) * StrictMath.PI * 2D;
        double cosine = StrictMath.cos(orientation);
        double sine = StrictMath.sin(orientation);
        double rotatedX = deltaX * cosine + deltaZ * sine;
        double rotatedZ = -deltaX * sine + deltaZ * cosine;
        double angle = StrictMath.atan2(rotatedZ, rotatedX);
        double secondPhase = HydrologyHash.unit(HydrologyHash.mix(seed, 2L)) * StrictMath.PI * 2D;
        double thirdPhase = HydrologyHash.unit(HydrologyHash.mix(seed, 3L)) * StrictMath.PI * 2D;
        double fifthPhase = HydrologyHash.unit(HydrologyHash.mix(seed, 5L)) * StrictMath.PI * 2D;
        double radialScale = 0.84D
                + StrictMath.sin(angle * 2D + secondPhase) * 0.07D
                + StrictMath.sin(angle * 3D + thirdPhase) * 0.05D
                + StrictMath.sin(angle * 5D + fifthPhase) * 0.035D;
        radialScale = Math.max(0.68D, Math.min(1D, radialScale));
        double normalizedDistance = StrictMath.hypot(
                rotatedX / (radius * 0.92D),
                rotatedZ / (radius * 0.7D)
        );
        return normalizedDistance * radius / radialScale;
    }

    private double organicNoise(long seed, int x, int z, int scale) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double localX = Math.floorMod(x, scale) / (double) scale;
        double localZ = Math.floorMod(z, scale) / (double) scale;
        double smoothX = localX * localX * (3D - 2D * localX);
        double smoothZ = localZ * localZ * (3D - 2D * localZ);
        double top = interpolate(
                organicCorner(seed, cellX, cellZ),
                organicCorner(seed, cellX + 1, cellZ),
                smoothX
        );
        double bottom = interpolate(
                organicCorner(seed, cellX, cellZ + 1),
                organicCorner(seed, cellX + 1, cellZ + 1),
                smoothX
        );
        return interpolate(top, bottom, smoothZ);
    }

    private double organicCorner(long seed, int cellX, int cellZ) {
        return HydrologyHash.unit(HydrologyHash.mix(seed, cellX, cellZ));
    }

    private double interpolate(double first, double second, double progress) {
        return first + (second - first) * progress;
    }

    private void addLayer(
            Long2ObjectLinkedOpenHashMap<MutableColumn> columns,
            int x,
            int z,
            HydrologyTerrainSample terrain,
            HydrologyColumnLayer layer
    ) {
        long packed = RiverFootprint.pack(x, z);
        MutableColumn column = columns.get(packed);
        if (column == null) {
            column = new MutableColumn(x, z, terrain, settings.seaLevel());
            columns.put(packed, column);
        }
        column.add(layer);
    }

    private HydrologyTerrainSample sampleTerrain(int x, int z) {
        long packed = RiverFootprint.pack(x, z);
        HydrologyTerrainSample cached = terrainSamples.get(packed);
        if (cached != null) {
            return cached;
        }
        HydrologyTerrainSample sampled = sampler.sample(x, z);
        if (sampled != null) {
            terrainSamples.put(packed, sampled);
            if (naturalSampler == null) {
                terrainBases.put(packed, sampled);
            }
        }
        return sampled;
    }

    private HydrologyTerrainSample sampleTerrainBasis(int x, int z) {
        if (naturalSampler == null) {
            return sampleTerrain(x, z);
        }
        long packed = RiverFootprint.pack(x, z);
        HydrologyTerrainSample cached = terrainBases.get(packed);
        if (cached != null) {
            return cached;
        }
        HydrologyTerrainSample sampled = naturalSampler.sampleBasis(x, z);
        if (sampled != null) {
            terrainBases.put(packed, sampled);
        }
        return sampled;
    }

    private HydrologyRoutingTerrainSampler.NaturalClassification classifyNatural(int x, int z) {
        if (naturalSampler == null) {
            return HydrologyRoutingTerrainSampler.NaturalClassification.UNAVAILABLE;
        }
        long packed = RiverFootprint.pack(x, z);
        HydrologyRoutingTerrainSampler.NaturalClassification cached = naturalClassifications.get(packed);
        if (cached != null) {
            return cached;
        }
        HydrologyRoutingTerrainSampler.NaturalClassification sampled = Objects.requireNonNull(
                naturalSampler.classifyNatural(x, z),
                "Hydrology natural terrain classifier returned null at " + x + "," + z
        );
        naturalClassifications.put(packed, sampled);
        return sampled;
    }

    private boolean naturallySubmergedSurfaceColumn(
            HydraulicSegment segment,
            HydrologyTerrainSample terrain
    ) {
        return segment.type().isSurface() && terrain.naturalHeight() <= settings.seaLevel();
    }

    private boolean oceanApronEligible(HydraulicSegment segment, double distance) {
        return (segment.type() == HydrologyFeatureType.MOUTH
                || segment.type() == HydrologyFeatureType.COASTAL_GROTTO)
                && distance <= settings.outlets().maximumOceanApron() + 0.25D;
    }

    private HydrologyFeatureRef feature(
            RiverCourse course,
            HydraulicSegment segment,
            int x,
            int y,
            int z,
            int flowX,
            int flowZ,
            boolean source,
            boolean channel,
            boolean shore,
            boolean grading,
            boolean falling,
            boolean receiving,
            HydrologyFeatureRef[] pointFeatures
    ) {
        int role = source ? 6 : falling ? 4 : receiving ? 5 : channel ? 1 : shore ? 2 : grading ? 3 : 0;
        HydrologyFeatureRef cached = pointFeatures[role];
        if (cached != null) {
            return cached;
        }
        int featureFlowX = Integer.compare(flowX, 0);
        int featureFlowZ = Integer.compare(flowZ, 0);
        FeatureKey key = new FeatureKey(
                course.id(),
                segment.id(),
                segment.type(),
                role,
                featureFlowX,
                featureFlowZ
        );
        HydrologyFeatureRef feature = features.get(key);
        if (feature == null) {
            feature = new HydrologyFeatureRef(
                    HydrologyHash.mix(
                            course.id(),
                            segment.id(),
                            segment.type().ordinal(),
                            role,
                            featureFlowX,
                            featureFlowZ
                    ),
                    segment.type(),
                    course.id(),
                    segment.id(),
                    x,
                    y,
                    z,
                    featureFlowX,
                    featureFlowZ,
                    source
            );
            features.put(key, feature);
        }
        pointFeatures[role] = feature;
        return feature;
    }

    private int flowDelta(List<HydrologyPoint> centerline, int pointIndex, boolean xAxis) {
        HydrologyPoint from;
        HydrologyPoint to;
        if (centerline.size() == 1) {
            return 0;
        }
        if (pointIndex < centerline.size() - 1) {
            from = centerline.get(pointIndex);
            to = centerline.get(pointIndex + 1);
        } else {
            from = centerline.get(pointIndex - 1);
            to = centerline.get(pointIndex);
        }
        return Integer.compare(xAxis ? to.x() : to.z(), xAxis ? from.x() : from.z());
    }

    private record LayerShape(
            int channelRadius,
            double shoreWidth,
            double gradingWidth,
            int bed,
            int fluidHead,
            int ceiling,
            boolean ellipsoid,
            boolean archedChannel,
            boolean roundedSurfaceBed,
            boolean organicBoundary,
            boolean fallingThroat
    ) {
        private double totalRadius() {
            return channelRadius + shoreWidth + gradingWidth;
        }
    }

    private record SurfaceSweepPoint(
            HydraulicSegment segment,
            HydrologyPoint point,
            LayerShape shape,
            boolean source,
            boolean receiving,
            boolean falling
    ) {
    }

    private record SurfaceSweep(
            RiverCourse course,
            List<SurfaceSweepPoint> points,
            boolean roundStart,
            boolean roundEnd,
            int singletonFlowX,
            int singletonFlowZ
    ) {
    }

    private record SurfaceSweepEdge(
            SurfaceSweep sweep,
            int edgeIndex
    ) {
    }

    private record SurfaceProjection(
            SurfaceSweep sweep,
            int edgeIndex,
            HydraulicSegment segment,
            HydrologyPoint anchor,
            LayerShape shape,
            boolean source,
            boolean receiving,
            boolean falling,
            boolean oceanConnection,
            double x,
            double z,
            double distance,
            int flowX,
            int flowZ,
            boolean roundedCap
    ) {
    }

    private enum SurfaceTerminal {
        NONE,
        OCEAN_MOUTH
    }

    private record CenterlineProjection(
            int pointIndex,
            double x,
            double z,
            double distance,
            int flowX,
            int flowZ
    ) {
    }

    private record SurfaceCell(
            HydrologyTerrainSample terrain,
            HydrologyColumnLayer layer,
            boolean naturalOcean
    ) {
    }

    private record ValidationCourseRaster(
            long courseId,
            List<HydrologyColumnSample> columns,
            List<SurfaceSweep> surfaceSweeps
    ) {
    }

    private record FeatureKey(
            long courseId,
            long segmentId,
            HydrologyFeatureType type,
            int role,
            int flowX,
            int flowZ
    ) {
    }

    private record RasterStencilKey(
            int channelRadius,
            double shoreWidth,
            double gradingWidth
    ) {
    }

    private record CourseRasterKey(
            long courseId,
            RiverCourseType courseType,
            String profileKey,
            List<HydraulicSegment> segments
    ) {
    }

    private record RasterStencil(
            int[] deltaXs,
            int[] deltaZs,
            double[] distances,
            byte[] roles
    ) {
        private int size() {
            return deltaXs.length;
        }
    }

    private static final class RasterStencilBuilder {
        private int[] deltaXs;
        private int[] deltaZs;
        private double[] distances;
        private byte[] roles;
        private int size;

        private RasterStencilBuilder() {
            this.deltaXs = new int[64];
            this.deltaZs = new int[64];
            this.distances = new double[64];
            this.roles = new byte[64];
        }

        private void add(int deltaX, int deltaZ, double distance, byte role) {
            if (size == deltaXs.length) {
                int expandedSize = Math.multiplyExact(size, 2);
                deltaXs = Arrays.copyOf(deltaXs, expandedSize);
                deltaZs = Arrays.copyOf(deltaZs, expandedSize);
                distances = Arrays.copyOf(distances, expandedSize);
                roles = Arrays.copyOf(roles, expandedSize);
            }
            deltaXs[size] = deltaX;
            deltaZs[size] = deltaZ;
            distances[size] = distance;
            roles[size] = role;
            size++;
        }

        private RasterStencil build() {
            return new RasterStencil(
                    Arrays.copyOf(deltaXs, size),
                    Arrays.copyOf(deltaZs, size),
                    Arrays.copyOf(distances, size),
                    Arrays.copyOf(roles, size)
            );
        }
    }

    record Sampling(
            HydrologyTerrainSampler sampler,
            HydrologyGeometrySampler geometrySampler,
            HydrologyNaturalTerrainSampler naturalSampler
    ) {
        Sampling {
            Objects.requireNonNull(sampler, "sampler");
            Objects.requireNonNull(geometrySampler, "geometrySampler");
        }
    }

    final class ValidationRaster {
        private final List<ValidationCourseRaster> courseRasters;
        private final Long2ObjectOpenHashMap<List<HydrologyColumnSample>> columnsByCourse;
        private final SurfaceRasterIndex surfaceRaster;
        private final RiverFootprint materializedSurface;
        private final int columnReferenceCount;
        private List<HydrologyColumnSample> mergedColumns;

        private ValidationRaster(
                List<ValidationCourseRaster> courseRasters,
                SurfaceRasterIndex surfaceRaster
        ) {
            this(courseRasters, surfaceRaster, null);
        }

        private ValidationRaster(
                List<ValidationCourseRaster> courseRasters,
                SurfaceRasterIndex surfaceRaster,
                RiverFootprint materializedSurface
        ) {
            this.courseRasters = List.copyOf(Objects.requireNonNull(courseRasters, "courseRasters"));
            this.surfaceRaster = Objects.requireNonNull(surfaceRaster, "surfaceRaster");
            this.materializedSurface = materializedSurface;
            this.columnsByCourse = new Long2ObjectOpenHashMap<>(courseRasters.size());
            int references = 0;
            for (ValidationCourseRaster raster : courseRasters) {
                if (columnsByCourse.put(raster.courseId(), raster.columns()) != null) {
                    throw new IllegalStateException("Duplicate hydrology validation course " + raster.courseId());
                }
                references += raster.columns().size();
            }
            this.columnReferenceCount = references;
        }

        List<HydrologyColumnSample> columnsForCourse(long courseId) {
            List<HydrologyColumnSample> columns = columnsByCourse.get(courseId);
            return columns == null ? List.of() : columns;
        }

        List<HydrologyColumnSample> columns() {
            List<HydrologyColumnSample> cached = mergedColumns;
            if (cached != null) {
                return cached;
            }
            Long2ObjectLinkedOpenHashMap<MutableColumn> merged = new Long2ObjectLinkedOpenHashMap<>();
            for (ValidationCourseRaster raster : courseRasters) {
                for (HydrologyColumnSample sample : raster.columns()) {
                    long packed = RiverFootprint.pack(sample.x(), sample.z());
                    MutableColumn column = merged.get(packed);
                    if (column == null) {
                        merged.put(packed, new MutableColumn(sample));
                    } else {
                        column.merge(sample);
                    }
                }
            }
            cached = buildValidationColumns(merged);
            mergedColumns = cached;
            return cached;
        }

        HydrologyCaveVoxelViewFactory.PlannedSurface plannedSurface() {
            if (materializedSurface == null) {
                return surfaceRaster;
            }
            return (int x, int z, int naturalHeight) -> materializedSurface.sample(x, z)
                    .map(HydrologyColumnSample::terrainHeight)
                    .orElse(naturalHeight);
        }

        HydrologyColumnSample surfaceColumnAt(int x, int z, int naturalHeight) {
            if (materializedSurface != null) {
                HydrologyColumnSample sample = materializedSurface.sample(x, z).orElse(null);
                if (sample != null && sample.naturalHeight() != naturalHeight) {
                    throw new IllegalStateException("Hydrology surface and cave rasters disagree on natural terrain at "
                            + x + "," + z + ".");
                }
                return sample;
            }
            return surfaceRaster.surfaceColumnAt(x, z, naturalHeight);
        }

        boolean ownsSurfaceChannelAt(int x, int z, int naturalHeight, long courseId) {
            if (materializedSurface != null) {
                HydrologyColumnSample sample = materializedSurface.sample(x, z).orElse(null);
                if (sample == null) {
                    return false;
                }
                if (sample.naturalHeight() != naturalHeight) {
                    throw new IllegalStateException("Hydrology surface and cave rasters disagree on natural terrain at "
                            + x + "," + z + ".");
                }
                return ownsSurfaceChannel(sample, courseId);
            }
            return surfaceRaster.ownsSurfaceChannelAt(x, z, courseId);
        }

        boolean ownsSurfaceChannelAt(int x, int z, long courseId) {
            if (materializedSurface == null) {
                return surfaceRaster.ownsSurfaceChannelAt(x, z, courseId);
            }
            HydrologyColumnSample sample = materializedSurface.sample(x, z).orElse(null);
            return sample != null && ownsSurfaceChannel(sample, courseId);
        }

        private boolean ownsSurfaceChannel(HydrologyColumnSample sample, long courseId) {
            for (HydrologyColumnLayer layer : sample.layers()) {
                if (layer.feature().courseId() == courseId
                        && layer.feature().type().isSurface()
                        && layer.channel()
                        && layer.terrainOwned()) {
                    return true;
                }
            }
            return false;
        }

        ValidationRaster withMaterializedSurface(RiverFootprint footprint) {
            return new ValidationRaster(courseRasters, surfaceRaster, Objects.requireNonNull(footprint));
        }

        int columnReferenceCount() {
            return columnReferenceCount;
        }
    }

    private final class SurfaceRasterIndex implements HydrologyCaveVoxelViewFactory.PlannedSurface {
        private final Long2ObjectOpenHashMap<ArrayList<SurfaceSweepEdge>> edgesByChunk;
        private final Long2ObjectOpenHashMap<HydrologyColumnSample> rawSurfaceColumns;
        private final Long2IntOpenHashMap validationNaturalHeights;
        private final Long2IntOpenHashMap resolvedHeights;

        private SurfaceRasterIndex(
                List<SurfaceSweep> surfaceSweeps,
                Long2ObjectOpenHashMap<HydrologyColumnSample> validationSamples
        ) {
            this.edgesByChunk = new Long2ObjectOpenHashMap<>();
            this.rawSurfaceColumns = new Long2ObjectOpenHashMap<>();
            this.validationNaturalHeights = new Long2IntOpenHashMap(validationSamples.size());
            for (HydrologyColumnSample sample : validationSamples.values()) {
                validationNaturalHeights.put(
                        RiverFootprint.pack(sample.x(), sample.z()),
                        sample.naturalHeight()
                );
            }
            this.resolvedHeights = new Long2IntOpenHashMap();
            for (SurfaceSweep sweep : surfaceSweeps) {
                index(sweep);
            }
        }

        @Override
        public int resolve(int x, int z, int naturalHeight) {
            long packed = RiverFootprint.pack(x, z);
            if (resolvedHeights.containsKey(packed)) {
                return resolvedHeights.get(packed);
            }

            MutableColumn column = null;
            int rasterNaturalHeight = naturalHeight;
            boolean rasterPresent = false;
            for (SurfaceProjection projection : projectionsAt(x, z)) {
                SurfaceCell cell = surfaceCell(projection, x, z);
                if (cell == null) {
                    continue;
                }
                if (!rasterPresent) {
                    rasterNaturalHeight = cell.terrain().naturalHeight();
                    rasterPresent = true;
                }
                if (cell.layer() == null) {
                    continue;
                }
                if (column == null) {
                    column = new MutableColumn(x, z, cell.terrain(), settings.seaLevel());
                }
                column.add(cell.layer());
            }

            int resolved;
            if (column != null) {
                resolved = containedSurfaceBankHeight(column.build());
            } else if (rasterPresent) {
                resolved = rasterNaturalHeight;
            } else if (validationNaturalHeights.containsKey(packed)) {
                resolved = validationNaturalHeights.get(packed);
            } else {
                return naturalHeight;
            }
            resolvedHeights.put(packed, resolved);
            return resolved;
        }

        private int containedSurfaceBankHeight(HydrologyColumnSample sample) {
            return containedSurfaceBankSample(sample).terrainHeight();
        }

        private HydrologyColumnSample containedSurfaceBankSample(HydrologyColumnSample sample) {
            if (sample.primarySurfaceFluidLayer().isPresent()) {
                return sample;
            }
            int minimumBankHeight = Integer.MIN_VALUE;
            for (int[] offset : HORIZONTAL_NEIGHBORS) {
                int neighborX = sample.x() + offset[0];
                int neighborZ = sample.z() + offset[1];
                HydrologyTerrainSample terrain = sampleTerrainBasis(neighborX, neighborZ);
                if (terrain == null) {
                    continue;
                }
                HydrologyColumnSample neighbor = rawSurfaceColumnAt(
                        neighborX,
                        neighborZ,
                        terrain.naturalHeight()
                );
                if (neighbor == null) {
                    continue;
                }
                HydrologyColumnLayer fluid = neighbor.primarySurfaceFluidLayer().orElse(null);
                if (fluid != null && !fluid.oceanApron() && !fluid.fallingFluid()) {
                    minimumBankHeight = Math.max(minimumBankHeight, Math.addExact(fluid.fluidHeadY(), 1));
                }
            }
            if (minimumBankHeight == Integer.MIN_VALUE) {
                return sample;
            }
            ArrayList<HydrologyColumnLayer> raised = new ArrayList<>(sample.layers().size());
            for (HydrologyColumnLayer layer : sample.layers()) {
                raised.add(raisedDrySurfaceLayer(layer, sample.naturalHeight(), minimumBankHeight));
            }
            return new HydrologyColumnSample(
                    sample.x(),
                    sample.z(),
                    sample.naturalHeight(),
                    sample.seaLevel(),
                    sample.ocean(),
                    sample.parentBiomeKey(),
                    raised
            );
        }

        private HydrologyColumnSample surfaceColumnAt(int x, int z, int naturalHeight) {
            HydrologyColumnSample sample = rawSurfaceColumnAt(x, z, naturalHeight);
            return sample == null ? null : containedSurfaceBankSample(sample);
        }

        private HydrologyColumnSample rawSurfaceColumnAt(int x, int z, int naturalHeight) {
            long packed = RiverFootprint.pack(x, z);
            HydrologyColumnSample cached = rawSurfaceColumns.get(packed);
            if (cached != null) {
                if (cached.naturalHeight() != naturalHeight) {
                    throw new IllegalStateException("Hydrology surface and cave rasters disagree on natural terrain at "
                            + x + "," + z + ".");
                }
                return cached;
            }
            MutableColumn column = null;
            for (SurfaceProjection projection : projectionsAt(x, z)) {
                SurfaceCell cell = surfaceCell(projection, x, z);
                if (cell == null || cell.layer() == null) {
                    continue;
                }
                if (column == null) {
                    column = new MutableColumn(x, z, cell.terrain(), settings.seaLevel());
                }
                column.add(cell.layer());
            }
            if (column == null) {
                return null;
            }
            HydrologyColumnSample sample = column.build();
            if (sample.naturalHeight() != naturalHeight) {
                throw new IllegalStateException("Hydrology surface and cave rasters disagree on natural terrain at "
                        + x + "," + z + ".");
            }
            rawSurfaceColumns.put(packed, sample);
            return sample;
        }

        private boolean ownsSurfaceChannelAt(int x, int z, long courseId) {
            for (SurfaceProjection projection : projectionsAt(x, z)) {
                SurfaceCell cell = surfaceCell(projection, x, z);
                HydrologyColumnLayer layer = cell == null ? null : cell.layer();
                if (layer != null
                        && layer.feature().courseId() == courseId
                        && layer.feature().type().isSurface()
                        && layer.channel()
                        && layer.terrainOwned()) {
                    return true;
                }
            }
            return false;
        }

        private List<SurfaceProjection> projectionsAt(int x, int z) {
            ArrayList<SurfaceSweepEdge> edges = edgesByChunk.get(RiverFootprint.pack(x >> 4, z >> 4));
            if (edges == null) {
                return List.of();
            }
            IdentityHashMap<SurfaceSweep, SurfaceProjection> selected = new IdentityHashMap<>();
            for (SurfaceSweepEdge edge : edges) {
                SurfaceProjection candidate = surfaceProjection(edge.sweep(), edge.edgeIndex(), x, z);
                if (candidate == null) {
                    continue;
                }
                SurfaceProjection current = selected.get(edge.sweep());
                if (current == null || prefersSurfaceProjection(candidate, current)) {
                    selected.put(edge.sweep(), candidate);
                }
            }
            ArrayList<SurfaceProjection> projections = new ArrayList<>(selected.values());
            projections.sort(Comparator
                    .comparingLong((SurfaceProjection projection) -> projection.sweep().course().id())
                    .thenComparingInt((SurfaceProjection projection) -> projection.sweep().points().getFirst().point().x())
                    .thenComparingInt((SurfaceProjection projection) -> projection.sweep().points().getFirst().point().z())
                    .thenComparingInt(SurfaceProjection::edgeIndex));
            return List.copyOf(projections);
        }

        private void index(SurfaceSweep sweep) {
            if (sweep.points().size() == 1) {
                index(new SurfaceSweepEdge(sweep, -1));
                return;
            }
            for (int edgeIndex = 0; edgeIndex < sweep.points().size() - 1; edgeIndex++) {
                index(new SurfaceSweepEdge(sweep, edgeIndex));
            }
        }

        private void index(SurfaceSweepEdge edge) {
            SurfaceSweepPoint start = edge.sweep().points().get(Math.max(0, edge.edgeIndex()));
            SurfaceSweepPoint end = edge.edgeIndex() < 0
                    ? start
                    : edge.sweep().points().get(edge.edgeIndex() + 1);
            int blockRadius = (int) StrictMath.ceil(Math.max(
                    start.shape().totalRadius(),
                    end.shape().totalRadius()
            ));
            int minimumChunkX = (Math.min(start.point().x(), end.point().x()) - blockRadius) >> 4;
            int maximumChunkX = (Math.max(start.point().x(), end.point().x()) + blockRadius) >> 4;
            int minimumChunkZ = (Math.min(start.point().z(), end.point().z()) - blockRadius) >> 4;
            int maximumChunkZ = (Math.max(start.point().z(), end.point().z()) + blockRadius) >> 4;
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
                    long chunkKey = RiverFootprint.pack(chunkX, chunkZ);
                    ArrayList<SurfaceSweepEdge> edges = edgesByChunk.get(chunkKey);
                    if (edges == null) {
                        edges = new ArrayList<>();
                        edgesByChunk.put(chunkKey, edges);
                    }
                    edges.add(edge);
                }
            }
        }
    }

    private static final class MutableColumn {
        private final int x;
        private final int z;
        private final int naturalHeight;
        private final int seaLevel;
        private final boolean ocean;
        private final String parentBiomeKey;
        private HydrologyColumnLayer singleLayer;
        private LinkedHashMap<Long, HydrologyColumnLayer> layers;

        private MutableColumn(int x, int z, HydrologyTerrainSample terrain, int seaLevel) {
            this.x = x;
            this.z = z;
            this.naturalHeight = terrain.naturalHeight();
            this.seaLevel = seaLevel;
            this.ocean = terrain.ocean();
            this.parentBiomeKey = terrain.parentBiomeKey();
        }

        private MutableColumn(HydrologyColumnSample sample) {
            this.x = sample.x();
            this.z = sample.z();
            this.naturalHeight = sample.naturalHeight();
            this.seaLevel = sample.seaLevel();
            this.ocean = sample.ocean();
            this.parentBiomeKey = sample.parentBiomeKey();
            for (HydrologyColumnLayer layer : sample.layers()) {
                add(layer);
            }
        }

        private void add(HydrologyColumnLayer layer) {
            if (layers == null) {
                if (singleLayer == null) {
                    singleLayer = layer;
                    return;
                }
                if (singleLayer.feature().id() == layer.feature().id()) {
                    singleLayer = merge(singleLayer, layer);
                    return;
                }
                layers = new LinkedHashMap<>();
                layers.put(singleLayer.feature().id(), singleLayer);
                singleLayer = null;
            }
            HydrologyColumnLayer existing = layers.get(layer.feature().id());
            layers.put(layer.feature().id(), existing == null ? layer : merge(existing, layer));
        }

        private HydrologyColumnSample build() {
            List<HydrologyColumnLayer> builtLayers;
            if (layers != null) {
                builtLayers = resolveSurfaceHeadConflicts(new ArrayList<>(layers.values()));
            } else if (singleLayer != null) {
                builtLayers = List.of(singleLayer);
            } else {
                builtLayers = List.of();
            }
            return new HydrologyColumnSample(
                    x,
                    z,
                    naturalHeight,
                    seaLevel,
                    ocean,
                    parentBiomeKey,
                    builtLayers
            );
        }

        private int maximumSurfaceFluidHead() {
            int maximum = Integer.MIN_VALUE;
            if (singleLayer != null) {
                return surfaceFluidHead(singleLayer);
            }
            if (layers == null) {
                return maximum;
            }
            for (HydrologyColumnLayer layer : layers.values()) {
                maximum = Math.max(maximum, surfaceFluidHead(layer));
            }
            return maximum;
        }

        private int surfaceFluidHead(HydrologyColumnLayer layer) {
            return layer.feature().type().isSurface()
                    && layer.channel()
                    && layer.connectedFluid()
                    && layer.fluidOwned()
                    && !layer.oceanApron()
                    && !layer.fallingFluid()
                    ? layer.fluidHeadY()
                    : Integer.MIN_VALUE;
        }

        private void raiseDrySurfaceBed(int minimumBed) {
            if (singleLayer != null) {
                singleLayer = raisedDrySurfaceLayer(singleLayer, naturalHeight, minimumBed);
                return;
            }
            if (layers == null) {
                return;
            }
            for (Map.Entry<Long, HydrologyColumnLayer> entry : layers.entrySet()) {
                entry.setValue(raisedDrySurfaceLayer(entry.getValue(), naturalHeight, minimumBed));
            }
        }

        private List<HydrologyColumnLayer> resolveSurfaceHeadConflicts(List<HydrologyColumnLayer> candidates) {
            HashMap<Long, HydrologyColumnLayer> selectedByCourse = new HashMap<>();
            for (HydrologyColumnLayer candidate : candidates) {
                if (!conflictingSurfaceCandidate(candidate)) {
                    continue;
                }
                long courseId = candidate.feature().courseId();
                HydrologyColumnLayer selected = selectedByCourse.get(courseId);
                if (selected == null || prefersSurfaceLayer(candidate, selected)) {
                    selectedByCourse.put(courseId, candidate);
                }
            }
            if (selectedByCourse.isEmpty()) {
                return candidates;
            }
            ArrayList<HydrologyColumnLayer> resolved = new ArrayList<>(candidates.size());
            for (HydrologyColumnLayer candidate : candidates) {
                HydrologyColumnLayer selected = selectedByCourse.get(candidate.feature().courseId());
                if (!conflictingSurfaceCandidate(candidate)
                        || selected == null
                        || candidate.fluidHeadY() == selected.fluidHeadY()) {
                    resolved.add(candidate);
                }
            }
            return List.copyOf(resolved);
        }

        private boolean conflictingSurfaceCandidate(HydrologyColumnLayer layer) {
            return layer.feature().type().isSurface()
                    && layer.channel()
                    && layer.connectedFluid()
                    && layer.fluidOwned()
                    && !layer.fallingFluid();
        }

        private boolean prefersSurfaceLayer(HydrologyColumnLayer candidate, HydrologyColumnLayer selected) {
            if (candidate.receivingPool() != selected.receivingPool()) {
                return candidate.receivingPool();
            }
            if (candidate.fluidHeadY() != selected.fluidHeadY()) {
                return candidate.fluidHeadY() > selected.fluidHeadY();
            }
            return candidate.feature().id() < selected.feature().id();
        }

        private void merge(HydrologyColumnSample sample) {
            if (sample.x() != x
                    || sample.z() != z
                    || sample.naturalHeight() != naturalHeight
                    || sample.seaLevel() != seaLevel
                    || sample.ocean() != ocean
                    || !sample.parentBiomeKey().equals(parentBiomeKey)) {
                throw new IllegalStateException("Hydrology course footprints disagree on terrain metadata at "
                        + x + "," + z + ".");
            }
            for (HydrologyColumnLayer layer : sample.layers()) {
                add(layer);
            }
        }

        private HydrologyColumnLayer merge(HydrologyColumnLayer first, HydrologyColumnLayer second) {
            if (first.equals(second)) {
                return first;
            }
            HydrologyFeatureRef feature = first.feature().y() <= second.feature().y()
                    ? first.feature()
                    : second.feature();
            boolean drySurfaceBlend = first.feature().type().isSurface()
                    && second.feature().type().isSurface()
                    && !first.channel()
                    && !second.channel();
            int bedY = drySurfaceBlend
                    ? Math.max(first.bedY(), second.bedY())
                    : Math.min(first.bedY(), second.bedY());
            return new HydrologyColumnLayer(
                    feature,
                    bedY,
                    Math.max(first.fluidHeadY(), second.fluidHeadY()),
                    Math.max(first.ceilingY(), second.ceilingY()),
                    first.channel() || second.channel(),
                    first.shore() || second.shore(),
                    first.grading() || second.grading(),
                    first.connectedFluid() || second.connectedFluid(),
                    first.fallingFluid() || second.fallingFluid(),
                    first.receivingPool() || second.receivingPool(),
                    first.terrainOwned() || second.terrainOwned(),
                    first.fluidOwned() || second.fluidOwned(),
                    first.oceanApron() || second.oceanApron(),
                    first.profileKey(),
                    first.surfaceBiomeKey(),
                    first.mouthBiomeKey(),
                    first.shoreBiomeKey(),
                    first.dryBiomeKey(),
                    first.floodedCaveBiomeKey()
            );
        }
    }
}
