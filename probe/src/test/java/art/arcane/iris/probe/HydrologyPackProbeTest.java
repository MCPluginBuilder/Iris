package art.arcane.iris.probe;

import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydraulicSegment;
import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import art.arcane.iris.engine.hydrology.HydrologyPoint;
import art.arcane.iris.engine.hydrology.HydrologyTile;
import art.arcane.iris.engine.hydrology.HydrologyTileKey;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveAction;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.spi.PlatformBiome;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.hunk.Hunk;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HydrologyPackProbeTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void parsesExplicitBoundedCoverageInputs() {
        HydrologyPackProbe.ProbeConfiguration configuration = HydrologyPackProbe.ProbeConfiguration.parse(
                new String[]{
                        "/tmp/pack",
                        "overworld",
                        "1,-19,331",
                        "-2",
                        "2",
                        "-1",
                        "1",
                        "SURFACE_POOL@water,WATERFALL@*,DEEP_POOL@deep_lava",
                        "false"
                });

        assertEquals(new File("/tmp/pack"), configuration.packSource());
        assertEquals("overworld", configuration.dimensionKey());
        assertEquals(List.of(1L, -19L, 331L), configuration.seeds());
        assertEquals(15, configuration.tilesPerSeed());
        assertEquals(3, configuration.requiredCoverage().size());
        assertTrue(!configuration.studio());
    }

    @Test
    public void rejectsImplicitUnboundedOrDuplicateInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.ProbeConfiguration.parse(new String[]{"/tmp/pack"}));
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.ProbeConfiguration.parse(new String[]{
                        "/tmp/pack", "overworld", "1", "0", "0", "0", "0",
                        "SURFACE_POOL@water", "false"
                }));
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.ProbeConfiguration.parse(new String[]{
                        "/tmp/pack", "overworld", "1,1", "0", "0", "0", "0",
                        "SURFACE_POOL@water", "false"
                }));
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.ProbeConfiguration.parse(new String[]{
                        "/tmp/pack", "overworld", "1,2", "0", "20", "0", "20",
                        "SURFACE_POOL@water", "false"
                }));
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.ProbeConfiguration.parse(new String[]{
                        "/tmp/pack", "overworld", "1,2", "0", "0", "0", "0",
                        "SURFACE_POOL@water", "sometimes"
                }));
    }

    @Test
    public void selectorsMatchExactOrWildcardProfiles() {
        HydrologyPackProbe.CoverageKey water = new HydrologyPackProbe.CoverageKey(
                HydrologyFeatureType.WATERFALL, "water");
        HydrologyPackProbe.CoverageKey lava = new HydrologyPackProbe.CoverageKey(
                HydrologyFeatureType.WATERFALL, "lava");
        HydrologyPackProbe.CoverageSelector exact = HydrologyPackProbe.CoverageSelector.parse(
                "WATERFALL@water");
        HydrologyPackProbe.CoverageSelector wildcard = HydrologyPackProbe.CoverageSelector.parse(
                "WATERFALL@*");

        assertTrue(exact.matches(water));
        assertTrue(!exact.matches(lava));
        assertTrue(wildcard.matches(water));
        assertTrue(wildcard.matches(lava));
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.CoverageSelector.parse("WATERFALL"));
        assertThrows(IllegalArgumentException.class,
                () -> HydrologyPackProbe.CoverageSelector.parse("UNKNOWN@water"));
    }

    @Test
    public void wildcardSelectorsRepresentEveryAcceptedFeatureType() {
        for (HydrologyFeatureType type : HydrologyFeatureType.values()) {
            HydrologyPackProbe.CoverageSelector selector = HydrologyPackProbe.CoverageSelector.parse(
                    type.name() + "@*");
            HydrologyPackProbe.CoverageKey observed = new HydrologyPackProbe.CoverageKey(type, "profile");

            assertTrue(selector.matches(observed));
        }
    }

    @Test
    public void everyFeatureTypeMapsToOneGeneratedAssertionFamily() {
        assertEquals(HydrologyPackProbe.VerificationFamily.SURFACE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.SURFACE_POOL));
        assertEquals(HydrologyPackProbe.VerificationFamily.SURFACE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.RIFFLE));
        assertEquals(HydrologyPackProbe.VerificationFamily.SURFACE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.CASCADE));
        assertEquals(HydrologyPackProbe.VerificationFamily.SURFACE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.WATERFALL));
        assertEquals(HydrologyPackProbe.VerificationFamily.SURFACE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.MOUTH));
        assertEquals(HydrologyPackProbe.VerificationFamily.CAVE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.RIDGE_BORE));
        assertEquals(HydrologyPackProbe.VerificationFamily.CAVE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.UNDERGROUND_POOL));
        assertEquals(HydrologyPackProbe.VerificationFamily.CAVE,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.UNDERGROUND_DROP));
        assertEquals(HydrologyPackProbe.VerificationFamily.GROTTO,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.SINKHOLE));
        assertEquals(HydrologyPackProbe.VerificationFamily.GROTTO,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.COASTAL_GROTTO));
        assertEquals(HydrologyPackProbe.VerificationFamily.GROTTO,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.INLAND_GROTTO));
        assertEquals(HydrologyPackProbe.VerificationFamily.DEEP,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.DEEP_POOL));
        assertEquals(HydrologyPackProbe.VerificationFamily.DEEP,
                HydrologyPackProbe.verificationFamily(HydrologyFeatureType.DEEP_CHANNEL));
    }

    @Test
    public void generatedChunkMapsNegativeWorldCoordinatesToLocalBuffers() {
        StubPlatform platform = new StubPlatform(new File("/tmp/iris-hydrology-pack-probe-test"));
        PlatformBlockState water = StubPlatform.blockStateForTest("minecraft:water[level=8]");
        PlatformBiome biome = platform.registries().biome("minecraft:river");
        Hunk<PlatformBlockState> blocks = Hunk.newArrayHunk(16, 2, 16);
        Hunk<PlatformBiome> biomes = Hunk.newArrayHunk(16, 2, 16);
        blocks.set(15, 1, 15, water);
        biomes.set(15, 1, 15, biome);
        RealPackProbeSupport.GeneratedChunk chunk = new RealPackProbeSupport.GeneratedChunk(
                -1, -2, 2, blocks, biomes);

        assertEquals(water, chunk.blockAt(-1, 1, -17));
        assertEquals(biome, chunk.biomeAt(-1, 1, -17));
        assertThrows(IllegalArgumentException.class, () -> chunk.blockAt(-17, 1, -17));
        assertThrows(IllegalArgumentException.class, () -> chunk.blockAt(-1, 2, -17));
    }

    @Test
    public void generatedSurfaceFluidAcceptsOnlyWaterBearingDecorantsForWaterProfiles() {
        PlatformBlockState water = StubPlatform.blockStateForTest("minecraft:water[level=0]");
        PlatformBlockState lava = StubPlatform.blockStateForTest("minecraft:lava[level=0]");
        PlatformBlockState kelp = StubPlatform.blockStateForTest("minecraft:kelp_plant");
        PlatformBlockState coral = StubPlatform.blockStateForTest(
                "minecraft:brain_coral_fan[waterlogged=true]");
        PlatformBlockState stone = StubPlatform.blockStateForTest("minecraft:stone");

        assertTrue(HydrologyPackProbe.matchesConfiguredFluid(water, water, false));
        assertTrue(HydrologyPackProbe.matchesConfiguredFluid(kelp, water, true));
        assertTrue(HydrologyPackProbe.matchesConfiguredFluid(coral, water, true));
        assertFalse(HydrologyPackProbe.matchesConfiguredFluid(kelp, water, false));
        assertFalse(HydrologyPackProbe.matchesConfiguredFluid(kelp, lava, true));
        assertFalse(HydrologyPackProbe.matchesConfiguredFluid(stone, water, true));
    }

    @Test
    public void generatedWitnessColumnsAreSortedOnceBySignedCoordinates() {
        HydrologyColumnSample eastern = column(1, -2);
        HydrologyColumnSample northwest = column(-1, -4);
        HydrologyColumnSample southwest = column(-1, 3);
        HydrologyTile tile = new HydrologyTile(
                new HydrologyTileKey(0, 0),
                1L,
                2L,
                64,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new RiverFootprint(Map.of(
                        RiverFootprint.pack(eastern.x(), eastern.z()), eastern,
                        RiverFootprint.pack(northwest.x(), northwest.z()), northwest,
                        RiverFootprint.pack(southwest.x(), southwest.z()), southwest
                ))
        );

        assertEquals(
                List.of(northwest, southwest, eastern),
                HydrologyPackProbe.orderedFootprintColumns(tile)
        );
    }

    @Test
    public void generatedWitnessChunksAreDistinctInFirstEncounterOrder() {
        assertEquals(
                List.of(
                        new HydrologyPackProbe.ChunkCoordinate(-1, -2),
                        new HydrologyPackProbe.ChunkCoordinate(0, 0),
                        new HydrologyPackProbe.ChunkCoordinate(2, -1)
                ),
                HydrologyPackProbe.generatedWitnessChunks(List.of(
                        new CavePosition(-1, 40, -17),
                        new CavePosition(-16, 50, -32),
                        new CavePosition(0, 60, 0),
                        new CavePosition(47, 70, -1)
                ))
        );
    }

    @Test
    public void generatedSurfaceBiomeUsesThePlatformDerivativeKey() {
        IrisBiome biome = new IrisBiome();
        biome.setDerivative("minecraft:river");

        assertEquals(
                "minecraft:river",
                HydrologyPackProbe.generatedSurfaceBiomeKey(
                        biome,
                        null,
                        19L,
                        "overworld",
                        32,
                        -48
                )
        );
    }

    @Test
    public void generatedWitnessDescriptorRoundTripsUnsignedIdsAndNegativeCoordinates() {
        HydrologyPackProbe.GeneratedWitnessDescriptor descriptor =
                new HydrologyPackProbe.GeneratedWitnessDescriptor(
                        HydrologyPackProbe.CoverageSelector.parse("UNDERGROUND_DROP@water"),
                        new HydrologyTileKey(-3, 7),
                        "water",
                        -5L,
                        new CavePosition(-33, 41, -1),
                        HydrologyCaveAction.FALLING_FLUID
                );

        assertEquals(
                descriptor,
                HydrologyPackProbe.GeneratedWitnessDescriptor.parse(descriptor.encode())
        );
    }

    @Test
    public void generatedProcessResultRoundTripsStateAndBiomeKeys() {
        HydrologyPackProbe.GeneratedProcessResult result = new HydrologyPackProbe.GeneratedProcessResult(
                HydrologyPackProbe.CoverageSelector.parse("SURFACE_POOL@water"),
                new HydrologyPackProbe.GeneratedVerification(
                        "minecraft:water[level=0]",
                        "minecraft:river"
                )
        );

        assertEquals(result, HydrologyPackProbe.GeneratedProcessResult.parse(result.machineLine()));
    }

    @Test
    public void versionTwoMachineLineReportsGeneratedVerificationCounts() {
        HydrologyPackProbe.ProbeResult result = new HydrologyPackProbe.ProbeResult(
                "PASS", "overworld", 2, 4, 8, 0, 1, 0, 1, 13, 13, 3, 7, 13, "abc");

        String line = result.machineLine();

        assertTrue(line.contains("version=2"));
        assertTrue(line.contains("generated_chunks=7"));
        assertTrue(line.contains("verified_features=13"));
    }

    @Test
    public void reportsEveryMissingExactCoverageSelector() {
        Set<HydrologyPackProbe.CoverageKey> observed = Set.of(
                new HydrologyPackProbe.CoverageKey(HydrologyFeatureType.SURFACE_POOL, "water"),
                new HydrologyPackProbe.CoverageKey(HydrologyFeatureType.DEEP_POOL, "deep_lava")
        );
        List<HydrologyPackProbe.CoverageSelector> required = List.of(
                HydrologyPackProbe.CoverageSelector.parse("SURFACE_POOL@water"),
                HydrologyPackProbe.CoverageSelector.parse("WATERFALL@water"),
                HydrologyPackProbe.CoverageSelector.parse("DEEP_CHANNEL@deep_lava")
        );

        assertEquals(
                List.of(required.get(1), required.get(2)),
                HydrologyPackProbe.missingCoverage(observed, required)
        );
    }

    @Test
    public void configuredFamiliesRequireEachEnabledProfileFamily() {
        Set<HydrologyPackProbe.ConfiguredCoverage> observed = Set.of(
                new HydrologyPackProbe.ConfiguredCoverage(
                        HydrologyPackProbe.CoverageFamily.SURFACE, "water"),
                new HydrologyPackProbe.ConfiguredCoverage(
                        HydrologyPackProbe.CoverageFamily.UNDERGROUND, "water")
        );
        LinkedHashSet<HydrologyPackProbe.ConfiguredCoverage> required = new LinkedHashSet<>(List.of(
                new HydrologyPackProbe.ConfiguredCoverage(
                        HydrologyPackProbe.CoverageFamily.SURFACE, "water"),
                new HydrologyPackProbe.ConfiguredCoverage(
                        HydrologyPackProbe.CoverageFamily.UNDERGROUND, "water"),
                new HydrologyPackProbe.ConfiguredCoverage(
                        HydrologyPackProbe.CoverageFamily.DEEP, "deep_lava")
        ));

        assertEquals(
                List.of(new HydrologyPackProbe.ConfiguredCoverage(
                        HydrologyPackProbe.CoverageFamily.DEEP, "deep_lava")),
                HydrologyPackProbe.missingConfiguredCoverage(observed, required)
        );
    }

    @Test
    public void surfaceOutletRequiresExactlyOneCompleteCourse() {
        assertTrue(HydrologyPackProbe.ShapeMetrics.validSurfaceOutletCourseGroup(1, 0));
        assertFalse(HydrologyPackProbe.ShapeMetrics.validSurfaceOutletCourseGroup(0, 0));
        assertFalse(HydrologyPackProbe.ShapeMetrics.validSurfaceOutletCourseGroup(0, 1));
        assertFalse(HydrologyPackProbe.ShapeMetrics.validSurfaceOutletCourseGroup(1, 1));
        assertFalse(HydrologyPackProbe.ShapeMetrics.validSurfaceOutletCourseGroup(2, 0));
    }

    @Test
    public void nearDiagonalStickCountsAsGridLocked() {
        List<HydrologyPoint> centerline = List.of(
                point(0, 0),
                point(96, 4),
                point(192, 8)
        );
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        1L,
                        centerline,
                        thickChannel(centerline, 4, 32),
                        false
                ));

        assertTrue(result.gridLockedFraction() > 0.9D);
        assertTrue(result.longestGridLockedRun() > 64D);
        assertTrue(result.violations().contains("GRID_LOCKED_FRACTION"));
        assertTrue(result.violations().contains("GRID_LOCKED_RUN"));
    }

    @Test
    public void isolatedRightAngleDoglegFailsTurnGates() {
        List<HydrologyPoint> centerline = List.of(
                point(0, 0),
                point(96, 0),
                point(96, 96),
                point(192, 96)
        );
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        2L,
                        centerline,
                        thickChannel(centerline, 4, 32),
                        false
                ));

        assertTrue(result.isolatedTurns() > 0);
        assertTrue(result.p95TurnDegrees() > 25D);
        assertTrue(result.violations().contains("ISOLATED_TURN"));
        assertTrue(result.violations().contains("P95_TURN"));
    }

    @Test
    public void gradualSourceWidthRampPassesEndpointGate() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(80, 24));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        3L,
                        centerline,
                        thickChannel(centerline, 4, 32),
                        false
                ));

        assertEquals(0, result.hardEndpointRamps());
        assertEquals(0, result.unexpectedLeaves());
        assertFalse(result.violations().contains("HARD_ENDPOINT_RAMP"));
    }

    @Test
    public void widenedSourceBasinFailsEndpointGate() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(80, 24));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        7L,
                        centerline,
                        widenedSourceBasin(centerline, 4, 7, 32),
                        false
                ));

        assertTrue(result.violations().contains("WIDENED_SOURCE_BASIN"));
    }

    @Test
    public void fullWidthSourceCapFailsEndpointRampGate() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(80, 24));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        4L,
                        centerline,
                        thickChannel(centerline, 4, 0),
                        false
                ));

        assertTrue(result.hardEndpointRamps() > 0);
        assertTrue(result.violations().contains("HARD_ENDPOINT_RAMP"));
    }

    @Test
    public void fullWidthExposedTerminalFailsEndpointRampGate() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(80, 24));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        5L,
                        centerline,
                        thickChannel(centerline, 4, 32),
                        true
                ));

        assertEquals(1, result.hardEndpointRamps());
        assertTrue(result.violations().contains("HARD_ENDPOINT_RAMP"));
    }

    @Test
    public void clippedCourseDoesNotInventEndpointRampFailures() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(160, 48));
        Set<Long> observedCells = thickChannel(
                List.of(point(40, 12), point(120, 36)),
                4,
                0
        );
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        8L,
                        centerline,
                        observedCells,
                        true
                ));

        assertEquals(0, result.hardEndpointRamps());
        assertFalse(result.violations().contains("HARD_ENDPOINT_RAMP"));
    }

    @Test
    public void roundedExposedTerminalCapPassesCapGate() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(160, 0));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        9L,
                        centerline,
                        terminalBasinChannel(centerline, 4, 7, 32, false),
                        true
                ));

        assertEquals(0, result.clippedTerminalCaps());
        assertTrue(result.terminalCapLength() >= 3D);
        assertFalse(result.violations().contains("CLIPPED_TERMINAL_CAP"));
    }

    @Test
    public void squareExposedTerminalCapFailsCapGateAfterGradualBasinRamp() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(160, 0));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        10L,
                        centerline,
                        terminalBasinChannel(centerline, 4, 7, 32, true),
                        true
                ));

        assertEquals(0, result.hardEndpointRamps());
        assertEquals(1, result.clippedTerminalCaps());
        assertEquals(0D, result.terminalCapLength(), 0D);
        assertTrue(result.violations().contains("CLIPPED_TERMINAL_CAP"));
    }

    @Test
    public void configuredMinimumWidthRejectsNarrowOwnedInterior() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(192, 0));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        11L,
                        centerline,
                        thickChannel(centerline, 1, 32),
                        false
                ));

        assertEquals(3, result.minimumInteriorWidth());
        assertTrue(result.violations().contains("NARROW_INTERIOR_WIDTH"));
    }

    @Test
    public void constantOwnedInteriorPassesWidthGates() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(192, 0));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        12L,
                        centerline,
                        thickChannel(centerline, 2, 32),
                        false
                ));

        assertTrue(result.minimumInteriorWidth() >= 4);
        assertEquals(0D, result.maximumWidthTroughDepthRatio(), 0D);
        assertFalse(result.violations().contains("NARROW_INTERIOR_WIDTH"));
        assertFalse(result.violations().contains("CONCAVE_WIDTH_TROUGH"));
    }

    @Test
    public void borePortalRunMarginsAreExcludedFromInteriorWidth() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(320, 0));
        HashSet<Long> ownedCells = new HashSet<>(thickChannel(
                List.of(point(0, 0), point(128, 0)),
                2,
                32
        ));
        ownedCells.addAll(thickChannel(
                List.of(point(192, 0), point(320, 0)),
                2,
                32
        ));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        15L,
                        centerline,
                        ownedCells,
                        false
                ));

        assertTrue(result.minimumInteriorWidth() >= 4);
        assertFalse(result.violations().contains("NARROW_INTERIOR_WIDTH"));
    }

    @Test
    public void sustainedMidCourseWidthTroughFailsConcavityGate() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(256, 0));
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(courseInput(
                        1337L,
                        13L,
                        centerline,
                        troughChannel(centerline, 5, 2, 96, 160),
                        false
                ));

        assertTrue(result.maximumWidthTroughDepthRatio() >= 0.35D);
        assertTrue(result.violations().contains("CONCAVE_WIDTH_TROUGH"));
    }

    @Test
    public void incompleteCourseSkipsCompleteCourseMorphologyGates() {
        List<HydrologyPoint> centerline = List.of(point(0, 0), point(160, 0));
        HydrologyPackProbe.CourseMorphologyInput input = new HydrologyPackProbe.CourseMorphologyInput(
                1337L,
                14L,
                9,
                16,
                384,
                0D,
                false,
                true,
                centerline,
                terminalBasinChannel(centerline, 1, 2, 32, true)
        );
        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(input);

        assertEquals(0, result.clippedTerminalCaps());
        assertEquals(0, result.minimumInteriorWidth());
        assertEquals(0D, result.maximumWidthTroughDepthRatio(), 0D);
        assertFalse(result.violations().contains("CLIPPED_TERMINAL_CAP"));
        assertFalse(result.violations().contains("NARROW_INTERIOR_WIDTH"));
        assertFalse(result.violations().contains("CONCAVE_WIDTH_TROUGH"));
    }

    @Test
    public void midCourseSpurCreatesUnexpectedLeaf() {
        List<HydrologyPoint> centerline = List.of(point(-80, 0), point(80, 0));
        HashSet<Long> ownedCells = new HashSet<>(thickChannel(centerline, 2, 16));
        ownedCells.addAll(thickChannel(List.of(point(0, 0), point(0, 96)), 2, 0));
        HydrologyPackProbe.SeedMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeSeed(
                        1337L,
                        List.of(courseInput(1337L, 6L, centerline, ownedCells, false))
                );

        assertTrue(result.unexpectedLeaves() > 0);
        assertTrue(result.violations().stream().anyMatch(
                (String violation) -> violation.contains("UNEXPECTED_BRANCH_LEAVES")));
        assertTrue(result.courses().getFirst().unexpectedLeaves() > 0);
    }

    @Test
    public void oceanIntegrityHelpersDetectOwnershipAndFloorMutation() {
        HydrologyColumnLayer apron = surfaceLayer(true, false);
        HydrologyColumnLayer inertLayer = surfaceLayer(false, false);
        HydrologyColumnLayer ownedTerrain = surfaceLayer(false, true);

        assertFalse(HydrologyPackProbe.ShapeMetrics.ownsOceanTerrain(apron));
        assertFalse(HydrologyPackProbe.ShapeMetrics.ownsOceanTerrain(inertLayer));
        assertTrue(HydrologyPackProbe.ShapeMetrics.ownsOceanTerrain(ownedTerrain));
        assertFalse(HydrologyPackProbe.ShapeMetrics.mutatesOceanTerrain(54, 54));
        assertTrue(HydrologyPackProbe.ShapeMetrics.mutatesOceanTerrain(54, 48));
    }

    @Test
    public void surfaceBankIntegrityRequiresFreeboardAroundOwnedFluid() {
        assertEquals(
                0,
                HydrologyPackProbe.ShapeMetrics.uncontainedSurfaceBankEdges(
                        surfaceBankFootprint(71),
                        Set.of(32L)
                )
        );
        assertEquals(
                1,
                HydrologyPackProbe.ShapeMetrics.uncontainedSurfaceBankEdges(
                        surfaceBankFootprint(70),
                        Set.of(32L)
                )
        );
        HashMap<Long, HydrologyColumnSample> missingBank = new HashMap<>(surfaceBankFootprint(71).columns());
        missingBank.remove(RiverFootprint.pack(1, 0));
        assertEquals(
                1,
                HydrologyPackProbe.ShapeMetrics.uncontainedSurfaceBankEdges(
                        new RiverFootprint(missingBank),
                        Set.of(32L)
                )
        );
    }

    @Test
    public void seedsWithoutSurfaceCoursesDoNotMaskPublishedFailures() {
        HydrologyPackProbe.CourseMorphologyResult acceptedCourse =
                new HydrologyPackProbe.CourseMorphologyResult(
                        642L, 5L, 20, 80D, 80D, 0, 0D, 0D, 0, 0D, 0,
                        0, 0, 0D, 0, 0, 0D, 0, 0D, 0, 0D, 0, List.of());
        HydrologyPackProbe.SeedMorphologyResult accepted =
                new HydrologyPackProbe.SeedMorphologyResult(642L, List.of(acceptedCourse), 0, List.of());
        HydrologyPackProbe.SeedMorphologyResult empty =
                new HydrologyPackProbe.SeedMorphologyResult(1337L, List.of(), 0, List.of());

        IllegalStateException perSeedFailure = assertThrows(
                IllegalStateException.class,
                () -> HydrologyPackProbe.PublishedMorphologyMetrics.requireValidResults(List.of(accepted, empty))
        );
        assertTrue(perSeedFailure.getMessage().contains("seed=1337"));
        IllegalStateException emptyFailure = assertThrows(
                IllegalStateException.class,
                () -> HydrologyPackProbe.PublishedMorphologyMetrics.requireValidResults(List.of(empty))
        );
        assertTrue(emptyFailure.getMessage().contains("NO_SURFACE_COURSES"));

        HydrologyPackProbe.CourseMorphologyResult rejectedCourse =
                new HydrologyPackProbe.CourseMorphologyResult(
                        52L, 5L, 20, 80D, 80D, 0, 0D, 0D, 0, 0D, 0,
                        0, 0, 0D, 0, 0, 0D, 0, 0D, 2, 0D, 0,
                        List.of("NARROW_INTERIOR_WIDTH"));
        HydrologyPackProbe.SeedMorphologyResult rejected =
                new HydrologyPackProbe.SeedMorphologyResult(
                        52L, List.of(rejectedCourse), 0, List.of("5:NARROW_INTERIOR_WIDTH"));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> HydrologyPackProbe.PublishedMorphologyMetrics.requireValidResults(
                        List.of(accepted, empty, rejected))
        );

        assertTrue(failure.getMessage().contains("seed=52"));
    }

    @Test
    public void completeSurfaceCourseRequiresTheConfiguredExposedLength() {
        List<HydrologyPoint> centerline = List.of(
                new HydrologyPoint(0, 72, 0),
                new HydrologyPoint(512, 72, 0)
        );
        HydrologyPackProbe.CourseMorphologyInput input = new HydrologyPackProbe.CourseMorphologyInput(
                642L,
                5L,
                9,
                4,
                384,
                40D,
                true,
                false,
                centerline,
                thickChannel(centerline, 4, 24)
        );

        HydrologyPackProbe.CourseMorphologyResult result =
                HydrologyPackProbe.PublishedMorphologyMetrics.analyzeCourse(input);

        assertTrue(result.violations().contains("INSUFFICIENT_EXPOSED_SURFACE"));
    }

    @Test
    public void writesTopDownPngAndJsonMetrics() throws Exception {
        File reportDirectory = temporaryFolder.newFolder("hydrology-reports");
        HydrologyPackProbe.CourseMorphologyResult course =
                new HydrologyPackProbe.CourseMorphologyResult(
                        1337L, 6L, 3, 2D, 2D, 0, 0D, 0D, 0, 0D, 0,
                        0, 0, 0D, 0, 0, 0D, 0, 0D, 0, 0D, 0, List.of());
        HydrologyPackProbe.SeedMorphologyResult result =
                new HydrologyPackProbe.SeedMorphologyResult(1337L, List.of(course), 0, List.of());
        Map<Long, Long> courseByCell = Map.of(
                RiverFootprint.pack(-1, 2), 6L,
                RiverFootprint.pack(0, 2), 6L,
                RiverFootprint.pack(1, 3), 6L
        );

        HydrologyPackProbe.PublishedMorphologyMetrics.writeReport(
                reportDirectory,
                result,
                courseByCell
        );

        File imageFile = new File(reportDirectory, "seed-1337-surface-footprint.png");
        File metricsFile = new File(reportDirectory, "seed-1337-surface-morphology.json");
        BufferedImage image = ImageIO.read(imageFile);
        String metrics = Files.readString(metricsFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(imageFile.isFile());
        assertTrue(metricsFile.isFile());
        assertEquals(3, image.getWidth());
        assertEquals(2, image.getHeight());
        assertTrue(metrics.contains("\"seed\": 1337"));
        assertTrue(metrics.contains("\"clippedTerminalCaps\": 0"));
        assertTrue(metrics.contains("\"minimumInteriorWidth\": 0"));
        assertTrue(metrics.contains("\"maximumWidthTroughDepthRatio\": 0.000000"));
        assertTrue(metrics.contains("\"unexpectedLeaves\": 0"));
    }

    private static HydrologyPackProbe.CourseMorphologyInput courseInput(
            long seed,
            long courseId,
            List<HydrologyPoint> centerline,
            Set<Long> ownedCells,
            boolean terminalExposed
    ) {
        return new HydrologyPackProbe.CourseMorphologyInput(
                seed,
                courseId,
                9,
                4,
                0,
                0D,
                true,
                terminalExposed,
                centerline,
                ownedCells
        );
    }

    private static Set<Long> thickChannel(List<HydrologyPoint> centerline, int radius, int sourceRamp) {
        List<HydrologyPoint> raster = raster(centerline);
        HashSet<Long> cells = new HashSet<>();
        for (int index = 0; index < raster.size(); index++) {
            int effectiveRadius = sourceRamp == 0
                    ? radius
                    : Math.min(radius, (int) StrictMath.round(radius * Math.min(1D, index / (double) sourceRamp)));
            addDisc(cells, raster.get(index), effectiveRadius);
        }
        return Set.copyOf(cells);
    }

    private static Set<Long> widenedSourceBasin(
            List<HydrologyPoint> centerline,
            int channelRadius,
            int basinRadius,
            int taperLength
    ) {
        List<HydrologyPoint> raster = raster(centerline);
        HashSet<Long> cells = new HashSet<>();
        for (int index = 0; index < raster.size(); index++) {
            double taper = Math.min(1D, index / (double) taperLength);
            int radius = channelRadius + (int) StrictMath.round((basinRadius - channelRadius) * (1D - taper));
            addDisc(cells, raster.get(index), radius);
        }
        return Set.copyOf(cells);
    }

    private static Set<Long> terminalBasinChannel(
            List<HydrologyPoint> centerline,
            int channelRadius,
            int basinRadius,
            int profileLength,
            boolean clipped
    ) {
        List<HydrologyPoint> raster = raster(centerline);
        HashSet<Long> cells = new HashSet<>();
        for (int index = 0; index < raster.size(); index++) {
            int sourceRadius = Math.min(
                    channelRadius,
                    (int) StrictMath.round(channelRadius * Math.min(1D, index / (double) profileLength))
            );
            int remaining = raster.size() - 1 - index;
            double terminalWeight = 1D - Math.min(1D, remaining / (double) profileLength);
            int radius = sourceRadius
                    + (int) StrictMath.round((basinRadius - sourceRadius) * terminalWeight);
            addDisc(cells, raster.get(index), radius);
        }
        if (!clipped) {
            return Set.copyOf(cells);
        }
        HydrologyPoint terminal = raster.getLast();
        cells.removeIf((Long packed) -> RiverFootprint.unpackX(packed) > terminal.x());
        return Set.copyOf(cells);
    }

    private static Set<Long> troughChannel(
            List<HydrologyPoint> centerline,
            int outerRadius,
            int innerRadius,
            int troughStart,
            int troughEnd
    ) {
        List<HydrologyPoint> raster = raster(centerline);
        HashSet<Long> cells = new HashSet<>();
        for (int index = 0; index < raster.size(); index++) {
            int sourceRadius = Math.min(
                    outerRadius,
                    (int) StrictMath.round(outerRadius * Math.min(1D, index / 32D))
            );
            int radius = index >= troughStart && index <= troughEnd ? innerRadius : outerRadius;
            addDisc(cells, raster.get(index), Math.min(sourceRadius, radius));
        }
        return Set.copyOf(cells);
    }

    private static HydrologyColumnLayer surfaceLayer(boolean oceanApron, boolean terrainOwned) {
        HydrologyFeatureRef feature = new HydrologyFeatureRef(
                31L,
                HydrologyFeatureType.MOUTH,
                32L,
                33L,
                0,
                70,
                0,
                1,
                0,
                false
        );
        return new HydrologyColumnLayer(
                feature,
                54,
                63,
                63,
                true,
                false,
                false,
                true,
                false,
                false,
                terrainOwned,
                false,
                oceanApron,
                "water",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded"
        );
    }

    private static RiverFootprint surfaceBankFootprint(int easternBankHeight) {
        HydrologyFeatureRef feature = new HydrologyFeatureRef(
                31L,
                HydrologyFeatureType.RIFFLE,
                32L,
                33L,
                0,
                63,
                0,
                1,
                0,
                false
        );
        HydrologyColumnLayer wetLayer = new HydrologyColumnLayer(
                feature,
                67,
                70,
                70,
                true,
                false,
                false,
                true,
                false,
                false,
                true,
                true,
                false,
                "water",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded"
        );
        HydrologyColumnSample center = new HydrologyColumnSample(
                0, 0, 80, 63, false, "parent", List.of(wetLayer));
        HydrologyColumnSample west = bankColumn(-1, 0, 71);
        HydrologyColumnSample east = bankColumn(1, 0, easternBankHeight);
        HydrologyColumnSample north = bankColumn(0, -1, 71);
        HydrologyColumnSample south = bankColumn(0, 1, 71);
        return new RiverFootprint(Map.of(
                RiverFootprint.pack(center.x(), center.z()), center,
                RiverFootprint.pack(west.x(), west.z()), west,
                RiverFootprint.pack(east.x(), east.z()), east,
                RiverFootprint.pack(north.x(), north.z()), north,
                RiverFootprint.pack(south.x(), south.z()), south
        ));
    }

    private static HydrologyColumnSample bankColumn(int x, int z, int naturalHeight) {
        return new HydrologyColumnSample(x, z, naturalHeight, 63, false, "parent", List.of());
    }

    private static List<HydrologyPoint> raster(List<HydrologyPoint> points) {
        ArrayList<HydrologyPoint> raster = new ArrayList<>();
        for (int pair = 0; pair < points.size() - 1; pair++) {
            HydrologyPoint start = points.get(pair);
            HydrologyPoint end = points.get(pair + 1);
            int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.z() - start.z()));
            for (int step = raster.isEmpty() ? 0 : 1; step <= steps; step++) {
                double progress = step / (double) steps;
                HydrologyPoint point = new HydrologyPoint(
                        (int) StrictMath.round(start.x() + (end.x() - start.x()) * progress),
                        64,
                        (int) StrictMath.round(start.z() + (end.z() - start.z()) * progress)
                );
                if (raster.isEmpty()
                        || point.x() != raster.getLast().x()
                        || point.z() != raster.getLast().z()) {
                    raster.add(point);
                }
            }
        }
        return List.copyOf(raster);
    }

    private static void addDisc(Set<Long> cells, HydrologyPoint center, int radius) {
        for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                if (deltaX * deltaX + deltaZ * deltaZ <= radius * radius) {
                    cells.add(RiverFootprint.pack(center.x() + deltaX, center.z() + deltaZ));
                }
            }
        }
    }

    private static HydrologyPoint point(int x, int z) {
        return new HydrologyPoint(x, 64, z);
    }

    private static HydrologyColumnSample column(int x, int z) {
        return new HydrologyColumnSample(x, z, 80, 63, false, "parent", List.of());
    }
}
