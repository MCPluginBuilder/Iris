package art.arcane.iris.probe;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.hydrology.HydrologyCandidateKind;
import art.arcane.iris.engine.hydrology.HydrologyCandidateRejection;
import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.DrainageEdge;
import art.arcane.iris.engine.hydrology.DrainageNode;
import art.arcane.iris.engine.hydrology.HydrologyDiagnosticCandidate;
import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import art.arcane.iris.engine.hydrology.HydraulicSegment;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyPoint;
import art.arcane.iris.engine.hydrology.HydrologyTile;
import art.arcane.iris.engine.hydrology.HydrologyTileKey;
import art.arcane.iris.engine.hydrology.RiverCourse;
import art.arcane.iris.engine.hydrology.RiverCourseType;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import art.arcane.iris.engine.hydrology.RiverOutlet;
import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.CaveVoxel;
import art.arcane.iris.engine.hydrology.cave.CaveVoxelView;
import art.arcane.iris.engine.hydrology.cave.CaveVoxelPrecondition;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveAction;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveCell;
import art.arcane.iris.engine.hydrology.cave.HydrologyCavePlan;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveStorage;
import art.arcane.iris.engine.hydrology.runtime.IrisHydrologyRuntime;
import art.arcane.iris.engine.mantle.components.MantleHydrologyCaveVoxelView;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisBiomeCustom;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.volmlib.util.matter.MatterUpdate;
import art.arcane.volmlib.util.math.RNG;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class HydrologyPackProbe {
    private static final String LOG_PREFIX = "[hydrology-pack-probe]";
    private static final String GENERATED_RESULT_PREFIX = "IRIS_HYDROLOGY_GENERATED_RESULT";
    private static final int MAXIMUM_SEEDS = 32;
    private static final int MAXIMUM_TILES_PER_SEED = 256;
    private static final int MAXIMUM_TOTAL_TILES = 1_024;
    private static final int MAXIMUM_REQUIRED_SELECTORS = 128;
    private static final Set<String> WATER_COLUMN_REPLACEMENTS = Set.of(
            "minecraft:bubble_column",
            "minecraft:kelp",
            "minecraft:kelp_plant",
            "minecraft:seagrass",
            "minecraft:tall_seagrass"
    );

    private HydrologyPackProbe() {
    }

    record CoverageSelector(HydrologyFeatureType type, String profileKey) {
        CoverageSelector {
            if (type == null) {
                throw new IllegalArgumentException("Coverage feature type is required.");
            }
            if (profileKey == null || profileKey.isBlank()
                    || profileKey.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Coverage profile must be non-blank and contain no whitespace.");
            }
        }

        static CoverageSelector parse(String value) {
            int separator = value.indexOf('@');
            if (separator <= 0 || separator != value.lastIndexOf('@') || separator == value.length() - 1) {
                throw new IllegalArgumentException(
                        "Coverage selector must use TYPE@profile or TYPE@*: " + value);
            }
            HydrologyFeatureType type;
            try {
                type = HydrologyFeatureType.valueOf(value.substring(0, separator).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unknown hydrology feature type in selector: " + value, failure);
            }
            return new CoverageSelector(type, value.substring(separator + 1));
        }

        boolean matches(CoverageKey key) {
            return type == key.type() && (profileKey.equals("*") || profileKey.equals(key.profileKey()));
        }

        String label() {
            return type.name() + "@" + profileKey;
        }
    }

    record ProbeConfiguration(
            File packSource,
            String dimensionKey,
            List<Long> seeds,
            int minimumTileX,
            int maximumTileX,
            int minimumTileZ,
            int maximumTileZ,
            List<CoverageSelector> requiredCoverage,
            boolean studio
    ) {
        ProbeConfiguration {
            if (packSource == null) {
                throw new IllegalArgumentException("Pack folder is required.");
            }
            if (dimensionKey == null || dimensionKey.isBlank()
                    || dimensionKey.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Dimension key must be non-blank and contain no whitespace.");
            }
            if (seeds == null || seeds.size() < 2 || seeds.size() > MAXIMUM_SEEDS) {
                throw new IllegalArgumentException("Hydrology pack scans require between 2 and "
                        + MAXIMUM_SEEDS + " explicit seeds.");
            }
            seeds = List.copyOf(seeds);
            if (new HashSet<>(seeds).size() != seeds.size()) {
                throw new IllegalArgumentException("Hydrology pack scan seeds must be unique.");
            }
            if (minimumTileX > maximumTileX || minimumTileZ > maximumTileZ) {
                throw new IllegalArgumentException("Minimum tile bounds cannot exceed maximum tile bounds.");
            }
            long tilesPerSeed = boundedArea(
                    minimumTileX, maximumTileX, minimumTileZ, maximumTileZ, "tile");
            if (tilesPerSeed > MAXIMUM_TILES_PER_SEED) {
                throw new IllegalArgumentException("Hydrology pack scan exceeds "
                        + MAXIMUM_TILES_PER_SEED + " tiles per seed.");
            }
            if (tilesPerSeed * seeds.size() > MAXIMUM_TOTAL_TILES) {
                throw new IllegalArgumentException("Hydrology pack scan exceeds "
                        + MAXIMUM_TOTAL_TILES + " total seed-tile evaluations.");
            }
            if (requiredCoverage == null || requiredCoverage.isEmpty()
                    || requiredCoverage.size() > MAXIMUM_REQUIRED_SELECTORS) {
                throw new IllegalArgumentException("Hydrology pack scans require between 1 and "
                        + MAXIMUM_REQUIRED_SELECTORS + " coverage selectors.");
            }
            requiredCoverage = List.copyOf(requiredCoverage);
            Set<CoverageSelector> uniqueSelectors = new HashSet<>(requiredCoverage);
            if (uniqueSelectors.size() != requiredCoverage.size()) {
                throw new IllegalArgumentException("Hydrology coverage selectors must be unique.");
            }
        }

        static ProbeConfiguration parse(String[] arguments) {
            if (arguments.length != 9) {
                throw new IllegalArgumentException(
                        "Expected: <pack> <dimension> <seed,seed,...> <minimumTileX> <maximumTileX> "
                                + "<minimumTileZ> <maximumTileZ> <TYPE@profile,...> <studio>");
            }
            return new ProbeConfiguration(
                    new File(arguments[0]),
                    arguments[1],
                    parseSeeds(arguments[2]),
                    Integer.parseInt(arguments[3]),
                    Integer.parseInt(arguments[4]),
                    Integer.parseInt(arguments[5]),
                    Integer.parseInt(arguments[6]),
                    parseCoverage(arguments[7]),
                    RealPackProbeSupport.parseBoolean(arguments[8], "studio")
            );
        }

        int tilesPerSeed() {
            return Math.toIntExact(boundedArea(
                    minimumTileX, maximumTileX, minimumTileZ, maximumTileZ, "tile"));
        }
    }

    record CoverageKey(HydrologyFeatureType type, String profileKey) implements Comparable<CoverageKey> {
        CoverageKey {
            if (type == null || profileKey == null || profileKey.isBlank()) {
                throw new IllegalArgumentException("Observed hydrology coverage requires a type and profile.");
            }
        }

        @Override
        public int compareTo(CoverageKey other) {
            int typeComparison = Integer.compare(type.ordinal(), other.type.ordinal());
            return typeComparison != 0 ? typeComparison : profileKey.compareTo(other.profileKey);
        }

        String label() {
            return type.name() + "@" + profileKey;
        }
    }

    enum CoverageFamily {
        SURFACE,
        UNDERGROUND,
        DEEP
    }

    enum VerificationFamily {
        SURFACE,
        CAVE,
        GROTTO,
        DEEP
    }

    record ConfiguredCoverage(CoverageFamily family, String profileKey) implements Comparable<ConfiguredCoverage> {
        ConfiguredCoverage {
            if (family == null || profileKey == null || profileKey.isBlank()) {
                throw new IllegalArgumentException("Configured coverage requires a family and profile.");
            }
        }

        String label() {
            return family.name() + "@" + profileKey;
        }

        @Override
        public int compareTo(ConfiguredCoverage other) {
            int familyComparison = Integer.compare(family.ordinal(), other.family.ordinal());
            return familyComparison != 0 ? familyComparison : profileKey.compareTo(other.profileKey);
        }
    }

    record SeedCoverageKey(long seed, CoverageKey coverage) implements Comparable<SeedCoverageKey> {
        @Override
        public int compareTo(SeedCoverageKey other) {
            int seedComparison = Long.compare(seed, other.seed);
            return seedComparison != 0 ? seedComparison : coverage.compareTo(other.coverage);
        }
    }

    record ObservedFeature(
            long seed,
            HydrologyTileKey tile,
            String profileKey,
            HydrologyFeatureRef feature,
            int minimumWorldY
    ) {
    }

    record GeneratedWitness(
            CoverageSelector selector,
            ObservedFeature observed,
            HydrologyColumnLayer layer,
            CavePosition position,
            HydrologyCaveAction action
    ) {
    }

    record ChunkCoordinate(int chunkX, int chunkZ) {
    }

    record GeneratedWitnessDescriptor(
            CoverageSelector selector,
            HydrologyTileKey tile,
            String profileKey,
            long featureId,
            CavePosition position,
            HydrologyCaveAction action
    ) {
        GeneratedWitnessDescriptor {
            if (selector == null || tile == null || profileKey == null || profileKey.isBlank()
                    || position == null || action == null) {
                throw new IllegalArgumentException("Generated witness descriptor is incomplete.");
            }
        }

        static GeneratedWitnessDescriptor from(GeneratedWitness witness) {
            return new GeneratedWitnessDescriptor(
                    witness.selector(),
                    witness.observed().tile(),
                    witness.observed().profileKey(),
                    witness.observed().feature().id(),
                    witness.position(),
                    witness.action()
            );
        }

        static GeneratedWitnessDescriptor parse(String value) {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 9) {
                throw new IllegalArgumentException("Generated witness descriptor has an invalid field count.");
            }
            return new GeneratedWitnessDescriptor(
                    CoverageSelector.parse(decodeToken(fields[0])),
                    new HydrologyTileKey(Integer.parseInt(fields[1]), Integer.parseInt(fields[2])),
                    decodeToken(fields[3]),
                    Long.parseUnsignedLong(fields[4]),
                    new CavePosition(
                            Integer.parseInt(fields[5]),
                            Integer.parseInt(fields[6]),
                            Integer.parseInt(fields[7])
                    ),
                    HydrologyCaveAction.valueOf(fields[8])
            );
        }

        String encode() {
            return String.join(
                    "|",
                    encodeToken(selector.label()),
                    Integer.toString(tile.tileX()),
                    Integer.toString(tile.tileZ()),
                    encodeToken(profileKey),
                    Long.toUnsignedString(featureId),
                    Integer.toString(position.x()),
                    Integer.toString(position.y()),
                    Integer.toString(position.z()),
                    action.name()
            );
        }
    }

    record GeneratedVerification(String blockStateKey, String biomeKey) {
        GeneratedVerification {
            if (blockStateKey == null || biomeKey == null) {
                throw new IllegalArgumentException("Generated verification keys are required.");
            }
        }
    }

    record GeneratedProcessResult(CoverageSelector selector, GeneratedVerification verification) {
        GeneratedProcessResult {
            if (selector == null || verification == null) {
                throw new IllegalArgumentException("Generated process result is incomplete.");
            }
        }

        static GeneratedProcessResult parse(String line) {
            if (line == null || !line.startsWith(GENERATED_RESULT_PREFIX + " ")) {
                throw new IllegalArgumentException("Generated process result has an invalid prefix.");
            }
            String[] fields = line.substring(GENERATED_RESULT_PREFIX.length() + 1).split(" ", -1);
            if (fields.length != 3
                    || !fields[0].startsWith("selector=")
                    || !fields[1].startsWith("block=")
                    || !fields[2].startsWith("biome=")) {
                throw new IllegalArgumentException("Generated process result has invalid fields.");
            }
            return new GeneratedProcessResult(
                    CoverageSelector.parse(decodeToken(fields[0].substring("selector=".length()))),
                    new GeneratedVerification(
                            decodeToken(fields[1].substring("block=".length())),
                            decodeToken(fields[2].substring("biome=".length()))
                    )
            );
        }

        String machineLine() {
            return GENERATED_RESULT_PREFIX
                    + " selector=" + encodeToken(selector.label())
                    + " block=" + encodeToken(verification.blockStateKey())
                    + " biome=" + encodeToken(verification.biomeKey());
        }
    }

    record SeedChunk(long seed, int chunkX, int chunkZ) {
    }

    record RejectionKey(
            HydrologyCandidateKind kind,
            HydrologyFeatureType type,
            HydrologyCandidateRejection rejection
    ) implements Comparable<RejectionKey> {
        RejectionKey {
            if (kind == null || type == null || rejection == null) {
                throw new IllegalArgumentException("Hydrology rejection coverage requires complete classification.");
            }
        }

        @Override
        public int compareTo(RejectionKey other) {
            int kindComparison = Integer.compare(kind.ordinal(), other.kind.ordinal());
            if (kindComparison != 0) {
                return kindComparison;
            }
            int typeComparison = Integer.compare(type.ordinal(), other.type.ordinal());
            return typeComparison != 0
                    ? typeComparison
                    : Integer.compare(rejection.ordinal(), other.rejection.ordinal());
        }
    }

    record CourseMorphologyInput(
            long seed,
            long courseId,
            int maximumWidth,
            int minimumWidth,
            int minimumExposedSurfaceLength,
            double exposedSurfaceLength,
            boolean complete,
            boolean terminalExposed,
            List<HydrologyPoint> centerline,
            Set<Long> ownedCells
    ) {
        CourseMorphologyInput {
            if (maximumWidth < 1 || minimumWidth < 1
                    || minimumExposedSurfaceLength < 0 || exposedSurfaceLength < 0D
                    || centerline == null || centerline.size() < 2 || ownedCells == null) {
                throw new IllegalArgumentException("Published morphology input is incomplete.");
            }
            centerline = List.copyOf(centerline);
            ownedCells = Set.copyOf(ownedCells);
        }

        HydrologyPoint source() {
            return centerline.getFirst();
        }

        HydrologyPoint terminal() {
            return centerline.getLast();
        }
    }

    record CourseMorphologyResult(
            long seed,
            long courseId,
            int ownedCells,
            double routedLength,
            double exposedSurfaceLength,
            int minimumExposedSurfaceLength,
            double gridLockedFraction,
            double longestGridLockedRun,
            int isolatedTurns,
            double p95TurnDegrees,
            int hardEndpointRamps,
            int sourceEndpointWidth,
            int sourceCruiseWidth,
            double sourceRampLength,
            int terminalEndpointWidth,
            int terminalCruiseWidth,
            double terminalRampLength,
            int clippedTerminalCaps,
            double terminalCapLength,
            int minimumInteriorWidth,
            double maximumWidthTroughDepthRatio,
            int unexpectedLeaves,
            List<String> violations
    ) {
        CourseMorphologyResult {
            violations = List.copyOf(violations);
        }

        boolean accepted() {
            return violations.isEmpty();
        }
    }

    record SeedMorphologyResult(
            long seed,
            List<CourseMorphologyResult> courses,
            int unexpectedLeaves,
            List<String> violations
    ) {
        SeedMorphologyResult {
            courses = List.copyOf(courses);
            violations = List.copyOf(violations);
        }

        boolean accepted() {
            return violations.isEmpty();
        }
    }

    static final class PublishedMorphologyMetrics {
        private static final double SAMPLE_SPACING = 16D;
        private static final double GRID_LOCK_TOLERANCE_DEGREES = 5D;
        private static final double MAXIMUM_GRID_LOCKED_FRACTION = 0.5D;
        private static final double MAXIMUM_GRID_LOCKED_RUN = 64D;
        private static final double ISOLATED_TURN_DEGREES = 35D;
        private static final double ISOLATED_NEIGHBOR_TURN_DEGREES = 10D;
        private static final double MAXIMUM_P95_TURN_DEGREES = 35D;
        private static final double MINIMUM_ENDPOINT_RAMP_WIDTHS = 2D;
        private static final double MINIMUM_PLANFORM_LENGTH = 96D;
        private static final int WIDTH_SAMPLE_SPACING = 4;
        private static final int MINIMUM_ENDPOINT_PROFILE_MARGIN = 48;
        private static final int MINIMUM_TROUGH_LENGTH = 32;
        private static final int MINIMUM_TROUGH_WIDTH_DEFICIT = 3;
        private static final double MAXIMUM_TROUGH_WIDTH_RATIO = 0.65D;
        private static final double MINIMUM_TERMINAL_CAP_WIDTH_RATIO = 0.2D;
        private static final int MINIMUM_TERMINAL_CAP_LENGTH = 2;
        private static final int MAXIMUM_IMAGE_DIMENSION = 2048;

        private final TreeMap<Long, SeedAccumulator> seeds = new TreeMap<>();

        void observe(HydrologyTile tile, int minimumWidth, int minimumExposedSurfaceLength) {
            SeedAccumulator seed = seeds.computeIfAbsent(tile.worldSeed(), SeedAccumulator::new);
            HashMap<Long, CourseAccumulator> observedCourses = new HashMap<>();
            for (RiverCourse course : tile.courses()) {
                if (course.type() != RiverCourseType.SURFACE) {
                    continue;
                }
                CourseAccumulator accumulator = seed.courses.computeIfAbsent(
                        course.id(),
                        (Long ignored) -> new CourseAccumulator(tile.worldSeed(), course.id())
                );
                accumulator.register(course, tile, minimumWidth, minimumExposedSurfaceLength);
                observedCourses.put(course.id(), accumulator);
            }
            for (HydrologyColumnSample sample : tile.footprint().columns().values()) {
                HydrologyColumnLayer layer = sample.primarySurfaceFluidLayer().orElse(null);
                if (layer == null
                        || !layer.feature().type().isSurface()
                        || !layer.channel()
                        || !layer.connectedFluid()
                        || !layer.fluidOwned()
                        || layer.oceanApron()) {
                    continue;
                }
                CourseAccumulator course = observedCourses.get(layer.feature().courseId());
                if (course == null) {
                    course = seed.courses.get(layer.feature().courseId());
                }
                if (course == null) {
                    continue;
                }
                long packed = RiverFootprint.pack(sample.x(), sample.z());
                course.ownedCells.add(packed);
                seed.courseByCell.put(packed, course.courseId);
            }
            seed.result = null;
        }

        List<SeedMorphologyResult> results() {
            ArrayList<SeedMorphologyResult> results = new ArrayList<>(seeds.size());
            for (SeedAccumulator seed : seeds.values()) {
                results.add(seed.result());
            }
            return List.copyOf(results);
        }

        void requireValid() {
            requireValidResults(results());
        }

        static void requireValidResults(List<SeedMorphologyResult> results) {
            ArrayList<String> failures = new ArrayList<>();
            for (SeedMorphologyResult result : results) {
                if (result.courses().isEmpty()) {
                    failures.add("seed=" + result.seed() + " [NO_SURFACE_COURSES]");
                    continue;
                }
                if (!result.accepted()) {
                    failures.add("seed=" + result.seed() + " " + result.violations());
                }
            }
            if (results.isEmpty()) {
                failures.add("scan=[NO_SURFACE_COURSES]");
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Real-pack published surface morphology failed: " + failures);
            }
        }

        void writeReports(File reportDirectory) throws IOException {
            Path directory = reportDirectory.toPath();
            Files.createDirectories(directory);
            for (SeedAccumulator seed : seeds.values()) {
                writeReport(reportDirectory, seed.result(), seed.courseByCell);
            }
        }

        static void writeReport(
                File reportDirectory,
                SeedMorphologyResult result,
                Map<Long, Long> courseByCell
        ) throws IOException {
            Path directory = reportDirectory.toPath();
            Files.createDirectories(directory);
            String fileSeed = Long.toString(result.seed());
            Path imagePath = directory.resolve("seed-" + fileSeed + "-surface-footprint.png");
            Path metricsPath = directory.resolve("seed-" + fileSeed + "-surface-morphology.json");
            writeImage(courseByCell, imagePath);
            Files.writeString(metricsPath, json(result, imagePath.getFileName().toString()), StandardCharsets.UTF_8);
        }

        String summary() {
            int courses = 0;
            int failures = 0;
            int unexpectedLeaves = 0;
            int clippedTerminalCaps = 0;
            int minimumInteriorWidth = Integer.MAX_VALUE;
            double maximumWidthTroughDepthRatio = 0D;
            for (SeedMorphologyResult result : results()) {
                courses += result.courses().size();
                unexpectedLeaves += result.unexpectedLeaves();
                for (CourseMorphologyResult course : result.courses()) {
                    clippedTerminalCaps += course.clippedTerminalCaps();
                    if (course.minimumInteriorWidth() > 0) {
                        minimumInteriorWidth = Math.min(minimumInteriorWidth, course.minimumInteriorWidth());
                    }
                    maximumWidthTroughDepthRatio = Math.max(
                            maximumWidthTroughDepthRatio,
                            course.maximumWidthTroughDepthRatio()
                    );
                }
                if (!result.accepted()) {
                    failures++;
                }
            }
            return "morphology_seeds=" + seeds.size()
                    + " morphology_courses=" + courses
                    + " morphology_failed_seeds=" + failures
                    + " morphology_unexpected_leaves=" + unexpectedLeaves
                    + " morphology_clipped_terminal_caps=" + clippedTerminalCaps
                    + " morphology_minimum_interior_width="
                    + (minimumInteriorWidth == Integer.MAX_VALUE ? 0 : minimumInteriorWidth)
                    + " morphology_maximum_width_trough_depth_ratio="
                    + format(maximumWidthTroughDepthRatio);
        }

        static CourseMorphologyResult analyzeCourse(CourseMorphologyInput input) {
            ArrayList<String> violations = new ArrayList<>();
            List<HydrologyPoint> raster = rasterCenterline(input.centerline());
            List<List<HydrologyPoint>> publishedRuns = publishedRuns(input, raster);
            if (publishedRuns.isEmpty()) {
                violations.add("NO_PUBLISHED_CENTERLINE");
                return new CourseMorphologyResult(
                        input.seed(),
                        input.courseId(),
                        input.ownedCells().size(),
                        0D,
                        input.exposedSurfaceLength(),
                        input.minimumExposedSurfaceLength(),
                        1D,
                        0D,
                        0,
                        0D,
                        2,
                        0,
                        0,
                        0D,
                        0,
                        0,
                        0D,
                        0,
                        0D,
                        0,
                        0D,
                        0,
                        violations
                );
            }
            double routedLength = 0D;
            double lockedLength = 0D;
            double longestGridLockedRun = 0D;
            int isolatedTurns = 0;
            ArrayList<Double> turnDegrees = new ArrayList<>();
            for (List<HydrologyPoint> published : publishedRuns) {
                List<HydrologyPoint> smoothed = smoothCenterline(published, 4);
                List<HydrologyPoint> sampled = resampleCenterline(smoothed, SAMPLE_SPACING);
                double runLength = routeLength(sampled);
                HeadingMetrics heading = headingMetrics(sampled);
                routedLength += runLength;
                lockedLength += heading.gridLockedFraction() * runLength;
                longestGridLockedRun = Math.max(longestGridLockedRun, heading.longestGridLockedRun());
                if (runLength >= SAMPLE_SPACING * 2D && sampled.size() >= 3) {
                    TurnMetrics turns = turnMetrics(sampled);
                    isolatedTurns += turns.isolatedTurns();
                    turnDegrees.addAll(turns.degrees());
                }
            }
            double gridLockedFraction = routedLength == 0D ? 0D : lockedLength / routedLength;
            turnDegrees.sort(Double::compare);
            double p95TurnDegrees = percentile95(turnDegrees);
            EndpointRampMetrics endpointRamps = endpointRampMetrics(input, raster);
            int hardEndpointRamps = endpointRamps.hardRamps();
            TerminalCapMetrics terminalCap = terminalCapMetrics(input, raster);
            InteriorWidthMetrics interiorWidths = interiorWidthMetrics(input, publishedRuns);
            List<HydrologyPoint> expectedLeaves = publishedEndpoints(input, raster);
            int unexpectedLeaves = unexpectedLeaves(
                    input.ownedCells(),
                    expectedLeaves,
                    raster,
                    input.maximumWidth()
            );
            if (routedLength >= MINIMUM_PLANFORM_LENGTH) {
                if (gridLockedFraction > MAXIMUM_GRID_LOCKED_FRACTION) {
                    violations.add("GRID_LOCKED_FRACTION");
                }
                if (longestGridLockedRun > MAXIMUM_GRID_LOCKED_RUN) {
                    violations.add("GRID_LOCKED_RUN");
                }
                if (isolatedTurns > 0) {
                    violations.add("ISOLATED_TURN");
                }
                if (p95TurnDegrees > MAXIMUM_P95_TURN_DEGREES) {
                    violations.add("P95_TURN");
                }
            }
            if (input.complete()
                    && input.exposedSurfaceLength() < input.minimumExposedSurfaceLength()) {
                violations.add("INSUFFICIENT_EXPOSED_SURFACE");
            }
            if (hardEndpointRamps > 0) {
                violations.add("HARD_ENDPOINT_RAMP");
            }
            int sourceWidthTolerance = Math.max(
                    1,
                    (int) StrictMath.ceil(endpointRamps.sourceCruiseWidth() * 0.1D)
            );
            if (endpointRamps.sourceEndpointWidth()
                    > endpointRamps.sourceCruiseWidth() + sourceWidthTolerance) {
                violations.add("WIDENED_SOURCE_BASIN");
            }
            if (terminalCap.clipped()) {
                violations.add("CLIPPED_TERMINAL_CAP");
            }
            if (interiorWidths.minimumWidth() > 0
                    && interiorWidths.minimumWidth() < input.minimumWidth()) {
                violations.add("NARROW_INTERIOR_WIDTH");
            }
            if (interiorWidths.maximumTroughDepthRatio() >= 1D - MAXIMUM_TROUGH_WIDTH_RATIO) {
                violations.add("CONCAVE_WIDTH_TROUGH");
            }
            if (unexpectedLeaves > 0) {
                violations.add("UNEXPECTED_BRANCH_LEAVES=" + unexpectedLeaves);
            }
            return new CourseMorphologyResult(
                    input.seed(),
                    input.courseId(),
                    input.ownedCells().size(),
                    routedLength,
                    input.exposedSurfaceLength(),
                    input.minimumExposedSurfaceLength(),
                    gridLockedFraction,
                    longestGridLockedRun,
                    isolatedTurns,
                    p95TurnDegrees,
                    hardEndpointRamps,
                    endpointRamps.sourceEndpointWidth(),
                    endpointRamps.sourceCruiseWidth(),
                    endpointRamps.sourceRampLength(),
                    endpointRamps.terminalEndpointWidth(),
                    endpointRamps.terminalCruiseWidth(),
                    endpointRamps.terminalRampLength(),
                    terminalCap.clipped() ? 1 : 0,
                    terminalCap.length(),
                    interiorWidths.minimumWidth(),
                    interiorWidths.maximumTroughDepthRatio(),
                    unexpectedLeaves,
                    violations
            );
        }

        static SeedMorphologyResult analyzeSeed(long seed, List<CourseMorphologyInput> inputs) {
            ArrayList<CourseMorphologyResult> courses = new ArrayList<>(inputs.size());
            HashSet<Long> ownedCells = new HashSet<>();
            ArrayList<HydrologyPoint> expectedLeaves = new ArrayList<>();
            ArrayList<HydrologyPoint> expectedCenterlines = new ArrayList<>();
            int maximumWidth = 1;
            for (CourseMorphologyInput input : inputs) {
                if (input.seed() != seed) {
                    throw new IllegalArgumentException("Morphology course seed differs from its seed report.");
                }
                courses.add(analyzeCourse(input));
                ownedCells.addAll(input.ownedCells());
                List<HydrologyPoint> raster = rasterCenterline(input.centerline());
                expectedLeaves.addAll(publishedEndpoints(input, raster));
                expectedCenterlines.addAll(raster);
                maximumWidth = Math.max(maximumWidth, input.maximumWidth());
            }
            int unexpectedLeaves = unexpectedLeaves(
                    ownedCells,
                    expectedLeaves,
                    expectedCenterlines,
                    maximumWidth
            );
            ArrayList<String> violations = new ArrayList<>();
            for (CourseMorphologyResult course : courses) {
                for (String violation : course.violations()) {
                    violations.add(Long.toUnsignedString(course.courseId(), 16) + ":" + violation);
                }
            }
            if (unexpectedLeaves > 0) {
                violations.add("UNEXPECTED_BRANCH_LEAVES=" + unexpectedLeaves);
            }
            return new SeedMorphologyResult(seed, courses, unexpectedLeaves, violations);
        }

        private static EndpointRampMetrics endpointRampMetrics(
                CourseMorphologyInput input,
                List<HydrologyPoint> raster
        ) {
            if (raster.size() < 2 || input.ownedCells().isEmpty()) {
                return new EndpointRampMetrics(2, 0, 0, 0D, 0, 0, 0D);
            }
            int hard = 0;
            int sourceEndpointWidth = 0;
            int sourceCruiseWidth = 0;
            double sourceRampLength = 0D;
            if (ownedNear(
                    input.ownedCells(),
                    input.source().x(),
                    input.source().z(),
                    input.maximumWidth()
            )) {
                sourceCruiseWidth = cruiseWidth(input.ownedCells(), raster, input.maximumWidth(), true);
                sourceEndpointWidth = endpointWidth(input.ownedCells(), raster, input.maximumWidth(), true);
                sourceRampLength = endpointTransitionLength(
                        input.ownedCells(),
                        raster,
                        input.maximumWidth(),
                        sourceCruiseWidth,
                        true
                );
                if (sourceRampLength < sourceCruiseWidth * MINIMUM_ENDPOINT_RAMP_WIDTHS) {
                    hard++;
                }
            }
            if (!input.terminalExposed() || !ownedNear(
                    input.ownedCells(),
                    input.terminal().x(),
                    input.terminal().z(),
                    input.maximumWidth()
            )) {
                return new EndpointRampMetrics(
                        hard,
                        sourceEndpointWidth,
                        sourceCruiseWidth,
                        sourceRampLength,
                        0,
                        0,
                        0D
                );
            }
            int terminalWidth = cruiseWidth(input.ownedCells(), raster, input.maximumWidth(), false);
            int terminalEndpointWidth = endpointWidth(input.ownedCells(), raster, input.maximumWidth(), false);
            double terminalRamp = endpointTransitionLength(
                    input.ownedCells(),
                    raster,
                    input.maximumWidth(),
                    terminalWidth,
                    false
            );
            return new EndpointRampMetrics(
                    terminalRamp < terminalWidth * MINIMUM_ENDPOINT_RAMP_WIDTHS ? hard + 1 : hard,
                    sourceEndpointWidth,
                    sourceCruiseWidth,
                    sourceRampLength,
                    terminalEndpointWidth,
                    terminalWidth,
                    terminalRamp
            );
        }

        private static int endpointWidth(
                Set<Long> cells,
                List<HydrologyPoint> raster,
                int maximumWidth,
                boolean source
        ) {
            int endpointIndex = source ? 0 : raster.size() - 1;
            int adjacentIndex = source ? 1 : raster.size() - 2;
            return crossSectionWidth(
                    cells,
                    raster.get(endpointIndex),
                    raster.get(endpointIndex),
                    raster.get(adjacentIndex),
                    maximumWidth
            );
        }

        private static int cruiseWidth(
                Set<Long> cells,
                List<HydrologyPoint> raster,
                int maximumWidth,
                boolean source
        ) {
            int start = Math.min(raster.size() - 2, Math.max(48, maximumWidth * 4));
            int end = Math.min(raster.size() - 1, Math.max(start + 16, maximumWidth * 6));
            ArrayList<Integer> widths = new ArrayList<>();
            for (int offset = start; offset <= end; offset++) {
                int index = source ? offset : raster.size() - 1 - offset;
                if (index <= 0 || index >= raster.size() - 1) {
                    continue;
                }
                int width = crossSectionWidth(
                        cells,
                        raster.get(index),
                        raster.get(index - 1),
                        raster.get(index + 1),
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

        private static double endpointTransitionLength(
                Set<Long> cells,
                List<HydrologyPoint> raster,
                int maximumWidth,
                int cruiseWidth,
                boolean source
        ) {
            int endpointIndex = source ? 0 : raster.size() - 1;
            int direction = source ? 1 : -1;
            int search = Math.max(32, cruiseWidth * 6);
            int adjacentIndex = endpointIndex + direction;
            int endpointWidth = crossSectionWidth(
                    cells,
                    raster.get(endpointIndex),
                    raster.get(endpointIndex),
                    raster.get(adjacentIndex),
                    maximumWidth
            );
            int widthTolerance = Math.max(1, (int) StrictMath.ceil(cruiseWidth * 0.1D));
            if (Math.abs(endpointWidth - cruiseWidth) <= widthTolerance) {
                return 0D;
            }
            double distance = 0D;
            int index = adjacentIndex;
            while (index >= 0 && index < raster.size() && distance <= search) {
                int previousIndex = Math.max(0, Math.min(raster.size() - 1, index - direction));
                int nextIndex = Math.max(0, Math.min(raster.size() - 1, index + direction));
                HydrologyPoint previous = raster.get(previousIndex);
                HydrologyPoint station = raster.get(index);
                HydrologyPoint next = raster.get(nextIndex);
                distance += StrictMath.hypot(station.x() - previous.x(), station.z() - previous.z());
                int width = crossSectionWidth(cells, station, previous, next, maximumWidth);
                if (Math.abs(width - cruiseWidth) <= widthTolerance) {
                    return distance;
                }
                index += direction;
            }
            return distance;
        }

        private static TerminalCapMetrics terminalCapMetrics(
                CourseMorphologyInput input,
                List<HydrologyPoint> raster
        ) {
            if (!input.complete() || !input.terminalExposed() || raster.size() < 2
                    || !ownedNear(
                    input.ownedCells(),
                    input.terminal().x(),
                    input.terminal().z(),
                    input.maximumWidth()
            )) {
                return new TerminalCapMetrics(false, 0D);
            }
            int terminalIndex = raster.size() - 1;
            int tangentIndex = Math.max(0, terminalIndex - Math.max(8, input.maximumWidth()));
            HydrologyPoint tangentStart = raster.get(tangentIndex);
            HydrologyPoint terminal = raster.get(terminalIndex);
            double tangentX = terminal.x() - tangentStart.x();
            double tangentZ = terminal.z() - tangentStart.z();
            double tangentLength = StrictMath.hypot(tangentX, tangentZ);
            if (tangentLength == 0D) {
                return new TerminalCapMetrics(true, 0D);
            }
            tangentX /= tangentLength;
            tangentZ /= tangentLength;
            int terminalWidth = crossSectionWidth(
                    input.ownedCells(),
                    terminal,
                    tangentStart,
                    terminal,
                    input.maximumWidth()
            );
            int searchLength = Math.max(MINIMUM_TERMINAL_CAP_LENGTH, input.maximumWidth() * 2);
            double capLength = 0D;
            for (int offset = 1; offset <= searchLength; offset++) {
                double centerX = terminal.x() + tangentX * offset;
                double centerZ = terminal.z() + tangentZ * offset;
                int width = crossSectionWidth(
                        input.ownedCells(),
                        centerX,
                        centerZ,
                        tangentX,
                        tangentZ,
                        input.maximumWidth()
                );
                if (width == 0) {
                    break;
                }
                capLength = offset;
            }
            double requiredLength = Math.max(
                    MINIMUM_TERMINAL_CAP_LENGTH,
                    terminalWidth * MINIMUM_TERMINAL_CAP_WIDTH_RATIO
            );
            return new TerminalCapMetrics(capLength + 0.25D < requiredLength, capLength);
        }

        private static InteriorWidthMetrics interiorWidthMetrics(
                CourseMorphologyInput input,
                List<List<HydrologyPoint>> publishedRuns
        ) {
            if (!input.complete()) {
                return new InteriorWidthMetrics(0, 0D);
            }
            int minimumWidth = Integer.MAX_VALUE;
            double maximumTroughDepthRatio = 0D;
            int endpointMargin = Math.max(
                    MINIMUM_ENDPOINT_PROFILE_MARGIN,
                    input.maximumWidth() * 4
            );
            int troughWindowSamples = Math.max(
                    1,
                    Math.floorDiv(
                            Math.max(MINIMUM_TROUGH_LENGTH, input.minimumWidth() * 4)
                                    + WIDTH_SAMPLE_SPACING - 1,
                            WIDTH_SAMPLE_SPACING
                    )
            );
            for (List<HydrologyPoint> run : publishedRuns) {
                int firstIndex = Math.min(run.size(), endpointMargin);
                int lastIndex = Math.max(firstIndex, run.size() - endpointMargin);
                ArrayList<Integer> widths = new ArrayList<>();
                for (int index = firstIndex; index < lastIndex; index += WIDTH_SAMPLE_SPACING) {
                    int previousIndex = Math.max(0, index - 1);
                    int nextIndex = Math.min(run.size() - 1, index + 1);
                    int width = crossSectionWidth(
                            input.ownedCells(),
                            run.get(index),
                            run.get(previousIndex),
                            run.get(nextIndex),
                            input.maximumWidth()
                    );
                    if (width <= 0) {
                        continue;
                    }
                    widths.add(width);
                    minimumWidth = Math.min(minimumWidth, width);
                }
                for (int start = 0; start + troughWindowSamples * 3 <= widths.size(); start++) {
                    int leftWidth = median(widths, start, start + troughWindowSamples);
                    int middleWidth = median(
                            widths,
                            start + troughWindowSamples,
                            start + troughWindowSamples * 2
                    );
                    int rightWidth = median(
                            widths,
                            start + troughWindowSamples * 2,
                            start + troughWindowSamples * 3
                    );
                    int rimWidth = Math.min(leftWidth, rightWidth);
                    if (rimWidth <= 0 || rimWidth - middleWidth < MINIMUM_TROUGH_WIDTH_DEFICIT) {
                        continue;
                    }
                    maximumTroughDepthRatio = Math.max(
                            maximumTroughDepthRatio,
                            (rimWidth - middleWidth) / (double) rimWidth
                    );
                }
            }
            return new InteriorWidthMetrics(
                    minimumWidth == Integer.MAX_VALUE ? 0 : minimumWidth,
                    maximumTroughDepthRatio
            );
        }

        private static int median(List<Integer> values, int firstIndex, int lastIndex) {
            ArrayList<Integer> sorted = new ArrayList<>(values.subList(firstIndex, lastIndex));
            sorted.sort(Integer::compare);
            return sorted.get(sorted.size() / 2);
        }

        private static List<HydrologyPoint> publishedEndpoints(
                CourseMorphologyInput input,
                List<HydrologyPoint> raster
        ) {
            List<List<HydrologyPoint>> runs = publishedRuns(input, raster);
            if (runs.isEmpty()) {
                return List.of(input.source(), input.terminal());
            }
            ArrayList<HydrologyPoint> endpoints = new ArrayList<>(runs.size() * 2);
            for (List<HydrologyPoint> run : runs) {
                endpoints.add(run.getFirst());
                endpoints.add(run.getLast());
            }
            return List.copyOf(endpoints);
        }

        private static List<List<HydrologyPoint>> publishedRuns(
                CourseMorphologyInput input,
                List<HydrologyPoint> raster
        ) {
            ArrayList<List<HydrologyPoint>> runs = new ArrayList<>();
            ArrayList<HydrologyPoint> current = new ArrayList<>();
            for (HydrologyPoint point : raster) {
                if (ownedNear(input.ownedCells(), point.x(), point.z(), 1)) {
                    current.add(point);
                    continue;
                }
                if (current.size() >= 2) {
                    runs.add(List.copyOf(current));
                }
                current.clear();
            }
            if (current.size() >= 2) {
                runs.add(List.copyOf(current));
            }
            return List.copyOf(runs);
        }

        private static int crossSectionWidth(
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

        private static int crossSectionWidth(
                Set<Long> cells,
                double centerX,
                double centerZ,
                double tangentX,
                double tangentZ,
                int maximumWidth
        ) {
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
                int x = (int) StrictMath.round(centerX + perpendicularX * offset);
                int z = (int) StrictMath.round(centerZ + perpendicularZ * offset);
                long packed = RiverFootprint.pack(x, z);
                if (cells.contains(packed)) {
                    sampled.add(packed);
                }
            }
            return sampled.size();
        }

        private static HeadingMetrics headingMetrics(List<HydrologyPoint> points) {
            double routedLength = 0D;
            double lockedLength = 0D;
            double currentRun = 0D;
            double longestRun = 0D;
            for (int index = 1; index < points.size(); index++) {
                HydrologyPoint previous = points.get(index - 1);
                HydrologyPoint point = points.get(index);
                double deltaX = point.x() - previous.x();
                double deltaZ = point.z() - previous.z();
                double length = StrictMath.hypot(deltaX, deltaZ);
                if (length == 0D) {
                    continue;
                }
                routedLength += length;
                double angle = StrictMath.toDegrees(StrictMath.atan2(deltaZ, deltaX));
                double remainder = Math.floorMod((int) StrictMath.round(angle * 1_000_000D), 45_000_000)
                        / 1_000_000D;
                double distance = Math.min(remainder, 45D - remainder);
                if (distance <= GRID_LOCK_TOLERANCE_DEGREES) {
                    lockedLength += length;
                    currentRun += length;
                    longestRun = Math.max(longestRun, currentRun);
                } else {
                    currentRun = 0D;
                }
            }
            return new HeadingMetrics(
                    routedLength == 0D ? 0D : lockedLength / routedLength,
                    longestRun
            );
        }

        private static TurnMetrics turnMetrics(List<HydrologyPoint> points) {
            ArrayList<Double> turns = new ArrayList<>();
            for (int index = 1; index < points.size() - 1; index++) {
                turns.add(turnDegrees(points.get(index - 1), points.get(index), points.get(index + 1)));
            }
            int isolated = 0;
            for (int index = 0; index < turns.size(); index++) {
                double turn = turns.get(index);
                double previous = index == 0 ? 0D : turns.get(index - 1);
                double next = index + 1 == turns.size() ? 0D : turns.get(index + 1);
                if (turn > ISOLATED_TURN_DEGREES
                        && previous <= ISOLATED_NEIGHBOR_TURN_DEGREES
                        && next <= ISOLATED_NEIGHBOR_TURN_DEGREES) {
                    isolated++;
                }
            }
            return new TurnMetrics(isolated, turns);
        }

        private static double percentile95(List<Double> sortedValues) {
            return sortedValues.isEmpty()
                    ? 0D
                    : sortedValues.get(Math.max(0, (int) StrictMath.ceil(sortedValues.size() * 0.95D) - 1));
        }

        private static double turnDegrees(HydrologyPoint previous, HydrologyPoint point, HydrologyPoint next) {
            double incomingX = point.x() - previous.x();
            double incomingZ = point.z() - previous.z();
            double outgoingX = next.x() - point.x();
            double outgoingZ = next.z() - point.z();
            double incomingLength = StrictMath.hypot(incomingX, incomingZ);
            double outgoingLength = StrictMath.hypot(outgoingX, outgoingZ);
            if (incomingLength == 0D || outgoingLength == 0D) {
                return 0D;
            }
            double cosine = (incomingX * outgoingX + incomingZ * outgoingZ)
                    / (incomingLength * outgoingLength);
            return StrictMath.toDegrees(StrictMath.acos(Math.max(-1D, Math.min(1D, cosine))));
        }

        private static List<HydrologyPoint> rasterCenterline(List<HydrologyPoint> points) {
            ArrayList<HydrologyPoint> raster = new ArrayList<>();
            for (int pair = 0; pair < points.size() - 1; pair++) {
                HydrologyPoint start = points.get(pair);
                HydrologyPoint end = points.get(pair + 1);
                int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.z() - start.z()));
                if (steps == 0) {
                    continue;
                }
                int first = raster.isEmpty() ? 0 : 1;
                for (int step = first; step <= steps; step++) {
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

        private static List<HydrologyPoint> smoothCenterline(List<HydrologyPoint> points, int radius) {
            ArrayList<HydrologyPoint> smoothed = new ArrayList<>(points.size());
            for (int index = 0; index < points.size(); index++) {
                int first = Math.max(0, index - radius);
                int last = Math.min(points.size() - 1, index + radius);
                long x = 0L;
                long y = 0L;
                long z = 0L;
                for (int sample = first; sample <= last; sample++) {
                    HydrologyPoint point = points.get(sample);
                    x += point.x();
                    y += point.y();
                    z += point.z();
                }
                int count = last - first + 1;
                HydrologyPoint point = new HydrologyPoint(
                        (int) StrictMath.round(x / (double) count),
                        (int) StrictMath.round(y / (double) count),
                        (int) StrictMath.round(z / (double) count)
                );
                if (smoothed.isEmpty()
                        || point.x() != smoothed.getLast().x()
                        || point.z() != smoothed.getLast().z()) {
                    smoothed.add(point);
                }
            }
            return List.copyOf(smoothed);
        }

        private static List<HydrologyPoint> resampleCenterline(List<HydrologyPoint> points, double spacing) {
            if (points.size() < 2) {
                return points;
            }
            double[] cumulative = new double[points.size()];
            for (int index = 1; index < points.size(); index++) {
                HydrologyPoint previous = points.get(index - 1);
                HydrologyPoint point = points.get(index);
                cumulative[index] = cumulative[index - 1]
                        + StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
            }
            double total = cumulative[cumulative.length - 1];
            if (total <= spacing) {
                return points;
            }
            ArrayList<HydrologyPoint> sampled = new ArrayList<>();
            int segment = 0;
            for (double distance = 0D; distance < total; distance += spacing) {
                while (segment < points.size() - 2 && cumulative[segment + 1] < distance) {
                    segment++;
                }
                HydrologyPoint start = points.get(segment);
                HydrologyPoint end = points.get(segment + 1);
                double length = cumulative[segment + 1] - cumulative[segment];
                double progress = length == 0D ? 0D : (distance - cumulative[segment]) / length;
                HydrologyPoint point = new HydrologyPoint(
                        (int) StrictMath.round(start.x() + (end.x() - start.x()) * progress),
                        (int) StrictMath.round(start.y() + (end.y() - start.y()) * progress),
                        (int) StrictMath.round(start.z() + (end.z() - start.z()) * progress)
                );
                if (sampled.isEmpty()
                        || point.x() != sampled.getLast().x()
                        || point.z() != sampled.getLast().z()) {
                    sampled.add(point);
                }
            }
            HydrologyPoint last = points.getLast();
            if (sampled.isEmpty()
                    || last.x() != sampled.getLast().x()
                    || last.z() != sampled.getLast().z()) {
                sampled.add(last);
            }
            return List.copyOf(sampled);
        }

        private static double routeLength(List<HydrologyPoint> points) {
            double length = 0D;
            for (int index = 1; index < points.size(); index++) {
                HydrologyPoint previous = points.get(index - 1);
                HydrologyPoint point = points.get(index);
                length += StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
            }
            return length;
        }

        private static boolean ownedNear(Set<Long> cells, int x, int z, int radius) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                    if (cells.contains(RiverFootprint.pack(x + deltaX, z + deltaZ))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static int unexpectedLeaves(
                Set<Long> ownedCells,
                List<HydrologyPoint> expectedLeaves,
                List<HydrologyPoint> expectedCenterlines,
                int maximumWidth
        ) {
            if (ownedCells.isEmpty()) {
                return 0;
            }
            HashSet<Long> skeleton = thin(ownedCells);
            pruneShortSpurs(skeleton, Math.max(32, maximumWidth * 8));
            int tolerance = Math.max(64, maximumWidth * 4);
            int centerlineTolerance = Math.max(8, maximumWidth * 2);
            int unexpected = 0;
            for (Set<Long> component : connectedComponents(skeleton)) {
                ArrayList<Long> leaves = new ArrayList<>();
                for (long packed : component) {
                    if (neighborCount(component, packed) <= 1) {
                        leaves.add(packed);
                    }
                }
                if (leaves.size() <= 2) {
                    continue;
                }
                for (long packed : leaves) {
                    int x = RiverFootprint.unpackX(packed);
                    int z = RiverFootprint.unpackZ(packed);
                    boolean expected = false;
                    for (HydrologyPoint endpoint : expectedLeaves) {
                        if (StrictMath.hypot(x - endpoint.x(), z - endpoint.z()) <= tolerance) {
                            expected = true;
                            break;
                        }
                    }
                    if (!expected) {
                        for (HydrologyPoint centerline : expectedCenterlines) {
                            if (StrictMath.hypot(
                                    x - centerline.x(),
                                    z - centerline.z()
                            ) <= centerlineTolerance) {
                                expected = true;
                                break;
                            }
                        }
                    }
                    if (!expected) {
                        unexpected++;
                    }
                }
            }
            return unexpected;
        }

        private static List<Set<Long>> connectedComponents(Set<Long> cells) {
            HashSet<Long> remaining = new HashSet<>(cells);
            ArrayList<Set<Long>> components = new ArrayList<>();
            while (!remaining.isEmpty()) {
                long first = remaining.iterator().next();
                HashSet<Long> component = new HashSet<>();
                ArrayList<Long> pending = new ArrayList<>();
                pending.add(first);
                remaining.remove(first);
                while (!pending.isEmpty()) {
                    long current = pending.removeLast();
                    component.add(current);
                    for (long adjacent : adjacent(remaining, current, Long.MIN_VALUE)) {
                        if (remaining.remove(adjacent)) {
                            pending.add(adjacent);
                        }
                    }
                }
                components.add(Set.copyOf(component));
            }
            return List.copyOf(components);
        }

        private static HashSet<Long> thin(Set<Long> cells) {
            HashSet<Long> skeleton = new HashSet<>(cells);
            boolean changed;
            do {
                changed = thinStep(skeleton, false);
                changed |= thinStep(skeleton, true);
            } while (changed);
            return skeleton;
        }

        private static boolean thinStep(Set<Long> skeleton, boolean second) {
            ArrayList<Long> remove = new ArrayList<>();
            ArrayList<Long> ordered = new ArrayList<>(skeleton);
            ordered.sort(Long::compare);
            for (long packed : ordered) {
                boolean[] neighbors = neighbors(skeleton, packed);
                int count = 0;
                for (boolean neighbor : neighbors) {
                    if (neighbor) {
                        count++;
                    }
                }
                if (count < 2 || count > 6 || transitions(neighbors) != 1) {
                    continue;
                }
                boolean north = neighbors[0];
                boolean east = neighbors[2];
                boolean south = neighbors[4];
                boolean west = neighbors[6];
                boolean firstConstraint = second ? north && east && west : north && east && south;
                boolean secondConstraint = second ? north && south && west : east && south && west;
                if (!firstConstraint && !secondConstraint) {
                    remove.add(packed);
                }
            }
            skeleton.removeAll(remove);
            return !remove.isEmpty();
        }

        private static int transitions(boolean[] neighbors) {
            int transitions = 0;
            for (int index = 0; index < neighbors.length; index++) {
                if (!neighbors[index] && neighbors[(index + 1) % neighbors.length]) {
                    transitions++;
                }
            }
            return transitions;
        }

        private static boolean[] neighbors(Set<Long> cells, long packed) {
            int x = RiverFootprint.unpackX(packed);
            int z = RiverFootprint.unpackZ(packed);
            return new boolean[]{
                    cells.contains(RiverFootprint.pack(x, z - 1)),
                    cells.contains(RiverFootprint.pack(x + 1, z - 1)),
                    cells.contains(RiverFootprint.pack(x + 1, z)),
                    cells.contains(RiverFootprint.pack(x + 1, z + 1)),
                    cells.contains(RiverFootprint.pack(x, z + 1)),
                    cells.contains(RiverFootprint.pack(x - 1, z + 1)),
                    cells.contains(RiverFootprint.pack(x - 1, z)),
                    cells.contains(RiverFootprint.pack(x - 1, z - 1))
            };
        }

        private static int neighborCount(Set<Long> cells, long packed) {
            int count = 0;
            for (boolean neighbor : neighbors(cells, packed)) {
                if (neighbor) {
                    count++;
                }
            }
            return count;
        }

        private static void pruneShortSpurs(Set<Long> skeleton, int maximumLength) {
            boolean changed;
            do {
                changed = false;
                ArrayList<Long> leaves = new ArrayList<>();
                for (long packed : skeleton) {
                    if (neighborCount(skeleton, packed) == 1) {
                        leaves.add(packed);
                    }
                }
                for (long leaf : leaves) {
                    ArrayList<Long> spurPath = new ArrayList<>();
                    long previous = Long.MIN_VALUE;
                    long current = leaf;
                    while (spurPath.size() <= maximumLength) {
                        spurPath.add(current);
                        ArrayList<Long> adjacent = adjacent(skeleton, current, previous);
                        if (adjacent.size() != 1) {
                            if (adjacent.size() > 1 && spurPath.size() <= maximumLength) {
                                skeleton.removeAll(spurPath.subList(0, spurPath.size() - 1));
                                changed = true;
                            }
                            break;
                        }
                        previous = current;
                        current = adjacent.getFirst();
                    }
                }
            } while (changed);
        }

        private static ArrayList<Long> adjacent(Set<Long> cells, long packed, long excluded) {
            int x = RiverFootprint.unpackX(packed);
            int z = RiverFootprint.unpackZ(packed);
            ArrayList<Long> adjacent = new ArrayList<>(3);
            for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                for (int deltaX = -1; deltaX <= 1; deltaX++) {
                    if (deltaX == 0 && deltaZ == 0) {
                        continue;
                    }
                    long neighbor = RiverFootprint.pack(x + deltaX, z + deltaZ);
                    if (neighbor != excluded && cells.contains(neighbor)) {
                        adjacent.add(neighbor);
                    }
                }
            }
            return adjacent;
        }

        private static void writeImage(Map<Long, Long> courseByCell, Path path) throws IOException {
            if (courseByCell.isEmpty()) {
                BufferedImage empty = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                ImageIO.write(empty, "png", path.toFile());
                return;
            }
            int minimumX = Integer.MAX_VALUE;
            int maximumX = Integer.MIN_VALUE;
            int minimumZ = Integer.MAX_VALUE;
            int maximumZ = Integer.MIN_VALUE;
            for (long packed : courseByCell.keySet()) {
                int x = RiverFootprint.unpackX(packed);
                int z = RiverFootprint.unpackZ(packed);
                minimumX = Math.min(minimumX, x);
                maximumX = Math.max(maximumX, x);
                minimumZ = Math.min(minimumZ, z);
                maximumZ = Math.max(maximumZ, z);
            }
            int worldWidth = Math.addExact(Math.subtractExact(maximumX, minimumX), 1);
            int worldHeight = Math.addExact(Math.subtractExact(maximumZ, minimumZ), 1);
            int scale = Math.max(1, (int) StrictMath.ceil(
                    Math.max(worldWidth, worldHeight) / (double) MAXIMUM_IMAGE_DIMENSION
            ));
            int width = Math.max(1, (int) StrictMath.ceil(worldWidth / (double) scale));
            int height = Math.max(1, (int) StrictMath.ceil(worldHeight / (double) scale));
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (Map.Entry<Long, Long> entry : courseByCell.entrySet()) {
                int x = (RiverFootprint.unpackX(entry.getKey()) - minimumX) / scale;
                int z = (RiverFootprint.unpackZ(entry.getKey()) - minimumZ) / scale;
                image.setRGB(x, z, courseColor(entry.getValue()));
            }
            ImageIO.write(image, "png", path.toFile());
        }

        private static int courseColor(long courseId) {
            long mixed = courseId ^ courseId >>> 33;
            int red = 32 + (int) Math.floorMod(mixed, 96L);
            int green = 96 + (int) Math.floorMod(mixed >>> 8, 96L);
            int blue = 160 + (int) Math.floorMod(mixed >>> 16, 96L);
            return 0xff000000 | red << 16 | green << 8 | blue;
        }

        private static String json(SeedMorphologyResult result, String image) {
            StringBuilder output = new StringBuilder();
            output.append("{\n  \"seed\": ").append(result.seed())
                    .append(",\n  \"accepted\": ").append(result.accepted())
                    .append(",\n  \"image\": \"").append(image).append("\"")
                    .append(",\n  \"unexpectedLeaves\": ").append(result.unexpectedLeaves())
                    .append(",\n  \"violations\": ").append(jsonStrings(result.violations()))
                    .append(",\n  \"courses\": [");
            for (int index = 0; index < result.courses().size(); index++) {
                CourseMorphologyResult course = result.courses().get(index);
                if (index > 0) {
                    output.append(',');
                }
                output.append("\n    {\"courseId\": \"")
                        .append(Long.toUnsignedString(course.courseId(), 16))
                        .append("\", \"accepted\": ").append(course.accepted())
                        .append(", \"ownedCells\": ").append(course.ownedCells())
                        .append(", \"routedLength\": ").append(format(course.routedLength()))
                        .append(", \"exposedSurfaceLength\": ").append(format(course.exposedSurfaceLength()))
                        .append(", \"minimumExposedSurfaceLength\": ")
                        .append(course.minimumExposedSurfaceLength())
                        .append(", \"gridLockedFraction\": ").append(format(course.gridLockedFraction()))
                        .append(", \"longestGridLockedRun\": ").append(format(course.longestGridLockedRun()))
                        .append(", \"isolatedTurns\": ").append(course.isolatedTurns())
                        .append(", \"p95TurnDegrees\": ").append(format(course.p95TurnDegrees()))
                        .append(", \"hardEndpointRamps\": ").append(course.hardEndpointRamps())
                        .append(", \"sourceEndpointWidth\": ").append(course.sourceEndpointWidth())
                        .append(", \"sourceCruiseWidth\": ").append(course.sourceCruiseWidth())
                        .append(", \"sourceRampLength\": ").append(format(course.sourceRampLength()))
                        .append(", \"terminalEndpointWidth\": ").append(course.terminalEndpointWidth())
                        .append(", \"terminalCruiseWidth\": ").append(course.terminalCruiseWidth())
                        .append(", \"terminalRampLength\": ").append(format(course.terminalRampLength()))
                        .append(", \"clippedTerminalCaps\": ").append(course.clippedTerminalCaps())
                        .append(", \"terminalCapLength\": ").append(format(course.terminalCapLength()))
                        .append(", \"minimumInteriorWidth\": ").append(course.minimumInteriorWidth())
                        .append(", \"maximumWidthTroughDepthRatio\": ")
                        .append(format(course.maximumWidthTroughDepthRatio()))
                        .append(", \"unexpectedLeaves\": ").append(course.unexpectedLeaves())
                        .append(", \"violations\": ").append(jsonStrings(course.violations()))
                        .append('}');
            }
            output.append("\n  ]\n}\n");
            return output.toString();
        }

        private static String jsonStrings(List<String> values) {
            StringBuilder output = new StringBuilder("[");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                output.append('"').append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
            return output.append(']').toString();
        }

        private static String format(double value) {
            return String.format(Locale.ROOT, "%.6f", value);
        }

        private record HeadingMetrics(double gridLockedFraction, double longestGridLockedRun) {
        }

        private record TurnMetrics(int isolatedTurns, List<Double> degrees) {
            private TurnMetrics {
                degrees = List.copyOf(degrees);
            }
        }

        private record EndpointRampMetrics(
                int hardRamps,
                int sourceEndpointWidth,
                int sourceCruiseWidth,
                double sourceRampLength,
                int terminalEndpointWidth,
                int terminalCruiseWidth,
                double terminalRampLength
        ) {
        }

        private record TerminalCapMetrics(boolean clipped, double length) {
        }

        private record InteriorWidthMetrics(int minimumWidth, double maximumTroughDepthRatio) {
        }

        private static final class CourseAccumulator {
            private final long seed;
            private final long courseId;
            private final HashSet<Long> ownedCells = new HashSet<>();
            private List<HydrologyPoint> centerline = List.of();
            private int maximumWidth = 1;
            private int minimumWidth = 1;
            private int minimumExposedSurfaceLength;
            private double exposedSurfaceLength;
            private boolean complete;

            private CourseAccumulator(long seed, long courseId) {
                this.seed = seed;
                this.courseId = courseId;
            }

            private void register(
                    RiverCourse course,
                    HydrologyTile tile,
                    int configuredMinimumWidth,
                    int configuredMinimumExposedSurfaceLength
            ) {
                List<HydrologyPoint> observedCenterline = surfaceCenterline(course);
                if (observedCenterline.size() > centerline.size()) {
                    centerline = observedCenterline;
                }
                exposedSurfaceLength = Math.max(exposedSurfaceLength, exposedSurfaceLength(course));
                for (HydraulicSegment segment : course.segments()) {
                    if (segment.type().isSurface()) {
                        maximumWidth = Math.max(maximumWidth, segment.width());
                    }
                }
                minimumWidth = Math.max(minimumWidth, configuredMinimumWidth);
                minimumExposedSurfaceLength = Math.max(
                        minimumExposedSurfaceLength,
                        configuredMinimumExposedSurfaceLength
                );
                RiverOutlet outlet = course.outletId().isPresent()
                        ? tile.outlet(course.outletId().getAsLong()).orElse(null)
                        : null;
                boolean reachesOutlet = outlet != null
                        && !course.drainageEdges().isEmpty()
                        && course.drainageEdges().getLast().downstreamNodeId() == outlet.drainageNodeId();
                complete |= reachesOutlet;
            }

            private CourseMorphologyInput input() {
                return new CourseMorphologyInput(
                        seed,
                        courseId,
                        maximumWidth,
                        minimumWidth,
                        minimumExposedSurfaceLength,
                        exposedSurfaceLength,
                        complete,
                        false,
                        centerline,
                        ownedCells
                );
            }

            private static List<HydrologyPoint> surfaceCenterline(RiverCourse course) {
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
                return List.copyOf(points);
            }

            private static double exposedSurfaceLength(RiverCourse course) {
                double length = 0D;
                for (HydraulicSegment segment : course.segments()) {
                    if (!segment.type().isSurface() || segment.type() == HydrologyFeatureType.MOUTH) {
                        continue;
                    }
                    for (int pointIndex = 1; pointIndex < segment.centerline().size(); pointIndex++) {
                        HydrologyPoint previous = segment.centerline().get(pointIndex - 1);
                        HydrologyPoint point = segment.centerline().get(pointIndex);
                        length += StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
                    }
                }
                return length;
            }
        }

        private static final class SeedAccumulator {
            private final long seed;
            private final TreeMap<Long, CourseAccumulator> courses = new TreeMap<>();
            private final HashMap<Long, Long> courseByCell = new HashMap<>();
            private SeedMorphologyResult result;

            private SeedAccumulator(long seed) {
                this.seed = seed;
            }

            private SeedMorphologyResult result() {
                if (result != null) {
                    return result;
                }
                ArrayList<CourseMorphologyInput> inputs = new ArrayList<>(courses.size());
                for (CourseAccumulator course : courses.values()) {
                    if (course.centerline.size() >= 2) {
                        inputs.add(course.input());
                    }
                }
                result = analyzeSeed(seed, inputs);
                return result;
            }
        }
    }

    static final class ShapeMetrics {
        private int surfaceEdges;
        private int visiblyCurvedEdges;
        private int surfaceNodes;
        private int displacedNodes;
        private int renderedSurfaceCourses;
        private int renderedBroadWindows;
        private int renderedCurvedWindows;
        private int renderedAlignedWindows;
        private int renderedTurns;
        private int renderedSharpTurns;
        private int abruptWidthChanges;
        private int surfaceOutletGroups;
        private int completeSurfaceCourses;
        private int nonCompleteSurfaceCourses;
        private int invalidSurfaceOutletGroups;
        private int maximumSurfaceWidth;
        private int configuredMaximumSurfaceWidth;
        private int alignedWindows;
        private int routeWindows;
        private int verticalChanges;
        private int longestCourse;
        private int surfaceTransitions;
        private int fallingWaterfalls;
        private int receivingBasins;
        private int shortTransitionRecoveries;
        private int unsupportedSurfaceTransitions;
        private int unsupportedWaterfalls;
        private int fallingTerrainOwnershipColumns;
        private int oceanTerrainOwnershipColumns;
        private int oceanTerrainMutationColumns;
        private int submergedSurfaceTerrainOwnershipColumns;
        private int submergedSurfaceTerrainMutationColumns;
        private int uncontainedSurfaceBankEdges;
        private int missingSurfaceBankEdges;
        private int lowSurfaceBankEdges;
        private int invalidSurfaceIncisionColumns;
        private int shallowSurfaceIncisionColumns;
        private int excessiveSurfaceIncisionColumns;
        private int maximumUnsupportedDrop;
        private long maximumUnsupportedCourseId;
        private HydrologyFeatureType maximumUnsupportedType;
        private int maximumUnsupportedStartX;
        private int maximumUnsupportedStartZ;
        private int maximumUnsupportedEndX;
        private int maximumUnsupportedEndZ;
        private int maximumUnsupportedUpstreamHead;
        private int maximumUnsupportedDownstreamHead;
        private int maximumUnsupportedUpstreamTerrain;
        private int maximumUnsupportedDownstreamTerrain;
        private int conflictingSurfaceHeadColumns;
        private long wetSurfaceColumns;
        private long wetSurfaceBlocks;
        private double minimumTransitionRecovery = Double.POSITIVE_INFINITY;
        private int minimumTargetPoolLength;
        private int sharpTurns;
        private int routeTurns;
        private double maximumTurnDegrees;
        private final ArrayList<Double> turnAngles = new ArrayList<>();
        private double routedLength;
        private double directLength;
        private double courseRoutedLength;
        private double courseDirectLength;
        private double renderedRoutedLength;
        private double renderedDirectLength;
        private double maximumRenderedDeviationRatio;
        private double maximumRenderedTurnDegrees;
        private long maximumRenderedTurnCourseId;
        private long maximumRenderedTurnSeed;
        private int maximumRenderedTurnTileX;
        private int maximumRenderedTurnTileZ;
        private int maximumRenderedTurnX;
        private int maximumRenderedTurnZ;
        private int maximumRenderedTurnPreviousX;
        private int maximumRenderedTurnPreviousZ;
        private int maximumRenderedTurnNextX;
        private int maximumRenderedTurnNextZ;
        private final ArrayList<Double> renderedTurnAngles = new ArrayList<>();
        private final PublishedMorphologyMetrics publishedMorphology = new PublishedMorphologyMetrics();

        void observe(HydrologyTile tile, HydrologyPlannerSettings settings) {
            publishedMorphology.observe(
                    tile,
                    settings.surface().minimumWidth(),
                    settings.routing().branching().minimumSurfaceCourseLength()
            );
            HydrologyPlannerSettings.Routing routing = settings.routing();
            int sampleSpacing = routing.sampleSpacing();
            minimumTargetPoolLength = settings.hydraulics().minimumTargetPoolLength();
            configuredMaximumSurfaceWidth = settings.surface().maximumWidth();
            Set<Long> ownedCompleteSurfaceCourseIds = ownedCompleteSurfaceCourseIds(tile);
            HashSet<Long> surfaceNodeIds = new HashSet<>();
            for (DrainageEdge edge : tile.edges()) {
                if (edge.contributingSurfaceSources() <= 0) {
                    continue;
                }
                surfaceEdges++;
                surfaceNodeIds.add(edge.upstreamNodeId());
                surfaceNodeIds.add(edge.downstreamNodeId());
                List<HydrologyPoint> centerline = edge.centerline();
                for (int pointIndex = 0; pointIndex < centerline.size() - 1; pointIndex++) {
                    HydrologyPoint start = centerline.get(pointIndex);
                    HydrologyPoint end = centerline.get(pointIndex + 1);
                    routedLength += StrictMath.hypot(end.x() - start.x(), end.z() - start.z());
                    if (start.y() != end.y()) {
                        verticalChanges++;
                    }
                }
                HydrologyPoint start = centerline.getFirst();
                HydrologyPoint end = centerline.getLast();
                directLength += StrictMath.hypot(end.x() - start.x(), end.z() - start.z());
                if (maximumChordDeviationRatio(centerline) >= 0.07D) {
                    visiblyCurvedEdges++;
                }
                for (int pointIndex = 0; pointIndex < centerline.size() - 3; pointIndex++) {
                    HydrologyPoint windowStart = centerline.get(pointIndex);
                    HydrologyPoint windowEnd = centerline.get(pointIndex + 3);
                    int deltaX = windowEnd.x() - windowStart.x();
                    int deltaZ = windowEnd.z() - windowStart.z();
                    routeWindows++;
                    if (deltaX == 0 || deltaZ == 0 || StrictMath.abs(deltaX) == StrictMath.abs(deltaZ)) {
                        alignedWindows++;
                    }
                }
                for (int pointIndex = 1; pointIndex < centerline.size() - 1; pointIndex++) {
                    HydrologyPoint previous = centerline.get(pointIndex - 1);
                    HydrologyPoint current = centerline.get(pointIndex);
                    HydrologyPoint next = centerline.get(pointIndex + 1);
                    double incomingX = current.x() - previous.x();
                    double incomingZ = current.z() - previous.z();
                    double outgoingX = next.x() - current.x();
                    double outgoingZ = next.z() - current.z();
                    double incomingLength = StrictMath.hypot(incomingX, incomingZ);
                    double outgoingLength = StrictMath.hypot(outgoingX, outgoingZ);
                    if (incomingLength == 0D || outgoingLength == 0D) {
                        continue;
                    }
                    double cosine = (incomingX * outgoingX + incomingZ * outgoingZ)
                            / (incomingLength * outgoingLength);
                    double angle = StrictMath.toDegrees(StrictMath.acos(Math.max(-1D, Math.min(1D, cosine))));
                    routeTurns++;
                    turnAngles.add(angle);
                    maximumTurnDegrees = Math.max(maximumTurnDegrees, angle);
                    if (angle > 45D) {
                        sharpTurns++;
                    }
                }
            }
            for (long nodeId : surfaceNodeIds) {
                tile.node(nodeId).ifPresent((DrainageNode node) -> {
                    surfaceNodes++;
                    if (Math.floorMod(node.x(), sampleSpacing) != 0
                            || Math.floorMod(node.z(), sampleSpacing) != 0) {
                        displacedNodes++;
                    }
                });
            }
            for (RiverCourse course : tile.courses()) {
                if (course.type() == RiverCourseType.SURFACE) {
                    longestCourse = Math.max(longestCourse, course.segments().size());
                    observeSurfaceTransitionRecovery(course);
                    observeRenderedSurfaceCourse(tile, course, routing.refinementSpacing());
                    observeSurfaceCenterlineIncision(
                            tile,
                            course,
                            settings,
                            ownedCompleteSurfaceCourseIds
                    );
                    if (!course.drainageEdges().isEmpty()) {
                        HydrologyPoint courseStart = course.drainageEdges().getFirst().centerline().getFirst();
                        HydrologyPoint courseEnd = course.drainageEdges().getLast().centerline().getLast();
                        courseRoutedLength += course.drainageEdges().stream()
                                .mapToDouble((DrainageEdge edge) -> routeLength(edge.centerline()))
                                .sum();
                        courseDirectLength += StrictMath.hypot(
                                courseEnd.x() - courseStart.x(),
                                courseEnd.z() - courseStart.z()
                        );
                    }
                }
                for (HydraulicSegment segment : course.segments()) {
                    if (course.type() == RiverCourseType.SURFACE
                            && segment.type().isSurface()
                            && !segment.receivingPool()) {
                        maximumSurfaceWidth = Math.max(maximumSurfaceWidth, segment.width());
                    }
                    if (segment.type() == HydrologyFeatureType.WATERFALL && segment.fallingFluid()) {
                        fallingWaterfalls++;
                    }
                    if (segment.receivingPool()) {
                        receivingBasins++;
                    }
                    observeSurfaceTerrainSupport(tile, course, segment);
                }
            }
            observeSurfaceOutletCourses(tile);
            SurfaceBankEdgeMetrics bankEdges = surfaceBankEdgeMetrics(
                    tile.footprint(),
                    ownedCompleteSurfaceCourseIds
            );
            uncontainedSurfaceBankEdges += bankEdges.total();
            missingSurfaceBankEdges += bankEdges.missing();
            lowSurfaceBankEdges += bankEdges.low();
            for (HydrologyColumnSample sample : tile.footprint().columns().values()) {
                observeSurfaceHeadConflicts(sample);
                observeFallingTerrainOwnership(sample, ownedCompleteSurfaceCourseIds);
                observeOceanIntegrity(sample, ownedCompleteSurfaceCourseIds);
                HydrologyColumnLayer layer = sample.primarySurfaceFluidLayer().orElse(null);
                if (layer == null || layer.oceanApron() || !layer.channel() || !layer.fluidOwned()) {
                    continue;
                }
                wetSurfaceColumns++;
                wetSurfaceBlocks += Math.max(0, layer.fluidHeadY() - layer.bedY());
            }
        }

        private void observeSurfaceCenterlineIncision(
                HydrologyTile tile,
                RiverCourse course,
                HydrologyPlannerSettings settings,
                Set<Long> scopedCourseIds
        ) {
            if (!scopedCourseIds.contains(course.id())) {
                return;
            }
            for (HydraulicSegment segment : course.segments()) {
                if (!segment.type().isSurface() || segment.type() == HydrologyFeatureType.MOUTH) {
                    continue;
                }
                for (HydrologyPoint point : segment.centerline()) {
                    HydrologyColumnSample sample = tile.footprint().sample(point.x(), point.z()).orElse(null);
                    if (sample == null) {
                        continue;
                    }
                    HydrologyColumnLayer layer = surfaceFluidLayer(sample, course.id());
                    if (layer == null) {
                        continue;
                    }
                    int incision = sample.naturalHeight() - layer.fluidHeadY();
                    if (incision < settings.surface().minimumSurfaceInset()) {
                        shallowSurfaceIncisionColumns++;
                    } else if (incision > settings.surface().maximumIncision()) {
                        excessiveSurfaceIncisionColumns++;
                        invalidSurfaceIncisionColumns++;
                        System.out.printf(
                                "IRIS_HYDROLOGY_PACK_SURFACE_INCISION seed=%d tile_x=%d tile_z=%d "
                                        + "course=%016x segment=%016x type=%s upstream_y=%d downstream_y=%d "
                                        + "receiving_pool=%s x=%d z=%d natural_y=%d head_y=%d "
                                        + "incision=%d maximum=%d%n",
                                tile.worldSeed(),
                                tile.key().tileX(),
                                tile.key().tileZ(),
                                course.id(),
                                segment.id(),
                                segment.type(),
                                segment.upstreamHeadY(),
                                segment.downstreamHeadY(),
                                segment.receivingPool(),
                                point.x(),
                                point.z(),
                                sample.naturalHeight(),
                                layer.fluidHeadY(),
                                incision,
                                settings.surface().maximumIncision()
                        );
                    }
                }
            }
        }

        private HydrologyColumnLayer surfaceFluidLayer(HydrologyColumnSample sample, long courseId) {
            for (HydrologyColumnLayer layer : sample.layers()) {
                if (layer.feature().courseId() == courseId
                        && layer.feature().type().isSurface()
                        && !layer.oceanApron()
                        && layer.channel()
                        && layer.fluidOwned()) {
                    return layer;
                }
            }
            return null;
        }

        void requireOrganicSurfaceNetwork() {
            publishedMorphology.requireValid();
            if (surfaceEdges == 0
                    || surfaceNodes == 0
                    || abruptWidthChanges > 0
                    || invalidSurfaceOutletGroups > 0
                    || nonCompleteSurfaceCourses > 0
                    || maximumSurfaceWidth > configuredMaximumSurfaceWidth
                    || verticalChanges == 0
                    || longestCourse < 16
                    || receivingBasins == 0
                    || shortTransitionRecoveries > 0
                    || unsupportedSurfaceTransitions > 0
                    || unsupportedWaterfalls > 0
                    || fallingTerrainOwnershipColumns > 0
                    || oceanTerrainOwnershipColumns > 0
                    || oceanTerrainMutationColumns > 0
                    || submergedSurfaceTerrainOwnershipColumns > 0
                    || submergedSurfaceTerrainMutationColumns > 0
                    || uncontainedSurfaceBankEdges > 0
                    || invalidSurfaceIncisionColumns > 0
                    || conflictingSurfaceHeadColumns > 0) {
                throw new IllegalStateException("Real-pack hydrology failed organic shape requirements: "
                        + summary());
            }
        }

        String machineLine() {
            return "IRIS_HYDROLOGY_PACK_SHAPE version=14 " + summary()
                    + " " + publishedMorphology.summary();
        }

        void writeReports(File directory) throws IOException {
            publishedMorphology.writeReports(directory);
        }

        private String summary() {
            return String.format(
                    Locale.ROOT,
                    "surface_edges=%d curved_edge_ratio=%.6f surface_nodes=%d displaced_ratio=%.6f aligned_ratio=%.6f "
                            + "sharp_turn_ratio=%.6f p95_turn_degrees=%.6f max_turn_degrees=%.6f "
                            + "sinuosity=%.6f course_sinuosity=%.6f surface_outlet_groups=%d "
                            + "complete_surface_courses=%d non_complete_surface_courses=%d "
                            + "invalid_surface_outlet_groups=%d "
                            + "rendered_courses=%d rendered_curved_window_ratio=%.6f rendered_aligned_window_ratio=%.6f "
                            + "rendered_sharp_turn_ratio=%.6f rendered_p95_turn_degrees=%.6f "
                            + "rendered_max_turn_degrees=%.6f rendered_max_turn_course=%016x "
                            + "rendered_max_turn_owner=%d,%d,%d "
                            + "rendered_max_turn_previous=%d,%d rendered_max_turn_at=%d,%d "
                            + "rendered_max_turn_next=%d,%d rendered_sinuosity=%.6f "
                            + "rendered_max_deviation_ratio=%.6f abrupt_width_changes=%d "
                            + "maximum_surface_width=%d "
                            + "vertical_changes=%d longest_course=%d "
                            + "surface_transitions=%d falling_waterfalls=%d receiving_basins=%d "
                            + "short_transition_recoveries=%d minimum_transition_recovery=%.3f "
                            + "unsupported_surface_transitions=%d unsupported_waterfalls=%d "
                            + "falling_terrain_ownership_columns=%d "
                            + "ocean_terrain_ownership_columns=%d "
                            + "ocean_terrain_mutation_columns=%d "
                            + "submerged_surface_terrain_ownership_columns=%d "
                            + "submerged_surface_terrain_mutation_columns=%d "
                            + "uncontained_surface_bank_edges=%d "
                            + "missing_surface_bank_edges=%d "
                            + "low_surface_bank_edges=%d "
                            + "invalid_surface_incision_columns=%d "
                            + "shallow_surface_incision_columns=%d "
                            + "excessive_surface_incision_columns=%d "
                            + "maximum_unsupported_drop=%d maximum_unsupported_course=%016x "
                            + "maximum_unsupported_type=%s maximum_unsupported_start=%d,%d "
                            + "maximum_unsupported_end=%d,%d maximum_unsupported_heads=%d,%d "
                            + "maximum_unsupported_terrain=%d,%d conflicting_surface_head_columns=%d "
                            + "wet_surface_columns=%d "
                            + "wet_surface_blocks=%d",
                    surfaceEdges,
                    ratio(visiblyCurvedEdges, surfaceEdges),
                    surfaceNodes,
                    ratio(displacedNodes, surfaceNodes),
                    ratio(alignedWindows, routeWindows),
                    ratio(sharpTurns, routeTurns),
                    percentileTurnDegrees(0.95D),
                    maximumTurnDegrees,
                    directLength == 0D ? 0D : routedLength / directLength,
                    courseDirectLength == 0D ? 0D : courseRoutedLength / courseDirectLength,
                    surfaceOutletGroups,
                    completeSurfaceCourses,
                    nonCompleteSurfaceCourses,
                    invalidSurfaceOutletGroups,
                    renderedSurfaceCourses,
                    ratio(renderedCurvedWindows, renderedBroadWindows),
                    ratio(renderedAlignedWindows, renderedBroadWindows),
                    ratio(renderedSharpTurns, renderedTurns),
                    renderedPercentileTurnDegrees(0.95D),
                    maximumRenderedTurnDegrees,
                    maximumRenderedTurnCourseId,
                    maximumRenderedTurnSeed,
                    maximumRenderedTurnTileX,
                    maximumRenderedTurnTileZ,
                    maximumRenderedTurnPreviousX,
                    maximumRenderedTurnPreviousZ,
                    maximumRenderedTurnX,
                    maximumRenderedTurnZ,
                    maximumRenderedTurnNextX,
                    maximumRenderedTurnNextZ,
                    renderedDirectLength == 0D ? 0D : renderedRoutedLength / renderedDirectLength,
                    maximumRenderedDeviationRatio,
                    abruptWidthChanges,
                    maximumSurfaceWidth,
                    verticalChanges,
                    longestCourse,
                    surfaceTransitions,
                    fallingWaterfalls,
                    receivingBasins,
                    shortTransitionRecoveries,
                    finiteOrZero(minimumTransitionRecovery),
                    unsupportedSurfaceTransitions,
                    unsupportedWaterfalls,
                    fallingTerrainOwnershipColumns,
                    oceanTerrainOwnershipColumns,
                    oceanTerrainMutationColumns,
                    submergedSurfaceTerrainOwnershipColumns,
                    submergedSurfaceTerrainMutationColumns,
                    uncontainedSurfaceBankEdges,
                    missingSurfaceBankEdges,
                    lowSurfaceBankEdges,
                    invalidSurfaceIncisionColumns,
                    shallowSurfaceIncisionColumns,
                    excessiveSurfaceIncisionColumns,
                    maximumUnsupportedDrop,
                    maximumUnsupportedCourseId,
                    maximumUnsupportedType == null ? "NONE" : maximumUnsupportedType.name(),
                    maximumUnsupportedStartX,
                    maximumUnsupportedStartZ,
                    maximumUnsupportedEndX,
                    maximumUnsupportedEndZ,
                    maximumUnsupportedUpstreamHead,
                    maximumUnsupportedDownstreamHead,
                    maximumUnsupportedUpstreamTerrain,
                    maximumUnsupportedDownstreamTerrain,
                    conflictingSurfaceHeadColumns,
                    wetSurfaceColumns,
                    wetSurfaceBlocks
            );
        }

        private void observeSurfaceOutletCourses(HydrologyTile tile) {
            HashMap<Long, ArrayList<RiverCourse>> coursesByOutlet = new HashMap<>();
            for (RiverCourse course : tile.courses()) {
                if (course.type() != RiverCourseType.SURFACE || course.outletId().isEmpty()) {
                    continue;
                }
                coursesByOutlet.computeIfAbsent(
                        course.outletId().getAsLong(),
                        (Long ignored) -> new ArrayList<>()
                ).add(course);
            }
            for (Map.Entry<Long, ArrayList<RiverCourse>> entry : coursesByOutlet.entrySet()) {
                surfaceOutletGroups++;
                RiverOutlet outlet = tile.outlet(entry.getKey()).orElse(null);
                if (outlet == null) {
                    nonCompleteSurfaceCourses += entry.getValue().size();
                    invalidSurfaceOutletGroups++;
                    continue;
                }
                int complete = 0;
                int nonComplete = 0;
                for (RiverCourse course : entry.getValue()) {
                    boolean reachesOutlet = !course.drainageEdges().isEmpty()
                            && course.drainageEdges().getLast().downstreamNodeId() == outlet.drainageNodeId();
                    if (reachesOutlet) {
                        complete++;
                    } else {
                        nonComplete++;
                    }
                }
                completeSurfaceCourses += complete;
                nonCompleteSurfaceCourses += nonComplete;
                if (!validSurfaceOutletCourseGroup(complete, nonComplete)) {
                    invalidSurfaceOutletGroups++;
                }
            }
        }

        private Set<Long> ownedCompleteSurfaceCourseIds(HydrologyTile tile) {
            HashSet<Long> completeCourseIds = new HashSet<>();
            for (RiverCourse course : tile.courses()) {
                if (course.type() != RiverCourseType.SURFACE || course.outletId().isEmpty()
                        || course.drainageEdges().isEmpty()) {
                    continue;
                }
                RiverOutlet outlet = tile.outlet(course.outletId().getAsLong()).orElse(null);
                if (outlet != null
                        && course.drainageEdges().getLast().downstreamNodeId() == outlet.drainageNodeId()) {
                    completeCourseIds.add(course.id());
                }
            }
            HashSet<Long> ownedCourseIds = new HashSet<>();
            for (HydrologyColumnSample sample : tile.footprint().columns().values()) {
                HydrologyColumnLayer layer = sample.primarySurfaceFluidLayer().orElse(null);
                if (layer != null && layer.fluidOwned()
                        && completeCourseIds.contains(layer.feature().courseId())) {
                    ownedCourseIds.add(layer.feature().courseId());
                }
            }
            return Set.copyOf(ownedCourseIds);
        }

        private void observeOceanIntegrity(
                HydrologyColumnSample sample,
                Set<Long> ownedCompleteSurfaceCourseIds
        ) {
            boolean ocean = sample.ocean();
            boolean naturallySubmerged = sample.naturalHeight() <= sample.seaLevel();
            if (!ocean && !naturallySubmerged) {
                return;
            }
            boolean scoped = false;
            boolean ownsSurfaceTerrain = false;
            for (HydrologyColumnLayer layer : sample.layers()) {
                if (!ownedCompleteSurfaceCourseIds.contains(layer.feature().courseId())) {
                    continue;
                }
                scoped = true;
                ownsSurfaceTerrain |= layer.feature().type().isSurface()
                        && !layer.oceanApron()
                        && ownsOceanTerrain(layer);
            }
            if (!scoped) {
                return;
            }
            boolean terrainMutation = mutatesOceanTerrain(sample.naturalHeight(), sample.terrainHeight());
            if (ocean) {
                if (ownsSurfaceTerrain) {
                    oceanTerrainOwnershipColumns++;
                }
                if (terrainMutation) {
                    oceanTerrainMutationColumns++;
                }
            }
            if (naturallySubmerged) {
                if (ownsSurfaceTerrain) {
                    submergedSurfaceTerrainOwnershipColumns++;
                }
                if (terrainMutation) {
                    submergedSurfaceTerrainMutationColumns++;
                }
            }
        }

        static int uncontainedSurfaceBankEdges(
                RiverFootprint footprint,
                Set<Long> scopedCourseIds
        ) {
            return surfaceBankEdgeMetrics(footprint, scopedCourseIds).total();
        }

        private static SurfaceBankEdgeMetrics surfaceBankEdgeMetrics(
                RiverFootprint footprint,
                Set<Long> scopedCourseIds
        ) {
            int missing = 0;
            int low = 0;
            int[] offsets = {-1, 0, 1, 0, -1};
            for (HydrologyColumnSample sample : footprint.columns().values()) {
                HydrologyColumnLayer layer = sample.primarySurfaceFluidLayer().orElse(null);
                if (layer == null
                        || layer.oceanApron()
                        || layer.fallingFluid()
                        || !scopedCourseIds.contains(layer.feature().courseId())) {
                    continue;
                }
                for (int offsetIndex = 0; offsetIndex < offsets.length - 1; offsetIndex++) {
                    HydrologyColumnSample neighbor = footprint.sample(
                            sample.x() + offsets[offsetIndex],
                            sample.z() + offsets[offsetIndex + 1]
                    ).orElse(null);
                    if (ownsSurfaceFluid(neighbor, layer.feature().courseId())) {
                        continue;
                    }
                    if (neighbor != null
                            && layer.fluidHeadY() <= neighbor.seaLevel()
                            && (neighbor.ocean() || neighbor.naturalHeight() <= neighbor.seaLevel())) {
                        continue;
                    }
                    if (neighbor == null) {
                        missing++;
                    } else if (neighbor.terrainHeight() <= layer.fluidHeadY()) {
                        low++;
                    }
                }
            }
            return new SurfaceBankEdgeMetrics(missing, low);
        }

        private record SurfaceBankEdgeMetrics(int missing, int low) {
            private int total() {
                return Math.addExact(missing, low);
            }
        }

        private static boolean ownsSurfaceFluid(HydrologyColumnSample sample, long courseId) {
            if (sample == null) {
                return false;
            }
            HydrologyColumnLayer layer = sample.primarySurfaceFluidLayer().orElse(null);
            return layer != null
                    && layer.fluidOwned()
                    && layer.feature().courseId() == courseId;
        }

        private void observeFallingTerrainOwnership(
                HydrologyColumnSample sample,
                Set<Long> ownedCompleteSurfaceCourseIds
        ) {
            for (HydrologyColumnLayer layer : sample.layers()) {
                if (ownedCompleteSurfaceCourseIds.contains(layer.feature().courseId())
                        && layer.fallingFluid()
                        && layer.terrainOwned()) {
                    fallingTerrainOwnershipColumns++;
                    return;
                }
            }
        }

        static boolean ownsOceanTerrain(HydrologyColumnLayer layer) {
            return layer.terrainOwned();
        }

        static boolean mutatesOceanTerrain(int naturalHeight, int terrainHeight) {
            return naturalHeight != terrainHeight;
        }

        static boolean validSurfaceOutletCourseGroup(int complete, int nonComplete) {
            return complete == 1 && nonComplete == 0;
        }

        private void observeRenderedSurfaceCourse(
                HydrologyTile tile,
                RiverCourse course,
                int refinementSpacing
        ) {
            List<HydrologyPoint> points = renderedCenterline(course);
            if (points.size() < 3) {
                return;
            }
            renderedSurfaceCourses++;
            double routeLength = routeLength(points);
            HydrologyPoint start = points.getFirst();
            HydrologyPoint end = points.getLast();
            double directLength = StrictMath.hypot(end.x() - start.x(), end.z() - start.z());
            renderedRoutedLength += routeLength;
            renderedDirectLength += directLength;
            maximumRenderedDeviationRatio = Math.max(
                    maximumRenderedDeviationRatio,
                    maximumChordDeviationRatio(points)
            );
            observeRenderedTurns(
                    tile,
                    course,
                    resampleCenterline(points, Math.max(16D, refinementSpacing * 4D))
            );
            observeBroadRenderedWindows(points, refinementSpacing);
            observeWidthContinuity(course);
        }

        private List<HydrologyPoint> renderedCenterline(RiverCourse course) {
            ArrayList<HydrologyPoint> points = new ArrayList<>();
            for (HydraulicSegment segment : course.segments()) {
                if (!segment.type().isSurface()
                        && segment.type() != HydrologyFeatureType.RIDGE_BORE) {
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
            return List.copyOf(points);
        }

        private void observeRenderedTurns(
                HydrologyTile tile,
                RiverCourse course,
                List<HydrologyPoint> points
        ) {
            for (int pointIndex = 1; pointIndex < points.size() - 1; pointIndex++) {
                HydrologyPoint previous = points.get(pointIndex - 1);
                HydrologyPoint current = points.get(pointIndex);
                HydrologyPoint next = points.get(pointIndex + 1);
                double incomingX = current.x() - previous.x();
                double incomingZ = current.z() - previous.z();
                double outgoingX = next.x() - current.x();
                double outgoingZ = next.z() - current.z();
                double incomingLength = StrictMath.hypot(incomingX, incomingZ);
                double outgoingLength = StrictMath.hypot(outgoingX, outgoingZ);
                if (incomingLength == 0D || outgoingLength == 0D) {
                    continue;
                }
                double cosine = (incomingX * outgoingX + incomingZ * outgoingZ)
                        / (incomingLength * outgoingLength);
                double angle = StrictMath.toDegrees(StrictMath.acos(Math.max(-1D, Math.min(1D, cosine))));
                renderedTurns++;
                renderedTurnAngles.add(angle);
                if (angle > maximumRenderedTurnDegrees) {
                    maximumRenderedTurnDegrees = angle;
                    maximumRenderedTurnCourseId = course.id();
                    maximumRenderedTurnSeed = tile.worldSeed();
                    maximumRenderedTurnTileX = tile.key().tileX();
                    maximumRenderedTurnTileZ = tile.key().tileZ();
                    maximumRenderedTurnX = current.x();
                    maximumRenderedTurnZ = current.z();
                    maximumRenderedTurnPreviousX = previous.x();
                    maximumRenderedTurnPreviousZ = previous.z();
                    maximumRenderedTurnNextX = next.x();
                    maximumRenderedTurnNextZ = next.z();
                }
                if (angle > 40D) {
                    renderedSharpTurns++;
                }
            }
        }

        private List<HydrologyPoint> resampleCenterline(List<HydrologyPoint> points, double spacing) {
            double[] cumulative = new double[points.size()];
            for (int index = 1; index < points.size(); index++) {
                HydrologyPoint previous = points.get(index - 1);
                HydrologyPoint point = points.get(index);
                cumulative[index] = cumulative[index - 1]
                        + StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
            }
            double totalLength = cumulative[cumulative.length - 1];
            if (totalLength <= spacing) {
                return points;
            }
            int samples = Math.max(2, (int) StrictMath.floor(totalLength / spacing));
            ArrayList<HydrologyPoint> sampled = new ArrayList<>(samples + 2);
            int segmentIndex = 0;
            for (double distance = 0D; distance < totalLength; distance += spacing) {
                while (segmentIndex < points.size() - 2 && cumulative[segmentIndex + 1] < distance) {
                    segmentIndex++;
                }
                HydrologyPoint start = points.get(segmentIndex);
                HydrologyPoint end = points.get(segmentIndex + 1);
                double segmentLength = cumulative[segmentIndex + 1] - cumulative[segmentIndex];
                double progress = segmentLength <= 0D
                        ? 0D
                        : (distance - cumulative[segmentIndex]) / segmentLength;
                HydrologyPoint point = new HydrologyPoint(
                        (int) StrictMath.round(start.x() + (end.x() - start.x()) * progress),
                        (int) StrictMath.round(start.y() + (end.y() - start.y()) * progress),
                        (int) StrictMath.round(start.z() + (end.z() - start.z()) * progress)
                );
                if (sampled.isEmpty()
                        || point.x() != sampled.getLast().x()
                        || point.z() != sampled.getLast().z()) {
                    sampled.add(point);
                }
            }
            HydrologyPoint last = points.getLast();
            HydrologyPoint sampledLast = sampled.isEmpty() ? null : sampled.getLast();
            double remainingLength = sampledLast == null
                    ? Double.POSITIVE_INFINITY
                    : StrictMath.hypot(last.x() - sampledLast.x(), last.z() - sampledLast.z());
            if (remainingLength >= spacing * 0.5D) {
                sampled.add(last);
            }
            return List.copyOf(sampled);
        }

        private void observeBroadRenderedWindows(List<HydrologyPoint> points, int refinementSpacing) {
            double[] cumulative = new double[points.size()];
            for (int index = 1; index < points.size(); index++) {
                HydrologyPoint previous = points.get(index - 1);
                HydrologyPoint point = points.get(index);
                cumulative[index] = cumulative[index - 1]
                        + StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
            }
            double windowLength = Math.max(64D, refinementSpacing * 16D);
            double stride = windowLength / 3D;
            int startIndex = 0;
            for (double startDistance = 0D;
                 startDistance + windowLength <= cumulative[cumulative.length - 1];
                 startDistance += stride) {
                while (startIndex < cumulative.length - 2 && cumulative[startIndex] < startDistance) {
                    startIndex++;
                }
                int endIndex = startIndex + 1;
                double endDistance = startDistance + windowLength;
                while (endIndex < cumulative.length - 1 && cumulative[endIndex] < endDistance) {
                    endIndex++;
                }
                if (endIndex - startIndex < 2) {
                    continue;
                }
                List<HydrologyPoint> window = points.subList(startIndex, endIndex + 1);
                double deviationRatio = maximumChordDeviationRatio(window);
                maximumRenderedDeviationRatio = Math.max(maximumRenderedDeviationRatio, deviationRatio);
                renderedBroadWindows++;
                if (deviationRatio >= 0.08D) {
                    renderedCurvedWindows++;
                }
                HydrologyPoint start = window.getFirst();
                HydrologyPoint end = window.getLast();
                int deltaX = end.x() - start.x();
                int deltaZ = end.z() - start.z();
                if (deltaX == 0 || deltaZ == 0 || StrictMath.abs(deltaX) == StrictMath.abs(deltaZ)) {
                    renderedAlignedWindows++;
                }
            }
        }

        private void observeWidthContinuity(RiverCourse course) {
            HydraulicSegment previous = null;
            for (HydraulicSegment segment : course.segments()) {
                boolean flatSurface = segment.type().isSurface()
                        && segment.drop() == 0
                        && !segment.fallingFluid();
                if (!flatSurface) {
                    previous = null;
                    continue;
                }
                if (previous != null) {
                    int maximumWidth = Math.max(previous.width(), segment.width());
                    int permittedChange = Math.max(2, (int) StrictMath.ceil(maximumWidth * 0.35D));
                    if (StrictMath.abs(previous.width() - segment.width()) > permittedChange) {
                        abruptWidthChanges++;
                    }
                }
                previous = segment;
            }
        }

        private double maximumChordDeviationRatio(List<HydrologyPoint> points) {
            if (points.size() < 3) {
                return 0D;
            }
            HydrologyPoint start = points.getFirst();
            HydrologyPoint end = points.getLast();
            double chordX = end.x() - start.x();
            double chordZ = end.z() - start.z();
            double chordLength = StrictMath.hypot(chordX, chordZ);
            if (chordLength == 0D) {
                return 0D;
            }
            double maximumDeviation = 0D;
            for (int index = 1; index < points.size() - 1; index++) {
                HydrologyPoint point = points.get(index);
                double localX = point.x() - start.x();
                double localZ = point.z() - start.z();
                double deviation = StrictMath.abs(chordX * localZ - chordZ * localX) / chordLength;
                maximumDeviation = Math.max(maximumDeviation, deviation);
            }
            return maximumDeviation / chordLength;
        }

        private double routeLength(List<HydrologyPoint> points) {
            double length = 0D;
            for (int index = 1; index < points.size(); index++) {
                HydrologyPoint previous = points.get(index - 1);
                HydrologyPoint point = points.get(index);
                length += StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
            }
            return length;
        }

        private void observeSurfaceHeadConflicts(HydrologyColumnSample sample) {
            HashMap<Long, Integer> headsByCourse = new HashMap<>();
            for (HydrologyColumnLayer layer : sample.layers()) {
                if (!layer.feature().type().isSurface()
                        || !layer.channel()
                        || !layer.connectedFluid()
                        || !layer.fluidOwned()
                        || layer.fallingFluid()) {
                    continue;
                }
                long courseId = layer.feature().courseId();
                Integer previousHead = headsByCourse.putIfAbsent(courseId, layer.fluidHeadY());
                if (previousHead != null && previousHead != layer.fluidHeadY()) {
                    conflictingSurfaceHeadColumns++;
                    return;
                }
            }
        }

        private void observeSurfaceTerrainSupport(
                HydrologyTile tile,
                RiverCourse course,
                HydraulicSegment segment
        ) {
            if (!segment.type().isSurface() || segment.drop() <= 0) {
                return;
            }
            if (!segment.fallingFluid()) {
                observeGradedSurfaceTerrainSupport(tile, course, segment);
                return;
            }
            HydrologyColumnSample upstream = tile.footprint()
                    .sample(segment.start().x(), segment.start().z())
                    .orElse(null);
            HydrologyColumnSample downstream = tile.footprint()
                    .sample(segment.end().x(), segment.end().z())
                    .orElse(null);
            if (upstream == null || downstream == null) {
                unsupportedSurfaceTransitions++;
                observeMaximumUnsupportedTransition(
                        course,
                        segment,
                        Integer.MIN_VALUE,
                        Integer.MIN_VALUE
                );
                if (segment.type() == HydrologyFeatureType.WATERFALL) {
                    unsupportedWaterfalls++;
                }
                return;
            }
            RiverOutlet outlet = course.outletId().isPresent()
                    ? tile.outlet(course.outletId().getAsLong()).orElse(null)
                    : null;
            int downstreamNaturalHeight = isCoastalOutletDrop(course, segment)
                    || outlet != null
                    && outlet.directOcean()
                    && segment.end().x() == outlet.connectionPoint().x()
                    && segment.end().z() == outlet.connectionPoint().z()
                    ? downstream.seaLevel()
                    : downstream.naturalHeight();
            long naturalLoss = (long) upstream.naturalHeight() - downstreamNaturalHeight;
            if (naturalLoss < segment.drop()) {
                unsupportedSurfaceTransitions++;
                observeMaximumUnsupportedTransition(
                        course,
                        segment,
                        upstream.naturalHeight(),
                        downstreamNaturalHeight
                );
                if (segment.type() == HydrologyFeatureType.WATERFALL) {
                    unsupportedWaterfalls++;
                }
            }
        }

        private boolean isCoastalOutletDrop(RiverCourse course, HydraulicSegment segment) {
            int segmentIndex = course.segments().indexOf(segment);
            return segmentIndex >= 0
                    && segmentIndex + 1 < course.segments().size()
                    && course.segments().get(segmentIndex + 1).type() == HydrologyFeatureType.MOUTH;
        }

        private void observeGradedSurfaceTerrainSupport(
                HydrologyTile tile,
                RiverCourse course,
                HydraulicSegment segment
        ) {
            RiverOutlet outlet = course.outletId().isPresent()
                    ? tile.outlet(course.outletId().getAsLong()).orElse(null)
                    : null;
            for (HydrologyPoint point : segment.centerline()) {
                HydrologyColumnSample sample = tile.footprint()
                        .sample(point.x(), point.z())
                        .orElse(null);
                boolean directMouthEndpoint = outlet != null
                        && outlet.directOcean()
                        && point.x() == outlet.landwardPoint().x()
                        && point.z() == outlet.landwardPoint().z()
                        && point.y() == sampleSeaLevel(sample);
                if (sample != null && (point.y() < sample.naturalHeight() || directMouthEndpoint)) {
                    continue;
                }
                unsupportedSurfaceTransitions++;
                observeMaximumUnsupportedTransition(
                        course,
                        segment,
                        sample == null ? Integer.MIN_VALUE : sample.naturalHeight(),
                        sample == null ? Integer.MIN_VALUE : sample.naturalHeight()
                );
                return;
            }
        }

        private int sampleSeaLevel(HydrologyColumnSample sample) {
            return sample == null ? Integer.MIN_VALUE : sample.seaLevel();
        }

        private void observeMaximumUnsupportedTransition(
                RiverCourse course,
                HydraulicSegment segment,
                int upstreamTerrain,
                int downstreamTerrain
        ) {
            if (segment.drop() <= maximumUnsupportedDrop) {
                return;
            }
            maximumUnsupportedDrop = segment.drop();
            maximumUnsupportedCourseId = course.id();
            maximumUnsupportedType = segment.type();
            maximumUnsupportedStartX = segment.start().x();
            maximumUnsupportedStartZ = segment.start().z();
            maximumUnsupportedEndX = segment.end().x();
            maximumUnsupportedEndZ = segment.end().z();
            maximumUnsupportedUpstreamHead = segment.upstreamHeadY();
            maximumUnsupportedDownstreamHead = segment.downstreamHeadY();
            maximumUnsupportedUpstreamTerrain = upstreamTerrain;
            maximumUnsupportedDownstreamTerrain = downstreamTerrain;
        }

        private void observeSurfaceTransitionRecovery(RiverCourse course) {
            double recovery = Double.POSITIVE_INFINITY;
            boolean transitionActive = false;
            for (int segmentIndex = 0; segmentIndex < course.segments().size(); segmentIndex++) {
                HydraulicSegment segment = course.segments().get(segmentIndex);
                boolean surfaceDrop = segment.type().isSurface() && segment.type().isDrop();
                boolean coastalOutletDrop = surfaceDrop
                        && segmentIndex + 1 < course.segments().size()
                        && course.segments().get(segmentIndex + 1).type() == HydrologyFeatureType.MOUTH;
                if (coastalOutletDrop) {
                    continue;
                }
                if (!segment.type().isSurface()) {
                    recovery = Double.POSITIVE_INFINITY;
                    transitionActive = false;
                    continue;
                }
                if (surfaceDrop) {
                    if (!transitionActive) {
                        surfaceTransitions++;
                        if (Double.isFinite(recovery)) {
                            minimumTransitionRecovery = Math.min(minimumTransitionRecovery, recovery);
                            if (recovery < minimumTargetPoolLength) {
                                shortTransitionRecoveries++;
                            }
                        }
                    }
                    recovery = 0D;
                    transitionActive = !segment.receivingPool();
                    continue;
                }
                if (transitionActive && segment.receivingPool()) {
                    transitionActive = false;
                } else if (!transitionActive && Double.isFinite(recovery)) {
                    recovery += segmentLength(segment);
                }
            }
        }

        private double segmentLength(HydraulicSegment segment) {
            double length = 0D;
            for (int pointIndex = 1; pointIndex < segment.centerline().size(); pointIndex++) {
                HydrologyPoint previous = segment.centerline().get(pointIndex - 1);
                HydrologyPoint current = segment.centerline().get(pointIndex);
                length += StrictMath.hypot(current.x() - previous.x(), current.z() - previous.z());
            }
            return length;
        }

        private double finiteOrZero(double value) {
            return Double.isFinite(value) ? value : 0D;
        }

        private static double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0D : numerator / (double) denominator;
        }

        private double percentileTurnDegrees(double percentile) {
            if (turnAngles.isEmpty()) {
                return 0D;
            }
            ArrayList<Double> ordered = new ArrayList<>(turnAngles);
            ordered.sort(Double::compare);
            int index = (int) StrictMath.ceil(percentile * ordered.size()) - 1;
            return ordered.get(Math.max(0, Math.min(index, ordered.size() - 1)));
        }

        private double renderedPercentileTurnDegrees(double percentile) {
            if (renderedTurnAngles.isEmpty()) {
                return 0D;
            }
            ArrayList<Double> ordered = new ArrayList<>(renderedTurnAngles);
            ordered.sort(Double::compare);
            int index = (int) StrictMath.ceil(percentile * ordered.size()) - 1;
            return ordered.get(Math.max(0, Math.min(index, ordered.size() - 1)));
        }
    }

    record SeedSummary(
            long seed,
            long hydrologySeed,
            int tiles,
            int courses,
            int surfaceCourses,
            int undergroundCourses,
            int deepCourses,
            int features,
            int coveragePairs,
            long engineReadyNanos
    ) {
    }

    record ProbeResult(
            String status,
            String dimensionKey,
            int seeds,
            int tilesPerSeed,
            int totalTiles,
            int minimumTileX,
            int maximumTileX,
            int minimumTileZ,
            int maximumTileZ,
            int observedCoveragePairs,
            int requiredCoveragePairs,
            int configuredRequirements,
            int generatedChunks,
            int verifiedFeatures,
            String signature
    ) {
        String machineLine() {
            return String.format(
                    Locale.ROOT,
                    "IRIS_HYDROLOGY_PACK_RESULT version=2 status=%s dimension=%s seeds=%d "
                            + "tiles_per_seed=%d total_tiles=%d tile_x=%d..%d tile_z=%d..%d "
                            + "observed_pairs=%d required_pairs=%d configured_requirements=%d "
                            + "generated_chunks=%d verified_features=%d signature=%s",
                    status,
                    dimensionKey,
                    seeds,
                    tilesPerSeed,
                    totalTiles,
                    minimumTileX,
                    maximumTileX,
                    minimumTileZ,
                    maximumTileZ,
                    observedCoveragePairs,
                    requiredCoveragePairs,
                    configuredRequirements,
                    generatedChunks,
                    verifiedFeatures,
                    signature
            );
        }
    }

    public static void main(String[] arguments) {
        ProbeConfiguration configuration;
        try {
            configuration = ProbeConfiguration.parse(arguments);
        } catch (Throwable failure) {
            System.out.println(LOG_PREFIX + " FAIL: " + failure.getMessage());
            failure.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        try {
            ProbeResult result = run(configuration);
            System.out.println(result.machineLine());
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println(LOG_PREFIX + " FAIL: probe execution failed");
            failure.printStackTrace(System.out);
            ProbeResult failed = new ProbeResult(
                    "FAIL",
                    configuration.dimensionKey(),
                    configuration.seeds().size(),
                    configuration.tilesPerSeed(),
                    configuration.tilesPerSeed() * configuration.seeds().size(),
                    configuration.minimumTileX(),
                    configuration.maximumTileX(),
                    configuration.minimumTileZ(),
                    configuration.maximumTileZ(),
                    0,
                    configuration.requiredCoverage().size(),
                    0,
                    0,
                    0,
                    "unavailable"
            );
            System.out.println(failed.machineLine());
            System.exit(1);
        }
    }

    static List<CoverageSelector> missingCoverage(
            Set<CoverageKey> observed,
            List<CoverageSelector> required
    ) {
        ArrayList<CoverageSelector> missing = new ArrayList<>();
        for (CoverageSelector selector : required) {
            boolean matched = false;
            for (CoverageKey coverage : observed) {
                if (selector.matches(coverage)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                missing.add(selector);
            }
        }
        return List.copyOf(missing);
    }

    private static ProbeResult run(ProbeConfiguration configuration) throws Exception {
        System.out.println(LOG_PREFIX + " pack: " + configuration.packSource().getAbsolutePath());
        System.out.println(LOG_PREFIX + " dimension: " + configuration.dimensionKey());
        System.out.println(LOG_PREFIX + " seeds: " + configuration.seeds());
        System.out.println(LOG_PREFIX + " tile bounds: " + configuration.minimumTileX() + ".."
                + configuration.maximumTileX() + "," + configuration.minimumTileZ() + ".."
                + configuration.maximumTileZ());
        System.out.println(LOG_PREFIX + " required coverage: "
                + configuration.requiredCoverage().stream().map(CoverageSelector::label).toList());
        System.out.println(LOG_PREFIX + " studio: " + configuration.studio());

        TreeMap<CoverageKey, Integer> aggregateCounts = new TreeMap<>();
        TreeMap<RejectionKey, Integer> rejectionCounts = new TreeMap<>();
        TreeMap<SeedCoverageKey, ObservedFeature> coordinates = new TreeMap<>();
        LinkedHashSet<ConfiguredCoverage> requiredConfiguredCoverage = new LinkedHashSet<>();
        LinkedHashSet<ConfiguredCoverage> observedConfiguredCoverage = new LinkedHashSet<>();
        LinkedHashSet<CoverageSelector> verifiedSelectors = new LinkedHashSet<>();
        LinkedHashSet<SeedChunk> generatedChunks = new LinkedHashSet<>();
        ArrayList<SeedSummary> summaries = new ArrayList<>(configuration.seeds().size());
        MessageDigest signature = sha256();
        ShapeMetrics shapeMetrics = new ShapeMetrics();

        try (RealPackProbeSupport.Workspace workspace = RealPackProbeSupport.openWorkspace(
                configuration.packSource(), configuration.dimensionKey(), LOG_PREFIX)) {
            for (long seed : configuration.seeds()) {
                scanSeed(
                        configuration,
                        workspace,
                        seed,
                        aggregateCounts,
                        rejectionCounts,
                        coordinates,
                        requiredConfiguredCoverage,
                        observedConfiguredCoverage,
                        verifiedSelectors,
                        generatedChunks,
                        summaries,
                        signature,
                        shapeMetrics
                );
            }
        }

        File morphologyReports = new File(
                new File(System.getProperty("user.dir"), "probe/build/reports"),
                "hydrology"
        );
        shapeMetrics.writeReports(morphologyReports);
        System.out.println(LOG_PREFIX + " morphology reports: " + morphologyReports.getAbsolutePath());
        System.out.println(shapeMetrics.machineLine());

        for (Map.Entry<SeedCoverageKey, ObservedFeature> entry : coordinates.entrySet()) {
            ObservedFeature observed = entry.getValue();
            HydrologyFeatureRef feature = observed.feature();
            System.out.printf(
                    Locale.ROOT,
                    "IRIS_HYDROLOGY_PACK_COORDINATE seed=%d tile_x=%d tile_z=%d type=%s profile=%s "
                            + "x=%d local_y=%d world_y=%d z=%d id=%016x%n",
                    observed.seed(),
                    observed.tile().tileX(),
                    observed.tile().tileZ(),
                    feature.type().name(),
                    observed.profileKey(),
                    feature.x(),
                    feature.y(),
                    Math.addExact(feature.y(), observed.minimumWorldY()),
                    feature.z(),
                    feature.id()
            );
        }
        for (Map.Entry<RejectionKey, Integer> entry : rejectionCounts.entrySet()) {
            RejectionKey rejection = entry.getKey();
            System.out.printf(
                    Locale.ROOT,
                    "IRIS_HYDROLOGY_PACK_REJECTION kind=%s type=%s rejection=%s count=%d%n",
                    rejection.kind().name(),
                    rejection.type().name(),
                    rejection.rejection().name(),
                    entry.getValue()
            );
        }
        ArrayList<ConfiguredCoverage> orderedConfiguredCoverage = new ArrayList<>(requiredConfiguredCoverage);
        orderedConfiguredCoverage.sort(Comparator.naturalOrder());
        for (ConfiguredCoverage requirement : orderedConfiguredCoverage) {
            System.out.println("IRIS_HYDROLOGY_PACK_CONFIGURED requirement=" + requirement.label());
        }
        for (SeedSummary summary : summaries) {
            System.out.printf(
                    Locale.ROOT,
                    "IRIS_HYDROLOGY_PACK_SEED seed=%d hydrology_seed=%d tiles=%d courses=%d "
                            + "surface_courses=%d underground_courses=%d deep_courses=%d features=%d "
                            + "pairs=%d engine_ready_ms=%.3f%n",
                    summary.seed(),
                    summary.hydrologySeed(),
                    summary.tiles(),
                    summary.courses(),
                    summary.surfaceCourses(),
                    summary.undergroundCourses(),
                    summary.deepCourses(),
                    summary.features(),
                    summary.coveragePairs(),
                    summary.engineReadyNanos() / 1_000_000D
            );
        }
        shapeMetrics.requireOrganicSurfaceNetwork();

        List<CoverageSelector> missing = missingCoverage(aggregateCounts.keySet(), configuration.requiredCoverage());
        for (CoverageSelector selector : missing) {
            System.out.println("IRIS_HYDROLOGY_PACK_MISSING selector=" + selector.label());
        }
        List<ConfiguredCoverage> missingConfigured = missingConfiguredCoverage(
                observedConfiguredCoverage, requiredConfiguredCoverage);
        for (ConfiguredCoverage requirement : missingConfigured) {
            System.out.println("IRIS_HYDROLOGY_PACK_MISSING configured=" + requirement.label());
        }
        if (!missing.isEmpty() || !missingConfigured.isEmpty()) {
            throw new IllegalStateException("Missing required accepted hydrology coverage: selectors="
                    + missing.stream().map(CoverageSelector::label).toList()
                    + ", configured=" + missingConfigured.stream().map(ConfiguredCoverage::label).toList());
        }
        ArrayList<CoverageSelector> unverified = new ArrayList<>();
        for (CoverageSelector selector : configuration.requiredCoverage()) {
            if (!verifiedSelectors.contains(selector)) {
                unverified.add(selector);
                System.out.println("IRIS_HYDROLOGY_PACK_UNVERIFIED selector=" + selector.label());
            }
        }
        if (!unverified.isEmpty()) {
            throw new IllegalStateException("Accepted hydrology coverage had no verifiable generated witness: "
                    + unverified.stream().map(CoverageSelector::label).toList());
        }

        return new ProbeResult(
                "PASS",
                configuration.dimensionKey(),
                configuration.seeds().size(),
                configuration.tilesPerSeed(),
                configuration.tilesPerSeed() * configuration.seeds().size(),
                configuration.minimumTileX(),
                configuration.maximumTileX(),
                configuration.minimumTileZ(),
                configuration.maximumTileZ(),
                aggregateCounts.size(),
                configuration.requiredCoverage().size(),
                requiredConfiguredCoverage.size(),
                generatedChunks.size(),
                verifiedSelectors.size(),
                HexFormat.of().formatHex(signature.digest())
        );
    }

    private static void scanSeed(
            ProbeConfiguration configuration,
            RealPackProbeSupport.Workspace workspace,
            long seed,
            Map<CoverageKey, Integer> aggregateCounts,
            Map<RejectionKey, Integer> rejectionCounts,
            Map<SeedCoverageKey, ObservedFeature> coordinates,
            Set<ConfiguredCoverage> requiredConfiguredCoverage,
            Set<ConfiguredCoverage> observedConfiguredCoverage,
            Set<CoverageSelector> verifiedSelectors,
            Set<SeedChunk> generatedChunks,
            List<SeedSummary> summaries,
            MessageDigest signature,
            ShapeMetrics shapeMetrics
    ) throws Exception {
        int courses = 0;
        int surfaceCourses = 0;
        int undergroundCourses = 0;
        int deepCourses = 0;
        int features = 0;
        long hydrologySeed;
        long engineReadyNanos;
        Set<CoverageKey> seedCoverage = new HashSet<>();
        LinkedHashMap<CoverageSelector, GeneratedWitness> pendingWitnesses = new LinkedHashMap<>();
        try (RealPackProbeSupport.EngineSession session = workspace.openEngine(
                seed, configuration.studio(), "coverage-" + seed)) {
            Engine engine = session.engine();
            IrisHydrologyRuntime runtime = engine.getComplex().getHydrologyRuntime();
            if (runtime == null) {
                throw new IllegalStateException("Dimension '" + configuration.dimensionKey()
                        + "' has no active hydrology runtime.");
            }
            validateRequiredProfiles(runtime, configuration.requiredCoverage());
            requiredConfiguredCoverage.addAll(configuredCoverage(runtime));
            validateTileBlockBounds(runtime, configuration);
            for (long tileZ = configuration.minimumTileZ(); tileZ <= configuration.maximumTileZ(); tileZ++) {
                int completedRows = Math.toIntExact(tileZ - configuration.minimumTileZ());
                System.out.printf(
                        Locale.ROOT,
                        "%s scan seed=%d row=%d/%d tile_z=%d%n",
                        LOG_PREFIX,
                        seed,
                        completedRows + 1,
                        configuration.maximumTileZ() - configuration.minimumTileZ() + 1,
                        tileZ
                );
                for (long tileX = configuration.minimumTileX(); tileX <= configuration.maximumTileX(); tileX++) {
                    HydrologyTileKey tileKey = new HydrologyTileKey((int) tileX, (int) tileZ);
                    HydrologyTile tile = runtime.tile(tileKey);
                    shapeMetrics.observe(tile, runtime.settings());
                    List<HydrologyColumnSample> orderedColumns = null;
                    courses += tile.courses().size();
                    HashMap<Long, String> profilesByCourse = new HashMap<>(tile.courses().size());
                    for (RiverCourse course : tile.courses()) {
                        profilesByCourse.put(course.id(), course.profileKey());
                        observedConfiguredCoverage.add(courseCoverage(course));
                        if (course.type() == RiverCourseType.SURFACE) {
                            surfaceCourses++;
                        } else if (course.type() == RiverCourseType.UNDERGROUND) {
                            undergroundCourses++;
                        } else if (course.type() == RiverCourseType.DEEP_FLUID) {
                            deepCourses++;
                        }
                    }
                    for (HydrologyDiagnosticCandidate candidate : tile.diagnosticCandidates()) {
                        RejectionKey rejection = new RejectionKey(
                                candidate.kind(),
                                candidate.projectedType(),
                                candidate.rejection()
                        );
                        rejectionCounts.merge(rejection, 1, Integer::sum);
                        if (surfaceBuildRejection(candidate.rejection())) {
                            System.out.printf(
                                    Locale.ROOT,
                                    "IRIS_HYDROLOGY_PACK_SURFACE_BUILD_REJECTION seed=%d tile_x=%d tile_z=%d "
                                            + "x=%d y=%d z=%d id=%016x rejection=%s%n",
                                    seed,
                                    tileKey.tileX(),
                                    tileKey.tileZ(),
                                    candidate.point().x(),
                                    candidate.point().y(),
                                    candidate.point().z(),
                                    candidate.id(),
                                    candidate.rejection().name()
                            );
                        }
                    }
                    ArrayList<HydrologyFeatureRef> orderedFeatures = new ArrayList<>(tile.features());
                    orderedFeatures.sort(Comparator.comparingLong(HydrologyFeatureRef::id));
                    for (HydrologyFeatureRef feature : orderedFeatures) {
                        String profileKey = profilesByCourse.get(feature.courseId());
                        if (profileKey == null) {
                            throw new IllegalStateException("Accepted hydrology feature references unknown course "
                                    + Long.toUnsignedString(feature.courseId(), 16) + ".");
                        }
                        CoverageKey coverage = new CoverageKey(feature.type(), profileKey);
                        ObservedFeature observedFeature = new ObservedFeature(
                                seed,
                                tileKey,
                                profileKey,
                                feature,
                                engine.getWorld().minHeight()
                        );
                        aggregateCounts.merge(coverage, 1, Integer::sum);
                        seedCoverage.add(coverage);
                        coordinates.putIfAbsent(
                                new SeedCoverageKey(seed, coverage),
                                observedFeature
                        );
                        for (CoverageSelector selector : configuration.requiredCoverage()) {
                            if (!selector.type().isSurface() && verifiedSelectors.contains(selector)
                                    || pendingWitnesses.containsKey(selector)
                                    || !selector.matches(coverage)) {
                                continue;
                            }
                            if (orderedColumns == null) {
                                orderedColumns = orderedFootprintColumns(tile);
                            }
                            findGeneratedWitness(runtime, tile, orderedColumns, observedFeature, selector)
                                    .ifPresent((GeneratedWitness witness) -> pendingWitnesses.put(selector, witness));
                        }
                        updateSignature(signature, seed, tileKey, profileKey, feature);
                        features++;
                    }
                }
                int loadedBefore = engine.getMantle().getLoadedRegionCount();
                engine.getMantle().trim(0L, 0);
                int unloaded = engine.getMantle().unloadTectonicPlate(0);
                int loadedAfter = engine.getMantle().getLoadedRegionCount();
                System.out.printf(
                        Locale.ROOT,
                        "%s mantle seed=%d tile_z=%d loaded_before=%d unloaded=%d loaded_after=%d%n",
                        LOG_PREFIX,
                        seed,
                        tileZ,
                        loadedBefore,
                        unloaded,
                        loadedAfter
                );
            }
            hydrologySeed = engine.getSeedManager().getBodies();
            engineReadyNanos = session.readyNanos();
            List<Throwable> reports = RealPackProbeSupport.settleAndDrain();
            if (!reports.isEmpty()) {
                RealPackProbeSupport.printReports(LOG_PREFIX, "seed " + seed + " planning reports", reports);
                throw reportedFailure("Hydrology coverage scan", reports);
            }
        }
        List<CoverageSelector> missingSeedSurfaceCoverage = missingCoverage(
                seedCoverage,
                configuration.requiredCoverage().stream()
                        .filter((CoverageSelector selector) -> selector.type().isSurface())
                        .toList()
        );
        if (!missingSeedSurfaceCoverage.isEmpty()) {
            throw new IllegalStateException("Seed " + seed + " is missing required surface coverage: "
                    + missingSeedSurfaceCoverage.stream().map(CoverageSelector::label).toList()
                    + "; rejections=" + rejectionCounts);
        }
        for (CoverageSelector selector : configuration.requiredCoverage()) {
            if (selector.type().isSurface() && !pendingWitnesses.containsKey(selector)) {
                throw new IllegalStateException("Seed " + seed
                        + " has no generated witness for required surface coverage " + selector.label() + ".");
            }
        }
        verifyGeneratedWitnesses(
                configuration,
                workspace,
                seed,
                pendingWitnesses,
                verifiedSelectors,
                generatedChunks,
                signature
        );
        summaries.add(new SeedSummary(
                seed,
                hydrologySeed,
                configuration.tilesPerSeed(),
                courses,
                surfaceCourses,
                undergroundCourses,
                deepCourses,
                features,
                seedCoverage.size(),
                engineReadyNanos
        ));
    }

    private static boolean surfaceBuildRejection(HydrologyCandidateRejection rejection) {
        return switch (rejection) {
            case SURFACE_HEAD_RANGE,
                 SURFACE_HEAD_DISTRIBUTION,
                 SURFACE_RIDGE_TUNNEL_LIMIT,
                 SURFACE_DROP_UNSUPPORTED,
                 SURFACE_CORRIDOR_UNSUPPORTED,
                 SURFACE_TRANSITION_UNSUPPORTED,
                 SURFACE_SEGMENT_UNSUPPORTED,
                 SURFACE_SINKHOLE_CLEARANCE,
                 SURFACE_SHAPE_UNSUPPORTED -> true;
            default -> false;
        };
    }

    private static void validateRequiredProfiles(
            IrisHydrologyRuntime runtime,
            List<CoverageSelector> selectors
    ) {
        Set<String> riverProfiles = runtime.profileKeys();
        LinkedHashSet<String> deepProfiles = new LinkedHashSet<>();
        for (HydrologyPlannerSettings.DeepFluid deepFluid : runtime.settings().deepFluids()) {
            if (deepFluid.enabled()) {
                deepProfiles.add(deepFluid.id());
            }
        }
        for (CoverageSelector selector : selectors) {
            if (selector.profileKey().equals("*")) {
                continue;
            }
            Set<String> configuredProfiles = selector.type().isDeepFluid() ? deepProfiles : riverProfiles;
            if (!configuredProfiles.contains(selector.profileKey())) {
                throw new IllegalArgumentException("Required coverage selector " + selector.label()
                        + " references a profile not configured for that feature family.");
            }
        }
    }

    static VerificationFamily verificationFamily(HydrologyFeatureType type) {
        if (type.isSurface()) {
            return VerificationFamily.SURFACE;
        }
        if (type.isGrotto() || type == HydrologyFeatureType.SINKHOLE) {
            return VerificationFamily.GROTTO;
        }
        return type.isDeepFluid() ? VerificationFamily.DEEP : VerificationFamily.CAVE;
    }

    private static Optional<GeneratedWitness> findGeneratedWitness(
            IrisHydrologyRuntime runtime,
            HydrologyTile tile,
            List<HydrologyColumnSample> columns,
            ObservedFeature observed,
            CoverageSelector selector
    ) {
        GeneratedWitness fallback = null;
        for (HydrologyColumnSample sample : columns) {
            for (HydrologyColumnLayer layer : sample.layers()) {
                if (layer.feature().type() != selector.type()
                        || layer.feature().courseId() != observed.feature().courseId()
                        || !layer.profileKey().equals(observed.profileKey())
                        || layer.oceanApron()) {
                    continue;
                }
                HydrologyColumnSample publicationSample = runtime.sample(sample.x(), sample.z())
                        .orElse(sample);
                HydrologyColumnLayer publicationLayer = layerByFeatureId(
                        publicationSample,
                        layer.feature().id()
                );
                if (publicationLayer == null) {
                    continue;
                }
                VerificationFamily family = verificationFamily(selector.type());
                if (family == VerificationFamily.SURFACE) {
                    HydrologyColumnLayer primary = publicationSample.primarySurfaceFluidLayer().orElse(null);
                    if (!publicationLayer.equals(primary)
                            || !publicationLayer.fluidOwned()
                            || publicationLayer.fluidHeadY() <= publicationLayer.bedY()) {
                        continue;
                    }
                    CavePosition fallingPosition = null;
                    if (publicationLayer.fallingFluid()) {
                        for (int y = publicationLayer.bedY() + 1; y < publicationLayer.fluidHeadY(); y++) {
                            HydrologyColumnSample.SurfacePublicationCell surfaceCell = publicationSample
                                    .surfacePublicationCellAt(y)
                                    .orElse(null);
                            if (surfaceCell != null
                                    && surfaceCell.layer().equals(publicationLayer)
                                    && surfaceCell.action() == HydrologyCaveAction.FALLING_FLUID) {
                                fallingPosition = new CavePosition(publicationSample.x(), y, publicationSample.z());
                                break;
                            }
                        }
                    }
                    boolean falling = fallingPosition != null;
                    CavePosition position = new CavePosition(
                            publicationSample.x(),
                            falling ? fallingPosition.y() : publicationLayer.fluidHeadY(),
                            publicationSample.z()
                    );
                    GeneratedWitness witness = new GeneratedWitness(
                            selector,
                            observed,
                            publicationLayer,
                            position,
                            falling ? HydrologyCaveAction.FALLING_FLUID : HydrologyCaveAction.WET_SOURCE
                    );
                    if (falling || fallback == null) {
                        fallback = witness;
                    }
                    if (!selector.type().isDrop() || falling) {
                        return Optional.of(witness);
                    }
                    continue;
                }

                HydrologyCavePlan plan = tile.cavePlan(layer.feature().courseId()).orElse(null);
                if (plan == null || !plan.accepted() || !exposureMatchesFamily(plan, family)) {
                    continue;
                }
                for (int y = publicationLayer.bedY() + 1; y <= publicationLayer.ceilingY(); y++) {
                    CavePosition position = new CavePosition(
                            publicationSample.x(),
                            y,
                            publicationSample.z()
                    );
                    if (surfaceBedGuardCovers(publicationSample, position)) {
                        continue;
                    }
                    HydrologyCaveAction action = composedCaveActionAt(
                            runtime,
                            plan,
                            publicationLayer,
                            position
                    );
                    if (action == null || action == HydrologyCaveAction.SEAL_GUARD) {
                        continue;
                    }
                    GeneratedWitness witness = new GeneratedWitness(
                            selector,
                            observed,
                            publicationLayer,
                            position,
                            action
                    );
                    if (action == HydrologyCaveAction.FALLING_FLUID) {
                        return Optional.of(witness);
                    }
                    if (fallback == null) {
                        fallback = witness;
                    }
                }
            }
        }
        return Optional.ofNullable(fallback);
    }

    private static HydrologyColumnLayer layerByFeatureId(
            HydrologyColumnSample sample,
            long featureId
    ) {
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.feature().id() == featureId) {
                return layer;
            }
        }
        return null;
    }

    private static HydrologyCaveAction composedCaveActionAt(
            IrisHydrologyRuntime runtime,
            HydrologyCavePlan plan,
            HydrologyColumnLayer layer,
            CavePosition position
    ) {
        HydrologyCaveAction action = plan.actions().get(position);
        if (action == null || action == HydrologyCaveAction.SEAL_GUARD) {
            return action;
        }
        HydrologyColumnSample sample = runtime.sample(position.x(), position.z()).orElse(null);
        if (sample == null) {
            return action;
        }
        HydrologyColumnSample.SurfacePublicationCell surfaceCell = sample
                .surfacePublicationCellAt(position.y())
                .orElse(null);
        if (surfaceCell != null && layer.profileKey().equals(surfaceCell.layer().profileKey())) {
            return surfaceCell.action();
        }
        return action;
    }

    private static boolean surfaceBedGuardCovers(HydrologyColumnSample sample, CavePosition position) {
        HydrologyColumnLayer surface = sample.primarySurfaceLayer().orElse(null);
        if (surface == null) {
            return false;
        }
        int minimumGuardY = Math.max(1, surface.bedY() - 2);
        return position.y() >= minimumGuardY && position.y() <= surface.bedY();
    }

    static List<HydrologyColumnSample> orderedFootprintColumns(HydrologyTile tile) {
        ArrayList<HydrologyColumnSample> columns = new ArrayList<>(tile.footprint().columns().values());
        columns.sort(Comparator.comparingInt(HydrologyColumnSample::x)
                .thenComparingInt(HydrologyColumnSample::z));
        return List.copyOf(columns);
    }

    private static boolean exposureMatchesFamily(
            HydrologyCavePlan plan,
            VerificationFamily family
    ) {
        boolean openToSurface = false;
        for (CaveVoxelPrecondition precondition : plan.baselinePreconditions().values()) {
            if (precondition.openToSurface()) {
                openToSurface = true;
                break;
            }
        }
        return family == VerificationFamily.GROTTO ? openToSurface
                : family != VerificationFamily.DEEP || !openToSurface;
    }

    static List<ChunkCoordinate> generatedWitnessChunks(List<CavePosition> positions) {
        LinkedHashSet<ChunkCoordinate> chunks = new LinkedHashSet<>();
        for (CavePosition position : positions) {
            chunks.add(new ChunkCoordinate(
                    Math.floorDiv(position.x(), 16),
                    Math.floorDiv(position.z(), 16)
            ));
        }
        return List.copyOf(chunks);
    }

    private static void verifyGeneratedWitnesses(
            ProbeConfiguration configuration,
            RealPackProbeSupport.Workspace workspace,
            long seed,
            Map<CoverageSelector, GeneratedWitness> pending,
            Set<CoverageSelector> verified,
            Set<SeedChunk> generatedChunks,
            MessageDigest signature
    ) throws Exception {
        ArrayList<CavePosition> positions = new ArrayList<>(pending.size());
        for (GeneratedWitness witness : pending.values()) {
            positions.add(witness.position());
        }
        for (ChunkCoordinate chunk : generatedWitnessChunks(positions)) {
            ArrayList<GeneratedWitness> chunkWitnesses = new ArrayList<>();
            for (GeneratedWitness witness : pending.values()) {
                int chunkX = Math.floorDiv(witness.position().x(), 16);
                int chunkZ = Math.floorDiv(witness.position().z(), 16);
                if (chunkX == chunk.chunkX() && chunkZ == chunk.chunkZ()) {
                    chunkWitnesses.add(witness);
                }
            }
            Map<CoverageSelector, GeneratedVerification> results = runGeneratedChunkProcess(
                    configuration,
                    workspace.pack(),
                    seed,
                    chunk,
                    chunkWitnesses
            );
            generatedChunks.add(new SeedChunk(seed, chunk.chunkX(), chunk.chunkZ()));
            for (GeneratedWitness witness : chunkWitnesses) {
                GeneratedVerification result = results.get(witness.selector());
                if (result == null) {
                    throw new IllegalStateException("Generated chunk process returned no result for "
                            + witness.selector().label() + ".");
                }
                updateGeneratedSignature(signature, witness, result);
                verified.add(witness.selector());
                System.out.printf(
                        Locale.ROOT,
                        "IRIS_HYDROLOGY_PACK_VERIFIED selector=%s seed=%d chunk_x=%d chunk_z=%d "
                                + "x=%d y=%d z=%d action=%s%n",
                        witness.selector().label(),
                        witness.observed().seed(),
                        chunk.chunkX(),
                        chunk.chunkZ(),
                        witness.position().x(),
                        witness.position().y(),
                        witness.position().z(),
                        witness.action().name()
                );
            }
        }
    }

    private static Map<CoverageSelector, GeneratedVerification> runGeneratedChunkProcess(
            ProbeConfiguration configuration,
            File preparedPack,
            long seed,
            ChunkCoordinate chunk,
            List<GeneratedWitness> witnesses
    ) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        command.add("--add-modules");
        command.add("jdk.incubator.vector");
        command.add("-Xmx" + Runtime.getRuntime().maxMemory());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(GeneratedChunkProcess.class.getName());
        command.add(preparedPack.getAbsolutePath());
        command.add(configuration.dimensionKey());
        command.add(Long.toString(seed));
        command.add(Boolean.toString(configuration.studio()));
        command.add(Integer.toString(chunk.chunkX()));
        command.add(Integer.toString(chunk.chunkZ()));
        for (GeneratedWitness witness : witnesses) {
            command.add(GeneratedWitnessDescriptor.from(witness).encode());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        processBuilder.environment().remove("JDK_JAVA_OPTIONS");
        processBuilder.environment().remove("_JAVA_OPTIONS");
        Process process = processBuilder.start();
        ArrayList<String> output = new ArrayList<>();
        LinkedHashMap<CoverageSelector, GeneratedVerification> results = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Generated chunk process failed for seed " + seed
                    + " at " + chunk.chunkX() + "," + chunk.chunkZ()
                    + " for " + witnesses.stream()
                    .map((GeneratedWitness witness) -> witness.selector().label()
                            + "@" + witness.position() + "/" + witness.action().name())
                    .toList() + ":\n"
                    + String.join("\n", output));
        }
        for (String line : output) {
            if (!line.startsWith(GENERATED_RESULT_PREFIX + " ")) {
                continue;
            }
            GeneratedProcessResult result = GeneratedProcessResult.parse(line);
            GeneratedVerification previous = results.put(result.selector(), result.verification());
            if (previous != null) {
                throw new IllegalStateException("Generated chunk process returned duplicate selector "
                        + result.selector().label() + ".");
            }
        }
        if (results.size() != witnesses.size()) {
            throw new IllegalStateException("Generated chunk process returned " + results.size()
                    + " result(s) for " + witnesses.size() + " witness(es).");
        }
        return Map.copyOf(results);
    }

    private static GeneratedVerification verifyGeneratedWitness(
            Engine engine,
            IrisHydrologyRuntime runtime,
            RealPackProbeSupport.GeneratedChunk generated,
            GeneratedWitness witness
    ) {
        CavePosition position = witness.position();
        HydrologyColumnSample composed = runtime.sample(position.x(), position.z()).orElseThrow(
                () -> new IllegalStateException("Generated witness lost its accepted hydrology column."));
        if (!composed.layers().contains(witness.layer())) {
            throw new IllegalStateException("Generated witness differs from the accepted composed hydrology layer.");
        }
        HydrologyColumnLayer layer = witness.layer();
        PlatformBlockState expectedFluid = engine.getComplex().resolveHydrologyFluid(
                layer.profileKey(),
                position.x(),
                position.z()
        );
        VerificationFamily family = verificationFamily(witness.selector().type());
        if (family == VerificationFamily.SURFACE) {
            verifySurfaceWitness(engine, generated, composed, layer, position, expectedFluid);
        } else {
            verifyCaveWitness(engine, runtime, generated, witness, expectedFluid, family);
        }
        if (witness.action() == HydrologyCaveAction.FALLING_FLUID) {
            requireNoUpdateMarker(engine, position);
        }
        String blockStateKey = stateKey(generated.blockAt(position.x(), position.y(), position.z()));
        PlatformBiome biome = generated.biomeAt(
                position.x(),
                Math.min(position.y(), generated.height() - 1),
                position.z()
        );
        return new GeneratedVerification(blockStateKey, biome == null ? "null" : biome.key());
    }

    private static void updateGeneratedSignature(
            MessageDigest signature,
            GeneratedWitness witness,
            GeneratedVerification verification
    ) {
        CavePosition position = witness.position();
        updateDigest(signature, "generated");
        updateDigest(signature, witness.selector().label());
        updateDigest(signature, Long.toString(witness.observed().seed()));
        updateDigest(signature, Integer.toString(position.x()));
        updateDigest(signature, Integer.toString(position.y()));
        updateDigest(signature, Integer.toString(position.z()));
        updateDigest(signature, verification.blockStateKey());
        updateDigest(signature, verification.biomeKey());
    }

    private static void verifySurfaceWitness(
            Engine engine,
            RealPackProbeSupport.GeneratedChunk generated,
            HydrologyColumnSample sample,
            HydrologyColumnLayer layer,
            CavePosition position,
            PlatformBlockState expectedFluid
    ) {
        if (sample.terrainHeight() != layer.bedY()) {
            throw new IllegalStateException("Accepted surface witness does not own the composed terrain height.");
        }
        if (layer.fluidHeadY() >= sample.naturalHeight()) {
            throw new IllegalStateException("Generated surface witness is not recessed below natural terrain.");
        }
        PlatformBlockState bed = generated.blockAt(position.x(), layer.bedY(), position.z());
        if (bed == null || bed.isAirOrFluid() || matchesConfiguredFluid(bed, expectedFluid, true)) {
            throw new IllegalStateException("Generated surface witness has no non-fluid channel bed: state="
                    + stateKey(bed)
                    + ", position=" + new CavePosition(position.x(), layer.bedY(), position.z())
                    + ", layer=" + layer
                    + ", sampleLayers=" + sample.layers());
        }
        requireNonVegetatedBed(bed, "surface");
        PlatformBlockState fluid = generated.blockAt(position.x(), position.y(), position.z());
        requireMatchingFluid(
                fluid,
                expectedFluid,
                "surface",
                position.y() == layer.fluidHeadY()
        );
        int composedFluidHead = composedSurfaceFluidHead(sample);
        if (composedFluidHead + 1 < generated.height()) {
            PlatformBlockState above = generated.blockAt(position.x(), composedFluidHead + 1, position.z());
            if (above != null && matchesConfiguredFluid(above, expectedFluid, true)) {
                throw new IllegalStateException("Generated surface witness contains " + stateKey(above)
                        + " above its accepted head at "
                        + new CavePosition(position.x(), composedFluidHead + 1, position.z())
                        + ": layer=" + layer + ", sampleLayers=" + sample.layers() + ".");
            }
        }
        PlatformBiome biome = generated.biomeAt(position.x(), layer.fluidHeadY(), position.z());
        IrisBiome surfaceBiome = engine.getComplex().getTrueBiomeStream().get(position.x(), position.z());
        String expectedBiomeKey = generatedSurfaceBiomeKey(
                surfaceBiome,
                engine,
                engine.getSeedManager().getBiome(),
                engine.getDimension().getLoadKey(),
                position.x(),
                position.z()
        );
        String actualBiomeKey = biome == null ? null : biome.key();
        if (!expectedBiomeKey.equals(actualBiomeKey)) {
            throw new IllegalStateException("Generated surface biome differs from its resolved hydrology biome: "
                    + "selector=" + layer.feature().type().name()
                    + ", accepted=" + layer.biomeKey()
                    + ", expected=" + expectedBiomeKey
                    + ", actual=" + actualBiomeKey
                    + ", x=" + position.x()
                    + ", y=" + layer.fluidHeadY()
                    + ", z=" + position.z() + ".");
        }
    }

    static String generatedSurfaceBiomeKey(
            IrisBiome biome,
            Engine engine,
            long biomeSeed,
            String dimensionKey,
            int x,
            int z
    ) {
        if (biome == null) {
            throw new IllegalStateException("Generated surface biome resolution returned no Iris biome.");
        }
        RNG rng = new RNG(biomeSeed);
        if (!biome.isCustom()) {
            String key = biome.getSkyBiomeKey(rng, engine, x, 0, z);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Generated surface Iris biome resolved no platform biome key.");
            }
            return key;
        }
        IrisBiomeCustom custom = biome.getCustomBiome(rng, engine, x, 0, z);
        if (custom == null || custom.getId() == null || custom.getId().isBlank()) {
            throw new IllegalStateException("Generated custom surface Iris biome resolved no custom biome id.");
        }
        return dimensionKey + ":" + custom.getId();
    }

    private static void verifyCaveWitness(
            Engine engine,
            IrisHydrologyRuntime runtime,
            RealPackProbeSupport.GeneratedChunk generated,
            GeneratedWitness witness,
            PlatformBlockState expectedFluid,
            VerificationFamily family
    ) {
        CavePosition position = witness.position();
        HydrologyCavePlan plan = runtime.tile(witness.observed().tile())
                .cavePlan(witness.layer().feature().courseId())
                .orElse(null);
        if (plan == null || composedCaveActionAt(runtime, plan, witness.layer(), position) != witness.action()
                || !exposureMatchesFamily(plan, family)) {
            throw new IllegalStateException("Generated cave witness differs from its accepted containment plan.");
        }
        HydrologyCaveCell cell = HydrologyCaveStorage.getIfPresent(
                engine.getMantle().getMantle(),
                position.x(),
                position.y(),
                position.z()
        );
        String expectedBiome = witness.action() == HydrologyCaveAction.DRY_AIR
                ? witness.layer().bankBiomeKey()
                : witness.layer().floodedCaveBiomeKey();
        if (cell == null
                || cell.action() != witness.action()
                || !cell.fluidProfileKey().equals(witness.layer().profileKey())
                || !cell.floodedBiomeKey().equals(expectedBiome)) {
            String preconditionMismatch = firstPublicationPreconditionMismatch(
                    engine,
                    runtime,
                    plan,
                    generated.chunkX(),
                    generated.chunkZ()
            );
            HydrologyColumnSample debugSample = runtime.sample(position.x(), position.z()).orElse(null);
            throw new IllegalStateException("Retained cave hydrology differs from the accepted plan: expected="
                    + witness.action() + "/" + witness.layer().profileKey() + "/" + expectedBiome
                    + ", actual=" + (cell == null
                    ? "null"
                    : cell.action() + "/" + cell.fluidProfileKey() + "/" + cell.floodedBiomeKey())
                    + ", position=" + position
                    + ", publicationPrecondition=" + preconditionMismatch
                    + ", terrain=" + (debugSample == null ? "null" : debugSample.terrainHeight())
                    + ", natural=" + (debugSample == null ? "null" : debugSample.naturalHeight())
                    + ", sampleLayers=" + (debugSample == null ? "null" : debugSample.layers())
                    + ", layer=" + witness.layer() + ".");
        }
        PlatformBlockState state = generated.blockAt(position.x(), position.y(), position.z());
        if (cell.isWet()) {
            requireMatchingFluid(state, expectedFluid, "cave", false);
            PlatformBlockState bed = generated.blockAt(
                    position.x(),
                    witness.layer().bedY(),
                    position.z()
            );
            requireNonVegetatedBed(
                    bed,
                    "cave at " + new CavePosition(position.x(), witness.layer().bedY(), position.z())
                            + " with " + witness.layer()
            );
        } else if (state != null && !state.isAir()) {
            throw new IllegalStateException("Generated dry hydrology cave cell is not air.");
        }
    }

    private static String firstPublicationPreconditionMismatch(
            Engine engine,
            IrisHydrologyRuntime runtime,
            HydrologyCavePlan plan,
            int chunkX,
            int chunkZ
    ) {
        CaveVoxelView view = new MantleHydrologyCaveVoxelView(
                engine,
                engine.getComplex(),
                (int x, int z, int naturalHeight) -> runtime.sample(x, z)
                        .map(HydrologyColumnSample::terrainHeight)
                        .orElse(naturalHeight)
        );
        int minimumX = chunkX << 4;
        int minimumZ = chunkZ << 4;
        int maximumX = minimumX + 15;
        int maximumZ = minimumZ + 15;
        for (Map.Entry<CavePosition, CaveVoxelPrecondition> entry
                : plan.baselinePreconditions().entrySet()) {
            CavePosition candidate = entry.getKey();
            if (candidate.x() < minimumX || candidate.x() > maximumX
                    || candidate.z() < minimumZ || candidate.z() > maximumZ) {
                continue;
            }
            CaveVoxelPrecondition expected = entry.getValue();
            CaveVoxel actual = view.voxelAt(candidate);
            boolean actualOpen = view.isOpenToSurface(candidate);
            if ((actual != expected.voxel() || actualOpen != expected.openToSurface())
                    && !publicationAllowsChangedCaveBoundary(
                    runtime.sample(candidate.x(), candidate.z()).orElse(null),
                    plan,
                    candidate,
                    expected,
                    actual,
                    actualOpen
            )) {
                HydrologyColumnSample mismatchSample = runtime.sample(candidate.x(), candidate.z()).orElse(null);
                return candidate + "/expected=" + expected.voxel() + "/" + expected.openToSurface()
                        + "/actual=" + actual + "/" + actualOpen
                        + "/action=" + plan.actions().get(candidate)
                        + "/terrain=" + (mismatchSample == null ? "null" : mismatchSample.terrainHeight());
            }
        }
        return "none";
    }

    private static boolean publicationAllowsChangedCaveBoundary(
            HydrologyColumnSample sample,
            HydrologyCavePlan plan,
            CavePosition position,
            CaveVoxelPrecondition expected,
            CaveVoxel actual,
            boolean actualOpen
    ) {
        HydrologyCaveAction action = plan.actions().get(position);
        if (expected.voxel() == CaveVoxel.CAVE_AIR
                && expected.openToSurface()
                && actual == CaveVoxel.CAVE_AIR
                && !actualOpen
                && action != HydrologyCaveAction.DRY_AIR) {
            return true;
        }
        if (expected.voxel() != CaveVoxel.CAVE_AIR
                || !expected.openToSurface()
                || actual != CaveVoxel.SOLID
                || actualOpen) {
            return false;
        }
        if (action == null || action == HydrologyCaveAction.SEAL_GUARD) {
            return true;
        }
        if (sample == null
                || action != HydrologyCaveAction.WET_SOURCE
                && action != HydrologyCaveAction.FALLING_FLUID) {
            return false;
        }
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.feature().courseId() == plan.source().sourceId()
                    && layer.feature().type().isSurface()
                    && !layer.oceanApron()
                    && layer.channel()
                    && layer.terrainOwned()
                    && layer.fluidOwned()
                    && layer.connectedFluid()
                    && position.y() > layer.bedY()
                    && position.y() <= layer.fluidHeadY()) {
                return true;
            }
        }
        return false;
    }

    private static void requireNoUpdateMarker(Engine engine, CavePosition position) {
        MatterUpdate update = engine.getMantle().getMantle().get(
                position.x(),
                position.y(),
                position.z(),
                MatterUpdate.class
        );
        if (update != null && update.isUpdate()) {
            throw new IllegalStateException("Generated falling fluid retained a lateral update marker.");
        }
    }

    private static void requireNonVegetatedBed(PlatformBlockState bed, String label) {
        String key = stateKey(bed).toLowerCase(Locale.ROOT);
        if (key.endsWith(":grass_block") || key.endsWith(":moss_block")) {
            throw new IllegalStateException("Generated " + label + " hydrology rests on " + key + ".");
        }
    }

    private static int composedSurfaceFluidHead(HydrologyColumnSample sample) {
        int maximumHead = sample.terrainHeight();
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.feature().type().isSurface()
                    && !layer.oceanApron()
                    && layer.channel()
                    && layer.terrainOwned()
                    && layer.fluidOwned()
                    && layer.connectedFluid()
                    && layer.fluidHeadY() > layer.bedY()) {
                maximumHead = Math.max(maximumHead, layer.fluidHeadY());
            }
        }
        return maximumHead;
    }

    private static void requireMatchingFluid(
            PlatformBlockState actual,
            PlatformBlockState expected,
            String label,
            boolean allowWaterColumnReplacement
    ) {
        if (!matchesConfiguredFluid(actual, expected, allowWaterColumnReplacement)) {
            throw new IllegalStateException("Generated " + label + " fluid differs from its configured profile: "
                    + "actual=" + (actual == null ? "null" : actual.key())
                    + ", actual_material=" + (actual == null ? "null" : materialKey(actual))
                    + ", expected=" + (expected == null ? "null" : expected.key())
                    + ", expected_material=" + (expected == null ? "null" : materialKey(expected)) + ".");
        }
    }

    static boolean matchesConfiguredFluid(
            PlatformBlockState actual,
            PlatformBlockState expected,
            boolean allowWaterColumnReplacement
    ) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.isFluid() && materialKey(actual).equals(materialKey(expected))) {
            return true;
        }
        return allowWaterColumnReplacement
                && expected.isWater()
                && (actual.isWaterLogged() || WATER_COLUMN_REPLACEMENTS.contains(materialKey(actual)));
    }

    private static String materialKey(PlatformBlockState state) {
        String cached = state.materialKey();
        if (cached != null) {
            return cached;
        }
        String key = state.key();
        int bracket = key.indexOf('[');
        return bracket < 0 ? key : key.substring(0, bracket);
    }

    private static String stateKey(PlatformBlockState state) {
        return state == null ? "minecraft:air" : state.key();
    }

    private static GeneratedWitness resolveGeneratedWitness(
            IrisHydrologyRuntime runtime,
            long seed,
            int minimumWorldY,
            GeneratedWitnessDescriptor descriptor
    ) {
        HydrologyTile tile = runtime.tile(descriptor.tile());
        HydrologyFeatureRef feature = null;
        for (HydrologyFeatureRef candidate : tile.features()) {
            if (candidate.id() == descriptor.featureId()) {
                feature = candidate;
                break;
            }
        }
        if (feature == null) {
            throw new IllegalStateException("Generated witness feature is absent from the accepted tile: "
                    + Long.toUnsignedString(descriptor.featureId()) + ".");
        }
        String profileKey = null;
        for (RiverCourse course : tile.courses()) {
            if (course.id() == feature.courseId()) {
                profileKey = course.profileKey();
                break;
            }
        }
        if (!descriptor.profileKey().equals(profileKey)) {
            throw new IllegalStateException("Generated witness profile changed across process isolation.");
        }
        ObservedFeature observed = new ObservedFeature(
                seed,
                descriptor.tile(),
                profileKey,
                feature,
                minimumWorldY
        );
        GeneratedWitness witness = findGeneratedWitness(
                runtime,
                tile,
                orderedFootprintColumns(tile),
                observed,
                descriptor.selector()
        ).orElseThrow(() -> new IllegalStateException(
                "Generated witness could not be reconstructed from the accepted tile."));
        if (!witness.position().equals(descriptor.position()) || witness.action() != descriptor.action()) {
            throw new IllegalStateException("Generated witness changed across process isolation: expected "
                    + descriptor.position() + "/" + descriptor.action().name()
                    + ", resolved " + witness.position() + "/" + witness.action().name() + ".");
        }
        return witness;
    }

    private static String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public static final class GeneratedChunkProcess {
        private static final String LOG_PREFIX = "[hydrology-generated-chunk-probe]";

        private GeneratedChunkProcess() {
        }

        public static void main(String[] arguments) {
            try {
                run(arguments);
                System.exit(0);
            } catch (Throwable failure) {
                System.out.println(LOG_PREFIX + " FAIL: generated chunk verification failed");
                failure.printStackTrace(System.out);
                System.exit(1);
            }
        }

        private static void run(String[] arguments) throws Exception {
            if (arguments.length < 7) {
                throw new IllegalArgumentException("Generated chunk process requires a pack, dimension, seed, "
                        + "studio flag, chunk coordinates, and at least one witness.");
            }
            File preparedPack = new File(arguments[0]);
            String dimensionKey = arguments[1];
            long seed = Long.parseLong(arguments[2]);
            boolean studio = RealPackProbeSupport.parseBoolean(arguments[3], "studio");
            int chunkX = Integer.parseInt(arguments[4]);
            int chunkZ = Integer.parseInt(arguments[5]);
            ArrayList<GeneratedWitnessDescriptor> descriptors = new ArrayList<>(arguments.length - 6);
            for (int index = 6; index < arguments.length; index++) {
                descriptors.add(GeneratedWitnessDescriptor.parse(arguments[index]));
            }

            try (RealPackProbeSupport.Workspace workspace = RealPackProbeSupport.openPreparedWorkspace(
                    preparedPack, dimensionKey, LOG_PREFIX, true);
                 RealPackProbeSupport.EngineSession session = workspace.openEngine(
                         seed, studio, "generated-" + seed + "-" + chunkX + "-" + chunkZ)) {
                Engine engine = session.engine();
                IrisHydrologyRuntime runtime = engine.getComplex().getHydrologyRuntime();
                if (runtime == null) {
                    throw new IllegalStateException("Dimension '" + dimensionKey
                            + "' has no hydrology runtime during generated verification.");
                }
                ArrayList<GeneratedWitness> witnesses = new ArrayList<>(descriptors.size());
                for (GeneratedWitnessDescriptor descriptor : descriptors) {
                    GeneratedWitness witness = resolveGeneratedWitness(
                            runtime,
                            seed,
                            engine.getWorld().minHeight(),
                            descriptor
                    );
                    int witnessChunkX = Math.floorDiv(witness.position().x(), 16);
                    int witnessChunkZ = Math.floorDiv(witness.position().z(), 16);
                    if (witnessChunkX != chunkX || witnessChunkZ != chunkZ) {
                        throw new IllegalStateException("Generated witness is outside the requested chunk.");
                    }
                    witnesses.add(witness);
                }
                RealPackProbeSupport.GeneratedChunk generated = RealPackProbeSupport.generateChunk(
                        engine, chunkX, chunkZ);
                for (GeneratedWitness witness : witnesses) {
                    GeneratedVerification verification = verifyGeneratedWitness(
                            engine, runtime, generated, witness);
                    System.out.println(new GeneratedProcessResult(
                            witness.selector(), verification).machineLine());
                }
                List<Throwable> reports = RealPackProbeSupport.settleAndDrain();
                if (!reports.isEmpty()) {
                    RealPackProbeSupport.printReports(LOG_PREFIX, "generated chunk reports", reports);
                    throw reportedFailure("Hydrology generated chunk process", reports);
                }
            }
        }
    }

    static List<ConfiguredCoverage> missingConfiguredCoverage(
            Set<ConfiguredCoverage> observed,
            Set<ConfiguredCoverage> required
    ) {
        ArrayList<ConfiguredCoverage> missing = new ArrayList<>();
        ArrayList<ConfiguredCoverage> ordered = new ArrayList<>(required);
        ordered.sort(Comparator.naturalOrder());
        for (ConfiguredCoverage requirement : ordered) {
            if (!observed.contains(requirement)) {
                missing.add(requirement);
            }
        }
        return List.copyOf(missing);
    }

    private static Set<ConfiguredCoverage> configuredCoverage(IrisHydrologyRuntime runtime) {
        LinkedHashSet<ConfiguredCoverage> required = new LinkedHashSet<>();
        HydrologyPlannerSettings settings = runtime.settings();
        if (settings.surface().enabled()
                && settings.surface().sources().enabled()
                && settings.surface().sources().maximumPerTile() > 0) {
            for (String profileKey : runtime.profileKeys()) {
                required.add(new ConfiguredCoverage(CoverageFamily.SURFACE, profileKey));
            }
        }
        if (settings.underground().enabled()
                && settings.underground().sources().enabled()
                && settings.underground().sources().maximumPerTile() > 0) {
            for (String profileKey : runtime.profileKeys()) {
                required.add(new ConfiguredCoverage(CoverageFamily.UNDERGROUND, profileKey));
            }
        }
        for (HydrologyPlannerSettings.DeepFluid deepFluid : settings.deepFluids()) {
            if (deepFluid.enabled() && deepFluid.maximumPerTile() > 0) {
                required.add(new ConfiguredCoverage(CoverageFamily.DEEP, deepFluid.id()));
            }
        }
        return Set.copyOf(required);
    }

    private static ConfiguredCoverage courseCoverage(RiverCourse course) {
        CoverageFamily family = switch (course.type()) {
            case SURFACE -> CoverageFamily.SURFACE;
            case UNDERGROUND -> CoverageFamily.UNDERGROUND;
            case DEEP_FLUID -> CoverageFamily.DEEP;
        };
        return new ConfiguredCoverage(family, course.profileKey());
    }

    private static void validateTileBlockBounds(
            IrisHydrologyRuntime runtime,
            ProbeConfiguration configuration
    ) {
        int tileSize = runtime.settings().routing().tileSize();
        int publicationRadius = runtime.settings().publicationRadius();
        long minimumBlockX = (long) configuration.minimumTileX() * tileSize - publicationRadius;
        long maximumBlockX = ((long) configuration.maximumTileX() + 1L) * tileSize
                - 1L + publicationRadius;
        long minimumBlockZ = (long) configuration.minimumTileZ() * tileSize - publicationRadius;
        long maximumBlockZ = ((long) configuration.maximumTileZ() + 1L) * tileSize
                - 1L + publicationRadius;
        if (minimumBlockX < Integer.MIN_VALUE || maximumBlockX > Integer.MAX_VALUE
                || minimumBlockZ < Integer.MIN_VALUE || maximumBlockZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Hydrology tile bounds exceed the planner's integer block range.");
        }
    }

    private static IllegalStateException reportedFailure(String operation, List<Throwable> reports) {
        IllegalStateException failure = new IllegalStateException(
                operation + " reported " + reports.size() + " error(s).");
        for (Throwable report : reports) {
            failure.addSuppressed(report);
        }
        return failure;
    }

    private static List<Long> parseSeeds(String value) {
        String[] parts = value.split(",", -1);
        ArrayList<Long> seeds = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Seed list cannot contain an empty entry.");
            }
            seeds.add(Long.parseLong(part));
        }
        return List.copyOf(seeds);
    }

    private static List<CoverageSelector> parseCoverage(String value) {
        String[] parts = value.split(",", -1);
        ArrayList<CoverageSelector> selectors = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Coverage selector list cannot contain an empty entry.");
            }
            selectors.add(CoverageSelector.parse(part));
        }
        return List.copyOf(selectors);
    }

    private static long boundedArea(
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ,
            String label
    ) {
        long width = (long) maximumX - minimumX + 1L;
        long depth = (long) maximumZ - minimumZ + 1L;
        try {
            return Math.multiplyExact(width, depth);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(label + " bounds overflow their bounded area.", failure);
        }
    }

    private static void updateSignature(
            MessageDigest digest,
            long seed,
            HydrologyTileKey tile,
            String profileKey,
            HydrologyFeatureRef feature
    ) {
        updateDigest(digest, Long.toString(seed));
        updateDigest(digest, Integer.toString(tile.tileX()));
        updateDigest(digest, Integer.toString(tile.tileZ()));
        updateDigest(digest, Long.toUnsignedString(feature.id()));
        updateDigest(digest, feature.type().name());
        updateDigest(digest, profileKey);
        updateDigest(digest, Integer.toString(feature.x()));
        updateDigest(digest, Integer.toString(feature.y()));
        updateDigest(digest, Integer.toString(feature.z()));
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
