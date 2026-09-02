package art.arcane.iris.engine.hydrology;

import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.CaveVoxel;
import art.arcane.iris.engine.hydrology.cave.CaveVoxelView;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveAction;
import art.arcane.iris.engine.hydrology.cave.HydrologyCavePlan;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HydrologyPlannerTest {
    private static final HydrologyTileKey TILE = new HydrologyTileKey(0, 0);

    @Test
    public void biomeIncisionMultiplierCannotExceedTheConfiguredSurfaceMaximum() {
        assertEquals(6, HydrologyPlanner.permittedSurfaceIncision(6, 2D));
        assertEquals(3, HydrologyPlanner.permittedSurfaceIncision(6, 0.5D));
    }

    @Test
    public void outletFirstPlanIsAcyclicAndHydraulicallyNonRising() {
        HydrologyPlanner planner = planner(77L, standardSettings(4D, 0D, true, false, List.of()), rollingCoast(112));

        HydrologyTile tile = planner.plan(TILE);

        assertFalse("diagnostics=" + tile.diagnosticCandidates(), tile.outlets().isEmpty());
        assertTrue(tile.outlets().stream().allMatch(RiverOutlet::directOcean));
        assertTrue(tile.acyclic());
        assertFalse(surfaceCourses(tile).isEmpty());
        for (DrainageEdge edge : tile.edges()) {
            DrainageNode upstream = tile.node(edge.upstreamNodeId()).orElseThrow();
            DrainageNode downstream = tile.node(edge.downstreamNodeId()).orElseThrow();
            assertTrue(downstream.potential() < upstream.potential());
        }
        for (RiverCourse course : tile.courses()) {
            assertTrue(course.hydraulicallyNonRising());
            for (HydraulicSegment segment : course.segments()) {
                if (segment.drop() > 0) {
                    assertTrue(segment.type().isDrop());
                    assertFalse(segment.fallingFluid());
                }
            }
        }
    }

    @Test
    public void acceptedSurfaceCourseUsesOneDeterministicContinuousOrganicCurve() {
        HydrologyPlannerSettings settings = organicShapeSettings();
        HydrologyTerrainSampler terrain = organicShapeTerrain();
        HydrologyTile first = new HydrologyPlanner(642L, settings, terrain).plan(TILE);
        HydrologyTile replay = new HydrologyPlanner(642L, settings, terrain).plan(TILE);

        assertEquals(first.courses(), replay.courses());
        List<RiverCourse> courses = surfaceCourses(first);
        assertTrue(first.diagnosticCandidates().toString(), courses.size() <= 4);
        RiverCourse mainCourse = courses.getFirst();
        for (RiverCourse course : courses) {
            if (course.drainageEdges().size() > mainCourse.drainageEdges().size()) {
                mainCourse = course;
            }
        }
        ArrayList<HydrologyPoint> centerline = new ArrayList<>();
        for (HydraulicSegment segment : mainCourse.segments()) {
            for (HydrologyPoint point : segment.centerline()) {
                if (centerline.isEmpty() || !centerline.getLast().equals(point)) {
                    centerline.add(point);
                }
            }
        }
        assertTrue(centerline.size() >= 64);
        double pathLength = 0D;
        for (int index = 1; index < centerline.size(); index++) {
            HydrologyPoint previous = centerline.get(index - 1);
            HydrologyPoint point = centerline.get(index);
            pathLength += StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
        }
        HydrologyPoint start = centerline.getFirst();
        HydrologyPoint end = centerline.getLast();
        double directLength = StrictMath.hypot(end.x() - start.x(), end.z() - start.z());
        assertTrue("sinuosity=" + pathLength / directLength, pathLength / directLength <= 1.5D);
        assertTrue(
                "deviation=" + maximumChordDeviationRatio(centerline),
                maximumChordDeviationRatio(centerline) <= 0.19D
        );
        double maximumStickLength = maximumQuantizedStickLength(centerline);
        assertTrue(
                "maximumStickLength=" + maximumStickLength,
                maximumStickLength <= settings.routing().sampleSpacing() * 0.75D
        );
    }

    @Test
    public void fallbackSourceTargetCannotExceedAvailableOutlets() {
        assertEquals(1, HydrologyPlanner.effectiveSourceTarget(true, 8, 1));
        assertEquals(0, HydrologyPlanner.effectiveSourceTarget(true, 8, 0));
        assertEquals(8, HydrologyPlanner.effectiveSourceTarget(false, 8, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> HydrologyPlanner.effectiveSourceTarget(true, -1, 1)
        );
    }

    @Test
    public void zeroMouthLevelingDistanceKeepsAcceptedCoursesHydraulicallyNonRising() {
        HydrologyPlannerSettings base = standardSettings(4D, 0D, true, false, List.of());
        HydrologyPlannerSettings.Outlets configured = base.outlets();
        HydrologyPlannerSettings settings = withOutlets(base, new HydrologyPlannerSettings.Outlets(
                configured.oceanEnabled(),
                configured.coastalGrotto(),
                configured.inlandGrotto(),
                configured.surfaceSinkholesEnabled(),
                configured.coastalCliffMinimumHeight(),
                0,
                configured.maximumOceanApron(),
                configured.maximumPerTile()
        ));

        HydrologyTile tile = planner(78L, settings, rollingCoast(112)).plan(TILE);

        assertFalse(tile.diagnosticCandidates().toString(), surfaceCourses(tile).isEmpty());
        assertTrue(surfaceCourses(tile).stream().allMatch(RiverCourse::hydraulicallyNonRising));
    }

    @Test
    public void requiredSourceQuotaOnlyAppliesWhenAProvenOutletExists() {
        HydrologyPlannerSettings base = standardSettings(0D, 0D, true, false, List.of());
        HydrologyPlannerSettings settings = withSurfaceSources(base, new HydrologyPlannerSettings.Source(
                true,
                0D,
                80,
                0,
                0,
                24
        ));
        HydrologyTerrainSampler connected = (int x, int z) -> terrain(
                110 - Math.floorDiv(x, 16),
                1D,
                x >= 112,
                true,
                x >= 0 && x <= 16,
                true,
                false,
                false
        );
        HydrologyTerrainSampler disconnected = (int x, int z) -> terrain(
                110,
                0D,
                false,
                true,
                x >= 0 && x <= 16,
                true,
                false,
                false
        );

        HydrologyTile connectedTile = new HydrologyPlanner(81L, settings, connected).plan(TILE);
        HydrologyTile disconnectedTile = new HydrologyPlanner(81L, settings, disconnected).plan(TILE);

        assertEquals(0, settings.surface().sources().maximumPerTile());
        assertTrue(settings.publicationRadius() > 0);
        assertFalse(surfaceCourses(connectedTile).isEmpty());
        assertTrue(surfaceCourses(disconnectedTile).isEmpty());
        assertTrue(disconnectedTile.outlets().isEmpty());
    }

    @Test
    public void rejectedRequiredCourseBackfillsTheNextRankedRequiredCandidate() {
        HydrologyPlannerSettings base = standardSettings(0D, 0D, true, false, List.of());
        HydrologyPlannerSettings settings = withUndergroundSources(base, new HydrologyPlannerSettings.Source(
                true,
                0D,
                Integer.MIN_VALUE,
                0,
                0,
                32
        ));
        HydrologyTerrainSampler terrain = (int x, int z) -> terrain(
                120 - Math.floorDiv(x, 16) - (z >= 64 ? 4 : 0),
                1D,
                x >= 112,
                true,
                false,
                false,
                x == 0 && (z == 0 || z == 96),
                x == 0 && (z == 0 || z == 96)
        );
        HydrologyPlanner acceptedPlanner = new HydrologyPlanner(8101L, settings, terrain, solidCaveView());
        HydrologyTile accepted = acceptedPlanner.plan(TILE);
        RiverCourse initiallySelected = courses(accepted, RiverCourseType.UNDERGROUND).getFirst();
        DrainageNode initialSource = accepted.node(initiallySelected.sourceNodeId().orElseThrow()).orElseThrow();
        CavePosition hazard = accepted.cavePlan(initiallySelected.id()).orElseThrow().actions().entrySet().stream()
                .filter((Map.Entry<CavePosition, HydrologyCaveAction> entry) ->
                        entry.getKey().x() == initialSource.x()
                                && entry.getKey().z() == initialSource.z()
                                && entry.getValue() == HydrologyCaveAction.WET_SOURCE)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        HydrologyPlanner backfillPlanner = new HydrologyPlanner(
                8101L,
                settings,
                terrain,
                selectiveCaveView(Map.of(hazard, CaveVoxel.LAVA))
        );
        HydrologyTile backfilled = backfillPlanner.plan(TILE);
        HydrologyTile repeated = backfillPlanner.plan(TILE);

        assertEquals(backfilled, repeated);
        assertEquals(1, courses(backfilled, RiverCourseType.UNDERGROUND).size());
        RiverCourse replacement = courses(backfilled, RiverCourseType.UNDERGROUND).getFirst();
        DrainageNode replacementSource = backfilled.node(replacement.sourceNodeId().orElseThrow()).orElseThrow();
        assertTrue(initialSource.z() < replacementSource.z());
        assertFalse(replacement.sourceNodeId().equals(initiallySelected.sourceNodeId()));
        assertTrue(backfilled.diagnosticCandidates().stream().anyMatch(
                (HydrologyDiagnosticCandidate candidate) ->
                        candidate.rejection() == HydrologyCandidateRejection.CAVE_CONTAINMENT
                                && candidate.point().x() == initialSource.x()
                                && candidate.point().z() == initialSource.z()
        ));
    }

    @Test
    public void requiredBackfillRestoresTheRequestedAcceptedCount() {
        HydrologyPlannerSettings base = standardSettings(0D, 0D, true, false, List.of());
        HydrologyPlannerSettings settings = withUndergroundSources(base, new HydrologyPlannerSettings.Source(
                true,
                2D,
                Integer.MIN_VALUE,
                1,
                2,
                32
        ));
        HydrologyTerrainSampler terrain = (int x, int z) -> terrain(
                120 - Math.floorDiv(x, 16) - Math.floorDiv(Math.max(0, z), 48),
                1D,
                x >= 112,
                true,
                false,
                false,
                x == 0 && (z == 0 || z == 48 || z == 96),
                x == 0 && (z == 0 || z == 48 || z == 96)
        );
        HydrologyTile accepted = new HydrologyPlanner(8102L, settings, terrain, solidCaveView()).plan(TILE);
        List<RiverCourse> initialCourses = courses(accepted, RiverCourseType.UNDERGROUND);
        assertEquals(2, initialCourses.size());
        RiverCourse rejectedCourse = initialCourses.getFirst();
        CavePosition hazard = accepted.cavePlan(rejectedCourse.id()).orElseThrow().actions().entrySet().stream()
                .filter((Map.Entry<CavePosition, HydrologyCaveAction> entry) ->
                        entry.getValue() == HydrologyCaveAction.WET_SOURCE)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        Set<Long> initialSourceIds = new HashSet<>();
        for (RiverCourse course : initialCourses) {
            initialSourceIds.add(course.sourceNodeId().orElseThrow());
        }

        HydrologyTile filtered = new HydrologyPlanner(
                8102L,
                settings,
                terrain,
                selectiveCaveView(Map.of(hazard, CaveVoxel.LAVA))
        ).plan(TILE);

        List<RiverCourse> survivingCourses = courses(filtered, RiverCourseType.UNDERGROUND);
        assertEquals(2, survivingCourses.size());
        assertTrue(survivingCourses.stream().anyMatch(
                (RiverCourse course) -> initialSourceIds.contains(course.sourceNodeId().orElseThrow())
        ));
        assertTrue(survivingCourses.stream().anyMatch(
                (RiverCourse course) -> !initialSourceIds.contains(course.sourceNodeId().orElseThrow())
        ));
    }

    @Test
    public void rejectedOptionalCourseBackfillsTheRequestedAcceptedCount() {
        HydrologyPlannerSettings base = standardSettings(0D, 0D, true, false, List.of());
        HydrologyPlannerSettings settings = withUndergroundSources(base, new HydrologyPlannerSettings.Source(
                true,
                2D,
                Integer.MIN_VALUE,
                0,
                2,
                0
        ));
        HydrologyTerrainSampler terrain = (int x, int z) -> terrain(
                120 - Math.floorDiv(x, 16) - (z >= 64 ? 4 : 0),
                1D,
                x >= 112,
                true,
                false,
                false,
                x == 0 && (z == 0 || z == 48 || z == 96),
                false
        );
        HydrologyTile baseline = new HydrologyPlanner(8102L, settings, terrain, solidCaveView()).plan(TILE);
        List<RiverCourse> baselineCourses = courses(baseline, RiverCourseType.UNDERGROUND);
        assertEquals(2, baselineCourses.size());
        RiverCourse rejectedCourse = baselineCourses.getFirst();
        DrainageNode rejectedSource = baseline.node(rejectedCourse.sourceNodeId().orElseThrow()).orElseThrow();
        Set<Long> initialSourceIds = new HashSet<>();
        for (RiverCourse course : baselineCourses) {
            initialSourceIds.add(course.sourceNodeId().orElseThrow());
        }
        CavePosition hazard = baseline.cavePlan(rejectedCourse.id()).orElseThrow().actions().entrySet().stream()
                .filter((Map.Entry<CavePosition, HydrologyCaveAction> entry) ->
                        entry.getKey().x() == rejectedSource.x()
                                && entry.getKey().z() == rejectedSource.z()
                                && entry.getValue() == HydrologyCaveAction.WET_SOURCE)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        HydrologyTile rejected = new HydrologyPlanner(
                8102L,
                settings,
                terrain,
                selectiveCaveView(Map.of(hazard, CaveVoxel.LAVA))
        ).plan(TILE);
        List<RiverCourse> acceptedCourses = courses(rejected, RiverCourseType.UNDERGROUND);
        assertEquals(2, acceptedCourses.size());
        assertTrue(acceptedCourses.stream().anyMatch(
                (RiverCourse course) -> !initialSourceIds.contains(course.sourceNodeId().orElseThrow())
        ));
        assertTrue(acceptedCourses.stream().flatMap((RiverCourse course) -> course.drainageEdges().stream()).allMatch(
                (DrainageEdge edge) -> edge.contributingUndergroundSources() >= 1
        ));
    }

    @Test
    public void refinedRoutesRejectUnsampledOceanBarriersBetweenCoarseNodes() {
        HydrologyPlannerSettings settings = standardSettings(4D, 0D, true, false, List.of());
        HydrologyTerrainSampler terrain = (int x, int z) -> {
            if (x >= 4 && x <= 12 || x >= 112) {
                return oceanTerrain();
            }
            return terrain(
                    118 - Math.floorDiv(x, 12),
                    1D,
                    false,
                    true,
                    x == 0,
                    x == 0,
                    false,
                    false
            );
        };

        HydrologyTile tile = new HydrologyPlanner(82L, settings, terrain).plan(TILE);

        assertTrue(surfaceCourses(tile).isEmpty());
        assertTrue(tile.diagnosticCandidates().toString(), tile.diagnosticCandidates().stream().anyMatch(
                candidate -> candidate.rejection() == HydrologyCandidateRejection.NO_DRAINAGE_PATH
        ));
    }

    @Test
    public void rejectedCandidatesRemainIsolatedFromAcceptedGenerationRenderingAndLocators() {
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, false, List.of());
        HydrologyTerrainSampler terrain = (int x, int z) -> terrain(
                110,
                0D,
                false,
                true,
                x >= 0 && x <= 16,
                true,
                false,
                false
        );
        HydrologyTile tile = new HydrologyPlanner(181L, settings, terrain).plan(TILE);

        assertTrue(tile.courses().isEmpty());
        assertTrue(tile.footprint().isEmpty());
        assertTrue(tile.features().isEmpty());
        assertFalse(tile.diagnosticCandidates().isEmpty());
        for (HydrologyDiagnosticCandidate candidate : tile.diagnosticCandidates()) {
            assertEquals(HydrologyCandidateKind.SOURCE, candidate.kind());
            assertEquals(HydrologyFeatureType.SURFACE_POOL, candidate.projectedType());
            assertEquals(HydrologyCandidateRejection.NO_LEGAL_OUTLET, candidate.rejection());
            assertTrue(tile.columnAt(candidate.point().x(), candidate.point().z()).isEmpty());
            assertFalse(tile.renderAt(candidate.point().x(), candidate.point().z()).present());
            assertTrue(tile.nearestFeature(
                    candidate.projectedType(),
                    candidate.point().x(),
                    candidate.point().z(),
                    0
            ).isEmpty());
            HydrologyDiagnosticRenderSample diagnostic = tile.diagnosticRenderAt(
                    candidate.point().x(),
                    candidate.point().z(),
                    0
            );
            assertTrue(diagnostic.present());
            assertTrue(diagnostic.candidates().contains(candidate));
        }
    }

    @Test
    public void rollingTerrainProducesPooledStepsAndCliffsProduceWaterfalls() {
        HydrologyPlannerSettings settings = standardSettings(3D, 0D, true, false, List.of());
        HydrologyTerrainSampler rollingTerrain = rollingCoast(112);
        HydrologyTile rolling = new HydrologyPlanner(19L, settings, rollingTerrain).plan(TILE);
        HydrologyTerrainSampler cliffTerrain = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            int height = x < 64 ? 122 : 78;
            return terrain(height, x >= 56 && x <= 64 ? 24D : 1D, false, true, x <= 16, true, false, false);
        };
        HydrologyTile cliff = new HydrologyPlanner(19L, settings, cliffTerrain).plan(TILE);

        assertTrue(
                "diagnostics=" + rolling.diagnosticCandidates() + " segments=" + rolling.courses().stream()
                        .flatMap((RiverCourse course) -> course.segments().stream())
                        .map((HydraulicSegment segment) -> segment.type() + ":" + segment.drop())
                        .toList(),
                hasAny(rolling, HydrologyFeatureType.RIFFLE, HydrologyFeatureType.CASCADE)
        );
        for (HydraulicSegment segment : segments(rolling, HydrologyFeatureType.CASCADE)) {
            assertTrue(segment.drop() > 0);
            assertFalse(segment.fallingFluid());
            assertTrue(segment.centerline().size() >= segment.drop() + 1);
            int previousHead = segment.upstreamHeadY();
            for (HydrologyPoint point : segment.centerline()) {
                assertTrue(point.y() <= previousHead);
                assertTrue(previousHead - point.y() <= 1);
                assertTrue(rollingTerrain.sample(point.x() - 1, point.z()).naturalHeight() > point.y());
                assertTrue(rollingTerrain.sample(point.x() + 1, point.z()).naturalHeight() > point.y());
                assertTrue(rollingTerrain.sample(point.x(), point.z() - 1).naturalHeight() > point.y());
                assertTrue(rollingTerrain.sample(point.x(), point.z() + 1).naturalHeight() > point.y());
                previousHead = point.y();
            }
            assertEquals(segment.downstreamHeadY(), previousHead);
        }
        assertTrue(
                "features=" + cliff.features().stream().map(HydrologyFeatureRef::type).toList()
                        + " diagnostics=" + cliff.diagnosticCandidates().stream()
                        .map((HydrologyDiagnosticCandidate candidate) ->
                                candidate.projectedType() + ":" + candidate.rejection())
                        .toList(),
                hasAny(cliff, HydrologyFeatureType.WATERFALL)
        );
        for (HydraulicSegment segment : segments(cliff, HydrologyFeatureType.WATERFALL)) {
            assertTrue(segment.drop() > 0);
            assertFalse(segment.fallingFluid());
            assertTrue(segment.centerline().size() >= 2);
            assertTrue(segment.width() >= 1);
        }
        boolean sawBlendedWaterfall = false;
        for (RiverCourse course : cliff.courses()) {
            for (int index = 1; index < course.segments().size() - 1; index++) {
                HydraulicSegment waterfall = course.segments().get(index);
                if (waterfall.type() != HydrologyFeatureType.WATERFALL) {
                    continue;
                }
                HydraulicSegment approach = course.segments().get(index - 1);
                HydraulicSegment receiver = course.segments().get(index + 1);
                assertTrue(approach.type().isSurface());
                assertTrue(receiver.type().isSurface());
                assertEquals(approach.end(), waterfall.start());
                assertEquals(waterfall.end(), receiver.start());
                sawBlendedWaterfall = true;
            }
        }
        assertTrue(sawBlendedWaterfall);
    }

    @Test
    public void adjacentSurfaceDropsCollapseIntoSeparatedTransitionComplexes() {
        HydrologyPlannerSettings settings = standardSettings(1D, 0D, false, true, List.of());
        HydrologyTerrainSampler terrain = (int x, int z) -> {
            boolean source = x == 0 && z == 0;
            boolean outlet = x == 64 && z == 64;
            int distance = x + z;
            int height = tieredTransitionHeight(distance);
            return new HydrologyTerrainSample(
                    height,
                    1D,
                    false,
                    true,
                    72,
                    74,
                    true,
                    outlet,
                    source,
                    source,
                    false,
                    false,
                    0D,
                    source ? 1D : 0D,
                    0D,
                    1D,
                    1D,
                    1D,
                    1D,
                    1D,
                    "parent",
                    "surface",
                    "mouth",
                    "shore",
                    "dry",
                    "flooded",
                    List.of("alpha")
            );
        };
        HydrologyTile tile = new HydrologyPlanner(812L, settings, terrain, solidCaveView()).plan(TILE);

        assertFalse("diagnostics=" + tile.diagnosticCandidates(), surfaceCourses(tile).isEmpty());
        boolean aggregateTransition = false;
        for (RiverCourse course : surfaceCourses(tile)) {
            for (HydraulicSegment segment : course.segments()) {
                if (segment.drop() < settings.hydraulics().waterfallMinimumDrop()) {
                    continue;
                }
                if (segment.type() == HydrologyFeatureType.WATERFALL
                        || segment.type() == HydrologyFeatureType.UNDERGROUND_DROP) {
                    aggregateTransition = true;
                }
            }
        }
        assertTrue("courses=" + surfaceCourses(tile), aggregateTransition);
    }

    @Test
    public void anUnbridgeableTerrainCrevasseRejectsTheSurfaceCourse() {
        HydrologyTerrainSampler terrain = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            int height = x == 47
                    ? 82
                    : 118 - Math.floorDiv(x, 12) + (int) StrictMath.round(StrictMath.sin(z / 18D) * 2D);
            return terrain(height, 1D, false, true, x >= 0 && x <= 24, true, true, false);
        };
        HydrologyTile tile = new HydrologyPlanner(
                19L,
                standardSettings(3D, 0D, true, false, List.of()),
                terrain
        ).plan(TILE);

        assertTrue("courses=" + surfaceCourses(tile), surfaceCourses(tile).isEmpty());
        for (HydrologyColumnSample column : tile.footprint().columns().values()) {
            for (HydrologyColumnLayer layer : column.layers()) {
                if (!layer.feature().type().isSurface() || !layer.channel() || !layer.fluidOwned()) {
                    continue;
                }
                assertTrue(
                        column.x() + "," + column.z() + " " + layer.feature().type()
                                + " head=" + layer.fluidHeadY() + " natural=" + column.naturalHeight(),
                        layer.fluidHeadY() <= column.naturalHeight()
                );
            }
        }
    }

    @Test
    public void ridgeCoursesRequireConfiguredPublishedSurfaceExposure() {
        HydrologyPlannerSettings base = standardSettings(2D, 0D, true, false, List.of());
        HydrologyPlannerSettings.Routing routing = base.routing();
        HydrologyPlannerSettings settings = new HydrologyPlannerSettings(
                base.seaLevel(),
                new HydrologyPlannerSettings.Routing(
                        routing.tileSize(),
                        routing.sampleSpacing(),
                        routing.maximumRouteNodes(),
                        routing.maximumRouteLength(), 96, 0,
                        routing.valleyPreference(),
                        routing.uphillPenalty(),
                        routing.slopePenalty(),
                        routing.confluenceAttraction()
                ),
                base.surface(),
                base.hydraulics(),
                base.underground(),
                base.outlets(),
                base.geometry(),
                base.deepFluids()
        );
        HydrologyTerrainSampler repeatedRidges = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            int baseHeight = 108 - Math.floorDiv(x, 8);
            boolean ridge = x >= 48 && x <= 72 && Math.abs(z) < 24;
            int height = ridge ? baseHeight + 48 : baseHeight;
            return terrain(height, ridge ? 18D : 1D, false, true, x <= 16, true, false, false);
        };

        HydrologyTile tile = new HydrologyPlanner(993L, settings, repeatedRidges).plan(TILE);

        assertFalse("courses=" + surfaceCourses(tile), surfaceCourses(tile).isEmpty());
        for (RiverCourse course : surfaceCourses(tile)) {
            assertTrue("segments=" + course.segments(), course.segments().getFirst().type().isSurface());
            double exposedLength = 0D;
            double exposedRunLength = 0D;
            double longestExposedRunLength = 0D;
            for (HydraulicSegment segment : course.segments()) {
                if (segment.type().isSurface() && segment.type() != HydrologyFeatureType.MOUTH) {
                    double length = segmentLength(segment);
                    exposedLength += length;
                    exposedRunLength += length;
                    longestExposedRunLength = Math.max(longestExposedRunLength, exposedRunLength);
                } else {
                    exposedRunLength = 0D;
                }
            }
            assertTrue("segments=" + course.segments(), exposedLength >= 96D);
            assertTrue(
                    "segments=" + course.segments(),
                    longestExposedRunLength >= Math.min(96, settings.routing().sampleSpacing() * 2)
            );
        }
    }

    @Test
    public void undergroundSourcesUseAnIndependentBudgetAndConfiguredHeight() {
        HydrologyPlannerSettings settings = standardSettings(0D, 3D, true, false, List.of());
        HydrologyTerrainSampler undergroundCoast = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            int height = 118 - Math.floorDiv(x, 12) + (int) StrictMath.round(StrictMath.sin(z / 18D) * 2D);
            return terrain(height, 1D, false, true, false, false, x >= 0 && x <= 24, true);
        };
        HydrologyTile tile = new HydrologyPlanner(331L, settings, undergroundCoast).plan(TILE);

        assertTrue(surfaceCourses(tile).isEmpty());
        List<RiverCourse> underground = courses(tile, RiverCourseType.UNDERGROUND);
        assertFalse(underground.isEmpty());
        for (RiverCourse course : underground) {
            int head = course.segments().getFirst().upstreamHeadY();
            DrainageNode source = tile.node(course.sourceNodeId().orElseThrow()).orElseThrow();
            assertEquals(source.terrain().caveFluidY(), head);
            assertEquals(74, head);
            assertTrue(head >= settings.underground().minimumFluidY());
            assertTrue(head <= settings.underground().maximumFluidY());
        }
    }

    @Test
    public void undergroundSourcesHonorStyledFluidLevelAtEachAcceptedSource() {
        HydrologyPlannerSettings settings = standardSettings(0D, 4D, true, false, List.of());
        HydrologyTerrainSampler styledCoast = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            int height = 118 - Math.floorDiv(x, 12);
            int fluidY = 68 + Math.floorMod(x + z, 15);
            return undergroundTerrain(height, fluidY, x >= 0 && x <= 24);
        };
        HydrologyTile tile = new HydrologyPlanner(331L, settings, styledCoast).plan(TILE);
        HashSet<Integer> acceptedHeads = new HashSet<>();

        for (RiverCourse course : courses(tile, RiverCourseType.UNDERGROUND)) {
            DrainageNode source = tile.node(course.sourceNodeId().orElseThrow()).orElseThrow();
            int expected = Math.max(
                    settings.underground().minimumFluidY(),
                    Math.min(settings.underground().maximumFluidY(), source.terrain().caveFluidY())
            );
            int actual = course.segments().getFirst().upstreamHeadY();
            assertEquals(expected, actual);
            acceptedHeads.add(actual);
        }
        assertTrue(acceptedHeads.size() > 1);
    }

    @Test
    public void undergroundCoastalOutletRaisesStyledHeadToSeaLevelWithinConfiguredRange() {
        HydrologyPlannerSettings settings = withUndergroundFluidRange(
                standardSettings(0D, 4D, true, false, List.of()),
                40,
                63
        );
        HydrologyTerrainSampler coast = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            return undergroundTerrain(118 - Math.floorDiv(x, 12), 48, x >= 0 && x <= 24);
        };
        HydrologyGeometrySampler geometry = request -> request.field()
                == HydrologyGeometrySampler.Field.UNDERGROUND_FLUID_LEVEL
                ? 48
                : request.minimum();

        HydrologyTile tile = new HydrologyPlanner(
                332L,
                settings,
                coast,
                geometry,
                -4096,
                footprint -> solidCaveView()
        ).plan(TILE);

        List<RiverCourse> underground = courses(tile, RiverCourseType.UNDERGROUND);
        assertFalse(underground.isEmpty());
        for (RiverCourse course : underground) {
            RiverOutlet outlet = tile.outlet(course.outletId().orElseThrow()).orElseThrow();
            assertTrue(outlet.directOcean());
            assertEquals(settings.seaLevel(), course.segments().getFirst().upstreamHeadY());
            assertTrue(course.segments().getFirst().upstreamHeadY() >= settings.underground().minimumFluidY());
            assertTrue(course.segments().getFirst().upstreamHeadY() <= settings.underground().maximumFluidY());
            assertTrue(course.hydraulicallyNonRising());
        }
    }

    @Test
    public void undergroundCoastalOutletRejectsWhenSeaLevelExceedsConfiguredMaximum() {
        HydrologyPlannerSettings settings = withUndergroundFluidRange(
                standardSettings(0D, 4D, true, false, List.of()),
                40,
                62
        );
        HydrologyTerrainSampler coast = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            return undergroundTerrain(118 - Math.floorDiv(x, 12), 48, x >= 0 && x <= 24);
        };

        HydrologyTile tile = new HydrologyPlanner(333L, settings, coast, solidCaveView()).plan(TILE);

        assertTrue(courses(tile, RiverCourseType.UNDERGROUND).isEmpty());
        assertTrue(tile.diagnosticCandidates().stream().anyMatch(
                (HydrologyDiagnosticCandidate candidate) ->
                        candidate.rejection() == HydrologyCandidateRejection.OUTLET_LEVEL
        ));
    }

    @Test
    public void undergroundUsesInlandGrottoWhenCoastIsAboveItsFluidRange() {
        HydrologyPlannerSettings base = withUndergroundFluidRange(
                standardSettings(0D, 4D, true, true, List.of()),
                40,
                62
        );
        HydrologyPlannerSettings.Outlets configured = base.outlets();
        HydrologyPlannerSettings settings = withOutlets(base, new HydrologyPlannerSettings.Outlets(
                configured.oceanEnabled(),
                configured.coastalGrotto(),
                configured.inlandGrotto(),
                configured.surfaceSinkholesEnabled(),
                configured.coastalCliffMinimumHeight(),
                configured.mouthLevelingDistance(),
                configured.maximumOceanApron(),
                1
        ));
        HydrologyTerrainSampler coast = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            return undergroundTerrain(118 - Math.floorDiv(x, 12), 48, x >= 0 && x <= 24);
        };

        HydrologyTile tile = new HydrologyPlanner(
                333L,
                settings,
                coast,
                HydrologyGeometrySampler.deterministic(coast),
                -4096,
                footprint -> solidCaveView()
        ).plan(TILE);

        assertFalse(tile.diagnosticCandidates().toString(), courses(tile, RiverCourseType.UNDERGROUND).isEmpty());
        for (RiverCourse course : courses(tile, RiverCourseType.UNDERGROUND)) {
            RiverOutlet outlet = tile.outlet(course.outletId().orElseThrow()).orElseThrow();
            assertEquals(HydrologyFeatureType.INLAND_GROTTO, outlet.type());
            assertFalse(outlet.directOcean());
            assertTrue(course.hydraulicallyNonRising());
        }
    }

    @Test
    public void infeasibleUndergroundCourseSkipsWidthDependentRadialSampling() {
        HydrologyPlannerSettings base = standardSettings(0D, 1D, true, false, List.of());
        HydrologyPlannerSettings narrow = withUndergroundWidth(base, 4);
        HydrologyPlannerSettings wide = withUndergroundWidth(base, 12);
        HydrologyTerrainSampler terrain = (int x, int z) -> x >= 112
                ? oceanTerrain()
                : undergroundTerrain(72, 68, x == 0 && z == 0);
        AtomicInteger narrowSamples = new AtomicInteger();
        AtomicInteger wideSamples = new AtomicInteger();

        HydrologyTile narrowTile = plannerWithRoutingSampler(
                3331L,
                narrow,
                terrain,
                narrowSamples
        ).plan(TILE);
        HydrologyTile wideTile = plannerWithRoutingSampler(
                3331L,
                wide,
                terrain,
                wideSamples
        ).plan(TILE);

        assertTrue(courses(narrowTile, RiverCourseType.UNDERGROUND).isEmpty());
        assertTrue(courses(wideTile, RiverCourseType.UNDERGROUND).isEmpty());
        assertTrue(narrowTile.diagnosticCandidates().stream().anyMatch(
                (HydrologyDiagnosticCandidate candidate) ->
                        candidate.rejection() == HydrologyCandidateRejection.CAVE_CONTAINMENT
        ));
        assertTrue(wideTile.diagnosticCandidates().stream().anyMatch(
                (HydrologyDiagnosticCandidate candidate) ->
                        candidate.rejection() == HydrologyCandidateRejection.CAVE_CONTAINMENT
        ));
        assertEquals(narrowSamples.get(), wideSamples.get());
    }

    @Test
    public void undergroundInlandOutletKeepsStyledHeadAboveOutlet() {
        HydrologyPlannerSettings settings = standardSettings(0D, 1D, false, true, List.of());
        HydrologyGeometrySampler geometry = request -> {
            if (request.field() != HydrologyGeometrySampler.Field.UNDERGROUND_FLUID_LEVEL) {
                return request.minimum();
            }
            return request.x() < 32 && request.z() < 32 ? 79 : 71;
        };

        HydrologyTile tile = new HydrologyPlanner(
                334L,
                settings,
                inlandTerrain(false, true),
                geometry,
                -4096,
                footprint -> solidCaveView()
        ).plan(TILE);

        assertFalse(tile.diagnosticCandidates().toString(), courses(tile, RiverCourseType.UNDERGROUND).isEmpty());
        RiverCourse course = courses(tile, RiverCourseType.UNDERGROUND).getFirst();
        RiverOutlet outlet = tile.outlet(course.outletId().orElseThrow()).orElseThrow();
        assertFalse(
                "outlet=" + outlet.type() + " source=" + course.sourceNodeId()
                        + " diagnostics=" + tile.diagnosticCandidates(),
                outlet.directOcean()
        );
        assertEquals(71, outlet.connectionPoint().y());
        assertEquals(79, course.segments().getFirst().upstreamHeadY());
        assertEquals(71, course.segments().getLast().downstreamHeadY());
        assertTrue(course.hydraulicallyNonRising());
    }

    @Test
    public void inlandOutletSelectionUsesDeterministicDisplacedAnchors() {
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, true, List.of());
        AtomicInteger firstOffLatticeSamples = new AtomicInteger();
        AtomicInteger secondOffLatticeSamples = new AtomicInteger();

        HydrologyTile first = plannerWithRoutingSampler(
                335L,
                settings,
                inlandTerrain(false, false),
                firstOffLatticeSamples
        ).plan(TILE);
        HydrologyTile second = plannerWithRoutingSampler(
                335L,
                settings,
                inlandTerrain(false, false),
                secondOffLatticeSamples
        ).plan(TILE);

        assertEquals(first.outlets(), second.outlets());
        assertEquals(firstOffLatticeSamples.get(), secondOffLatticeSamples.get());
        assertTrue(firstOffLatticeSamples.get() > 0);
    }

    @Test
    public void undergroundHeadSolverMatchesExhaustiveBoundedSequences() {
        Random random = new Random(0x485944524f4c4f47L);
        for (int iteration = 0; iteration < 400; iteration++) {
            int length = 1 + random.nextInt(5);
            int[] preferred = new int[length];
            int[] minimum = new int[length];
            int[] maximum = new int[length];
            int[] solved = new int[length];
            for (int index = 0; index < length; index++) {
                minimum[index] = random.nextInt(7) - 2;
                maximum[index] = minimum[index] + random.nextInt(5);
                preferred[index] = random.nextInt(11) - 3;
            }
            int outlet = random.nextInt(11) - 3;

            boolean expected = bruteForceUndergroundHeads(minimum, maximum, outlet, 0, Integer.MAX_VALUE);
            boolean actual = HydrologyPlanner.solveUndergroundHeads(
                    preferred,
                    minimum,
                    maximum,
                    outlet,
                    solved
            );

            assertEquals(expected, actual);
            if (!actual) {
                continue;
            }
            assertEquals(outlet, solved[length - 1]);
            for (int index = 0; index < length; index++) {
                assertTrue(solved[index] >= minimum[index]);
                assertTrue(solved[index] <= maximum[index]);
                if (index > 0) {
                    assertTrue(solved[index - 1] >= solved[index]);
                }
            }
        }
    }

    @Test
    public void deepFluidPlacementIsIndependentAndHonorsItsOwnRangeAndShapeSwitches() {
        HydrologyPlannerSettings.DeepFluid deepFluid = new HydrologyPlannerSettings.DeepFluid(
                "deep_lava",
                true,
                4D,
                32,
                -180,
                -120,
                3,
                4,
                2,
                3,
                8,
                24,
                3,
                2,
                3,
                8192,
                3,
                false,
                true
        );
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, false, List.of(deepFluid));
        HydrologyTerrainSampler terrain = (int x, int z) -> terrain(90, 0D, false, true, false, false, false, false);

        HydrologyTile tile = new HydrologyPlanner(441L, settings, terrain).plan(TILE);

        assertTrue(surfaceCourses(tile).isEmpty());
        assertTrue(courses(tile, RiverCourseType.UNDERGROUND).isEmpty());
        List<RiverCourse> deepCourses = courses(tile, RiverCourseType.DEEP_FLUID);
        assertFalse(deepCourses.isEmpty());
        for (RiverCourse course : deepCourses) {
            assertEquals("deep_lava", course.profileKey());
            assertTrue(course.segments().stream().noneMatch(
                    (HydraulicSegment segment) -> segment.type() == HydrologyFeatureType.DEEP_POOL));
            assertTrue(course.segments().stream().allMatch(
                    (HydraulicSegment segment) -> segment.type() == HydrologyFeatureType.DEEP_CHANNEL));
            assertTrue(course.segments().stream().allMatch(
                    (HydraulicSegment segment) -> hasNonCollinearInterior(segment.centerline())));
            int head = course.segments().getFirst().upstreamHeadY();
            assertTrue(head >= -180 && head <= -120);
        }
    }

    @Test
    public void routingHaloFindsAnOceanOutsideTheOwnedTile() {
        HydrologyTerrainSampler neighboringCoast = (int x, int z) -> {
            if (x >= 144) {
                return oceanTerrain();
            }
            return terrain(118 - Math.floorDiv(x, 8), 1D, false, true, x <= 16, true, false, false);
        };
        HydrologyPlanner planner = new HydrologyPlanner(
                882L,
                standardSettings(2D, 0D, true, true, List.of()),
                neighboringCoast
        );
        HydrologyTile tile = planner.plan(TILE);

        assertFalse(
                "courses=" + tile.courses() + " diagnostics=" + tile.diagnosticCandidates(),
                tile.outlets().isEmpty()
        );
        assertTrue(tile.outlets().stream().allMatch(RiverOutlet::directOcean));
        assertTrue(tile.outlets().stream().anyMatch((RiverOutlet outlet) -> outlet.connectionPoint().x() >= 144));
        HydrologyColumnSample neighboringColumn = null;
        for (HydrologyColumnSample column : tile.footprint().columns().values()) {
            if (!TILE.contains(column.x(), column.z(), tile.tileSize()) && !column.ocean()) {
                neighboringColumn = column;
                break;
            }
        }
        assertNotNull(neighboringColumn);
        HydrologyColumnSample published = new HydrologyTileCache(planner, 8)
                .columnAt(neighboringColumn.x(), neighboringColumn.z())
                .orElseThrow();
        assertTrue(published.layers().containsAll(neighboringColumn.layers()));
    }

    @Test
    public void firstOceanClipPublishesNoRiverOwnedWritesBeyondTheCoast() {
        HydrologyTile tile = new HydrologyPlanner(
                52L,
                standardSettings(3D, 0D, true, false, List.of()),
                rollingCoast(112)
        ).plan(TILE);

        assertFalse(
                "courses=" + tile.courses() + " diagnostics=" + tile.diagnosticCandidates(),
                tile.footprint().isEmpty()
        );
        for (HydrologyColumnSample column : tile.footprint().columns().values()) {
            if (!column.ocean() && column.naturalHeight() > column.seaLevel()) {
                continue;
            }
            for (HydrologyColumnLayer layer : column.layers()) {
                assertFalse(layer.terrainOwned());
                assertFalse(layer.fluidOwned());
                assertFalse(layer.grading());
                assertFalse(layer.shore());
                assertTrue(layer.fluidHeadY() <= column.seaLevel());
            }
        }
    }

    @Test
    public void footprintPersistsResolvedProfileBiomeRolesAndParentGrading() {
        HydrologyTile tile = new HydrologyPlanner(
                712L,
                standardSettings(2D, 0D, true, false, List.of()),
                rollingCoast(112)
        ).plan(TILE);

        assertFalse("courses=" + tile.courses() + " diagnostics=" + tile.diagnosticCandidates(), tile.footprint().isEmpty());
        boolean sawGrading = false;
        for (HydrologyColumnSample column : tile.footprint().columns().values()) {
            assertEquals(column.ocean() ? "ocean_parent" : "parent", column.parentBiomeKey());
            for (HydrologyColumnLayer layer : column.layers()) {
                assertTrue(layer.profileKey().equals("alpha") || layer.profileKey().equals("beta"));
                assertEquals("surface", layer.surfaceBiomeKey());
                assertEquals("mouth", layer.mouthBiomeKey());
                assertEquals("shore", layer.shoreBiomeKey());
                assertEquals("dry", layer.bankBiomeKey());
                assertEquals("flooded", layer.floodedCaveBiomeKey());
                if (layer.grading() && !layer.shore() && !layer.channel()) {
                    sawGrading = true;
                    assertEquals("parent", column.parentBiomeKey());
                    assertEquals(layer.bankBiomeKey(), layer.biomeKey());
                }
            }
        }
        assertTrue(sawGrading);
    }

    @Test
    public void fractionalShoreContentAndPhysicalParentGradingRemainIndependent() {
        HydrologyPlannerSettings settings = standardSettings(3D, 0D, true, false, List.of());
        HydrologyTile tile = new HydrologyPlanner(501L, settings, rollingCoast(112)).plan(TILE);
        boolean sawFractionalShore = false;
        boolean sawChangedParentGrading = false;
        double maximumShoreDistance = 0D;
        double maximumGradingDistance = 0D;
        int maximumChannelRadius = 0;
        for (HydrologyColumnSample column : tile.footprint().columns().values()) {
            for (HydrologyColumnLayer layer : column.layers()) {
                if (layer.oceanApron() || !layer.feature().type().isSurface()) {
                    continue;
                }
                HydraulicSegment segment = segment(tile, layer.feature().segmentId());
                int channelRadius = Math.max(1, segment.width() / 2);
                maximumChannelRadius = Math.max(maximumChannelRadius, channelRadius);
                double distance = distanceToCenterline(
                        new HydrologyPoint(column.x(), layer.fluidHeadY(), column.z()),
                        segment.centerline()
                );
                if (layer.shore()) {
                    maximumShoreDistance = Math.max(maximumShoreDistance, distance - channelRadius);
                    sawFractionalShore |= distance > channelRadius + 1.25D;
                    assertEquals("shore", layer.biomeKey());
                } else if (layer.grading() && !layer.channel()) {
                    maximumGradingDistance = Math.max(maximumGradingDistance, distance - channelRadius);
                    sawChangedParentGrading |= layer.bedY() != column.naturalHeight();
                    assertEquals(layer.bankBiomeKey(), layer.biomeKey());
                    assertEquals("parent", column.parentBiomeKey());
                }
            }
        }

        assertTrue(
                "fractional=" + sawFractionalShore
                        + " grading=" + sawChangedParentGrading
                        + " shore=" + maximumShoreDistance
                        + " gradingDistance=" + maximumGradingDistance
                        + " radius=" + maximumChannelRadius
                        + " courses=" + surfaceCourses(tile).size()
                        + " edges=" + tile.edges().size(),
                sawFractionalShore
        );
        assertTrue(sawChangedParentGrading);
        assertTrue(maximumGradingDistance > 0D);
        assertTrue(maximumChannelRadius > 0);
    }

    @Test
    public void surfaceCompilationPublishesOneTrunkAndNoBranchesPerOutlet() {
        HydrologyPlannerSettings base = standardSettings(6D, 0D, true, false, List.of());
        HydrologyPlannerSettings.Surface deepeningSurface = new HydrologyPlannerSettings.Surface(
                base.surface().enabled(),
                base.surface().sources(),
                base.surface().minimumWidth(),
                base.surface().maximumWidth(),
                base.surface().minimumDepth(),
                8,
                128,
                base.surface().shoreWidth(),
                HydrologyPlannerSettings.Banks.defaults());
        HydrologyPlannerSettings shaped = new HydrologyPlannerSettings(
                base.seaLevel(),
                base.routing(),
                deepeningSurface,
                base.hydraulics(),
                base.underground(),
                base.outlets(),
                HydrologyPlannerSettings.Geometry.defaults(),
                base.deepFluids()
        );
        HydrologyPlannerSettings settings = withOutlets(shaped, new HydrologyPlannerSettings.Outlets(
                true,
                base.outlets().coastalGrotto(),
                base.outlets().inlandGrotto(),
                base.outlets().surfaceSinkholesEnabled(),
                base.outlets().coastalCliffMinimumHeight(),
                base.outlets().mouthLevelingDistance(),
                base.outlets().maximumOceanApron(),
                1
        ));
        HydrologyTile tile = new HydrologyPlanner(642L, settings, optionalRollingCoast(112)).plan(TILE);

        List<RiverCourse> courses = surfaceCourses(tile);
        assertFalse(courses.isEmpty());
        HashSet<Long> outletIds = new HashSet<>();
        for (RiverCourse course : courses) {
            outletIds.add(course.outletId().orElseThrow());
        }
        assertEquals(1, outletIds.size());
        assertEquals(1, courses.size());
        HydrologyFeatureType terminal = courses.getFirst().segments().getLast().type();
        assertTrue(terminal == HydrologyFeatureType.MOUTH
                || terminal == HydrologyFeatureType.COASTAL_GROTTO
                || terminal == HydrologyFeatureType.INLAND_GROTTO);
    }

    @Test
    public void everyAcceptedDropCompilesAContinuousBedAndReceivingBasin() {
        HydrologyTerrainSampler cliffTerrain = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            int height = x < 64 ? 124 : 78;
            return terrain(height, x >= 56 && x <= 64 ? 24D : 1D, false, true, x <= 16, true, false, false);
        };
        HydrologyPlannerSettings settings = standardSettings(3D, 0D, true, false, List.of());
        HydrologyTile tile = new HydrologyPlanner(
                221L,
                settings,
                cliffTerrain
        ).plan(TILE);
        List<HydraulicSegment> drops = new ArrayList<>();
        for (RiverCourse course : surfaceCourses(tile)) {
            for (HydraulicSegment segment : course.segments()) {
                if (segment.drop() > 0) {
                    drops.add(segment);
                }
            }
        }

        assertFalse(drops.isEmpty());
        for (HydraulicSegment drop : drops) {
            boolean bed = false;
            boolean falling = false;
            boolean receiving = false;
            for (HydrologyColumnSample column : tile.footprint().columns().values()) {
                for (HydrologyColumnLayer layer : column.layers()) {
                    if (layer.feature().segmentId() != drop.id()) {
                        continue;
                    }
                    if (!layer.channel()) {
                        continue;
                    }
                    falling |= layer.fallingFluid();
                    assertTrue(layer.bedY() < layer.fluidHeadY());
                    bed = true;
                    if (layer.receivingPool()) {
                        receiving = true;
                        assertEquals(drop.downstreamHeadY(), layer.fluidHeadY());
                    }
                }
            }
            assertTrue(bed);
            assertEquals(drop.fallingFluid(), falling);
            assertFalse(drop.fallingFluid());
            if (!receiving) {
                HydrologyColumnSample endColumn = tile.columnAt(drop.end().x(), drop.end().z()).orElseThrow();
                receiving = endColumn.layers().stream().anyMatch((HydrologyColumnLayer layer) ->
                        layer.channel() && layer.fluidHeadY() == drop.downstreamHeadY());
            }
            assertTrue("drop=" + drop, receiving);
            if (drop.type() == HydrologyFeatureType.WATERFALL) {
                assertTrue(
                        "waterfall drop=" + drop.drop(),
                        drop.drop() >= settings.surface().banks().waterfallMinimumDrop()
                );
                continue;
            }
            int previousHead = drop.upstreamHeadY();
            for (HydrologyPoint point : drop.centerline()) {
                assertTrue(point.y() <= previousHead);
                assertTrue(
                        drop.type() + " drop=" + drop.drop() + " points=" + drop.centerline().size()
                                + " step=" + (previousHead - point.y()),
                        previousHead - point.y() <= settings.geometry().drops().stepLimit(drop.type())
                );
                previousHead = point.y();
            }
        }
    }

    @Test
    public void coastalGrottoIsASeaLevelEllipsoidWithADirectOceanConnection() {
        HydrologyPlannerSettings base = standardSettings(2D, 0D, true, true, List.of());
        HydrologyPlannerSettings settings = withOutlets(base, new HydrologyPlannerSettings.Outlets(
                true,
                new HydrologyPlannerSettings.Grotto(true, 4, 3, 3, 4096),
                base.outlets().inlandGrotto(),
                base.outlets().surfaceSinkholesEnabled(),
                12,
                base.outlets().mouthLevelingDistance(),
                base.outlets().maximumOceanApron(),
                base.outlets().maximumPerTile()
        ));
        HydrologyTerrainSampler cliffCoast = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            return terrain(92, 2D, false, true, x <= 16, true, false, false);
        };
        HydrologyTile tile = new HydrologyPlanner(994L, settings, cliffCoast).plan(TILE);

        assertFalse("diagnostics=" + tile.diagnosticCandidates(), tile.outlets().isEmpty());
        for (RiverOutlet outlet : tile.outlets()) {
            assertEquals("outlet=" + outlet + " diagnostics=" + tile.diagnosticCandidates(),
                    HydrologyFeatureType.COASTAL_GROTTO, outlet.type());
            assertTrue(outlet.directOcean());
            assertEquals(settings.seaLevel(), outlet.connectionPoint().y());
            assertFalse(cliffCoast.sample(outlet.landwardPoint().x(), outlet.landwardPoint().z()).ocean());
            assertTrue(cliffCoast.sample(outlet.connectionPoint().x(), outlet.connectionPoint().z()).ocean());
            assertEquals(1L, outlet.landwardPoint().distanceSquared2D(outlet.connectionPoint()));
        }
        RiverCourse course = surfaceCourses(tile).stream()
                .filter((RiverCourse candidate) -> candidate.segments().getLast().type()
                        == HydrologyFeatureType.COASTAL_GROTTO)
                .findFirst()
                .orElseThrow();
        HydraulicSegment grotto = course.segments().getLast();
        assertEquals(HydrologyFeatureType.COASTAL_GROTTO, grotto.type());
        assertEquals(settings.seaLevel(), grotto.upstreamHeadY());
        HydrologyColumnLayer center = layerForSegment(
                tile.columnAt(grotto.start().x(), grotto.start().z()).orElseThrow(),
                grotto.id()
        );
        assertTrue(center.bedY() < settings.seaLevel());
        assertTrue(center.bedY() >= settings.seaLevel()
                - settings.outlets().coastalGrotto().verticalRadius() * 2);
        assertEquals(settings.seaLevel() + settings.outlets().coastalGrotto().headroom(), center.ceilingY());
        double maximumOwnedRadius = settings.outlets().coastalGrotto().horizontalRadius() + 0.25D;
        for (HydrologyColumnSample column : tile.footprint().columns().values()) {
            for (HydrologyColumnLayer layer : column.layers()) {
                if (layer.feature().segmentId() == grotto.id() && layer.terrainOwned()) {
                    assertTrue(StrictMath.hypot(
                            column.x() - grotto.start().x(),
                            column.z() - grotto.start().z()
                    ) <= maximumOwnedRadius);
                }
            }
        }
    }

    @Test
    public void configuredSurfaceRiverContinuesThroughOneContainedSinkholeCourse() {
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, true, List.of());
        HydrologyTerrainSampler terrain = inlandTerrain(true, false);
        HydrologyPlanner planner = new HydrologyPlanner(7012L, settings, terrain, solidCaveView());

        HydrologyTile first = planner.plan(TILE);
        planner.plan(new HydrologyTileKey(1, 0));
        HydrologyTile repeated = planner.plan(TILE);

        assertEquals(first, repeated);
        assertEquals(first.diagnosticCandidates().toString(), 1, surfaceCourses(first).size());
        RiverCourse course = surfaceCourses(first).getFirst();
        assertTrue(course.surfaceSinkholeContinuation());
        RiverOutlet outlet = first.outlet(course.outletId().orElseThrow()).orElseThrow();
        assertEquals(HydrologyFeatureType.INLAND_GROTTO, outlet.type());
        assertFalse(outlet.directOcean());
        assertEquals(1L, outlet.landwardPoint().distanceSquared2D(outlet.connectionPoint()));

        HydraulicSegment sinkhole = course.segments().get(course.segments().size() - 2);
        HydraulicSegment grotto = course.segments().getLast();
        HydraulicSegment approach = course.segments().get(course.segments().size() - 3);
        assertEquals(HydrologyFeatureType.SINKHOLE, sinkhole.type());
        assertEquals(HydrologyFeatureType.INLAND_GROTTO, grotto.type());
        assertFalse(approach.type() == HydrologyFeatureType.SURFACE_POOL
                && approach.centerline().size() == 1);
        assertEquals(approach.end(), sinkhole.start());
        assertEquals(outlet.landwardPoint().x(), sinkhole.start().x());
        assertEquals(outlet.landwardPoint().z(), sinkhole.start().z());
        assertTrue(Math.abs(approach.width() - sinkhole.width()) <= 1);
        assertEquals(course.id(), sinkhole.courseId());
        assertEquals(course.id(), grotto.courseId());
        assertTrue(sinkhole.drop() > 0);
        assertFalse(sinkhole.fallingFluid());
        assertTrue(sinkhole.receivingPool());
        assertEquals(sinkhole.downstreamHeadY(), grotto.upstreamHeadY());

        HydrologyCavePlan cavePlan = first.cavePlan(course.id()).orElseThrow();
        assertTrue(cavePlan.accepted());
        assertEquals(course.id(), cavePlan.source().sourceId());
        Set<HydrologyCaveAction> actions = new HashSet<>(cavePlan.actions().values());
        assertFalse(actions.contains(HydrologyCaveAction.FALLING_FLUID));
        assertTrue(actions.contains(HydrologyCaveAction.WET_SOURCE));
        assertTrue(actions.contains(HydrologyCaveAction.DRY_AIR));

        HydrologyRenderSample render = first.renderAt(sinkhole.start().x(), sinkhole.start().z());
        assertTrue(render.hasFeature(HydrologyFeatureType.SINKHOLE));
        assertEquals(HydrologyFeatureType.SINKHOLE, render.primaryFeature().orElseThrow().type());
        HydrologyFeatureRef located = first.nearestFeature(
                HydrologyFeatureType.SINKHOLE,
                sinkhole.start().x(),
                sinkhole.start().z(),
                0
        ).orElseThrow();
        assertEquals(course.id(), located.courseId());
        assertEquals(sinkhole.id(), located.segmentId());
    }

    @Test
    public void disabledSurfaceSinkholesRejectSurfaceSourcesButKeepUndergroundSourcesEligible() {
        HydrologyPlannerSettings base = standardSettings(0D, 1D, false, true, List.of());
        HydrologyPlannerSettings.Outlets configured = base.outlets();
        HydrologyPlannerSettings settings = withOutlets(base, new HydrologyPlannerSettings.Outlets(
                configured.oceanEnabled(),
                configured.coastalGrotto(),
                configured.inlandGrotto(),
                false,
                configured.coastalCliffMinimumHeight(),
                configured.mouthLevelingDistance(),
                configured.maximumOceanApron(),
                configured.maximumPerTile()
        ));

        HydrologyTile tile = new HydrologyPlanner(
                7013L,
                settings,
                inlandTerrain(true, true),
                solidCaveView()
        ).plan(TILE);

        assertTrue(surfaceCourses(tile).isEmpty());
        assertFalse(courses(tile, RiverCourseType.UNDERGROUND).isEmpty());
        assertTrue(tile.diagnosticCandidates().stream().anyMatch(
                (HydrologyDiagnosticCandidate candidate) -> candidate.projectedType() == HydrologyFeatureType.SURFACE_POOL
                        && candidate.rejection() == HydrologyCandidateRejection.NO_LEGAL_OUTLET
        ));
        assertTrue(courses(tile, RiverCourseType.UNDERGROUND).stream().allMatch(
                (RiverCourse course) -> course.segments().getLast().type() == HydrologyFeatureType.INLAND_GROTTO
        ));
    }

    @Test
    public void directOceanOutletsTakePriorityOverConfiguredSurfaceSinkholes() {
        HydrologyPlannerSettings settings = standardSettings(2D, 0D, true, true, List.of());

        HydrologyTile tile = new HydrologyPlanner(7014L, settings, rollingCoast(112)).plan(TILE);

        assertFalse(surfaceCourses(tile).isEmpty());
        assertTrue(tile.outlets().stream().allMatch(RiverOutlet::directOcean));
        assertFalse(hasAny(tile, HydrologyFeatureType.SINKHOLE, HydrologyFeatureType.INLAND_GROTTO));
    }

    @Test
    public void rejectedOceanCourseFallsBackToOneContainedSurfaceSinkhole() {
        HydrologyPlannerSettings base = standardSettings(1D, 0D, true, true, List.of());
        HydrologyPlannerSettings.Surface surface = base.surface();
        HydrologyPlannerSettings settings = new HydrologyPlannerSettings(
                base.seaLevel(),
                base.routing(),
                new HydrologyPlannerSettings.Surface(
                        surface.enabled(),
                        surface.sources(),
                        surface.minimumWidth(),
                        surface.maximumWidth(),
                        surface.minimumDepth(),
                        surface.maximumDepth(),
                        surface.maximumIncision(),
                        surface.shoreWidth(),
                        HydrologyPlannerSettings.Banks.defaults()),
                base.hydraulics(),
                base.underground(),
                base.outlets(),
                base.geometry(),
                base.deepFluids()
        );
        HydrologyTerrainSampler terrain = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            boolean source = x == 0 && z == 0;
            int height = 120 + Math.floorDiv(Math.abs(x) + Math.abs(z - 96), 16);
            if (x >= 32 && x <= 96) {
                height += 64;
            }
            return terrain(height, 1D, false, true, source, source, false, false);
        };

        HydrologyTile tile = new HydrologyPlanner(70142L, settings, terrain, solidCaveView()).plan(TILE);

        assertEquals(tile.diagnosticCandidates().toString(), 1, surfaceCourses(tile).size());
        RiverCourse course = surfaceCourses(tile).getFirst();
        RiverOutlet outlet = tile.outlet(course.outletId().orElseThrow()).orElseThrow();
        assertFalse(outlet.directOcean());
        assertTrue(course.surfaceSinkholeContinuation());
        assertTrue(course.segments().stream().noneMatch(HydraulicSegment::fallingFluid));
    }

    @Test
    public void inlandOutletsServeOnlyComponentsWithoutAValidOceanOutlet() {
        HydrologyPlannerSettings settings = standardSettings(2D, 0D, true, true, List.of());
        HydrologyTerrainSampler terrain = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            if (x == 48) {
                return blockedTerrain();
            }
            boolean source = (x == 0 || x == 80) && z == 0;
            return terrain(120 - Math.floorDiv(x, 16), 1D, false, true, source, source, false, false);
        };

        HydrologyTile tile = new HydrologyPlanner(70140L, settings, terrain, solidCaveView()).plan(TILE);

        assertTrue(
                tile.diagnosticCandidates().toString(),
                tile.outlets().stream().anyMatch((RiverOutlet outlet) -> !outlet.directOcean())
        );
        RiverCourse course = surfaceCourses(tile).stream()
                .filter((RiverCourse candidate) -> tile.outlet(candidate.outletId().orElseThrow())
                        .map((RiverOutlet candidateOutlet) -> !candidateOutlet.directOcean())
                        .orElse(false))
                .findFirst()
                .orElseThrow();
        RiverOutlet outlet = tile.outlet(course.outletId().orElseThrow()).orElseThrow();
        assertFalse(outlet.directOcean());
        assertTrue(hasAny(tile, HydrologyFeatureType.SINKHOLE, HydrologyFeatureType.INLAND_GROTTO));
    }

    @Test
    public void sourceAdmissionRepresentsAConfiguredInlandDrainageComponent() {
        HydrologyPlannerSettings settings = standardSettings(1D, 0D, true, true, List.of());
        HydrologyTerrainSampler terrain = (int x, int z) -> {
            if (x >= 112) {
                return oceanTerrain();
            }
            if (x == 48) {
                return blockedTerrain();
            }
            boolean source = (x == 0 || x == 80) && z == 0;
            int height = x < 48 ? 90 : 130 - Math.floorDiv(x, 16);
            return terrain(height, 1D, false, true, source, false, false, false);
        };

        HydrologyTile tile = new HydrologyPlanner(70141L, settings, terrain, solidCaveView()).plan(TILE);

        assertEquals(1, surfaceCourses(tile).size());
        RiverCourse course = surfaceCourses(tile).getFirst();
        RiverOutlet outlet = tile.outlet(course.outletId().orElseThrow()).orElseThrow();
        assertFalse(
                "outlet=" + outlet.type() + " source=" + course.sourceNodeId()
                        + " diagnostics=" + tile.diagnosticCandidates(),
                outlet.directOcean()
        );
        assertTrue(hasAny(tile, HydrologyFeatureType.SINKHOLE, HydrologyFeatureType.INLAND_GROTTO));
    }

    @Test
    public void oneSinkholeContainmentHazardRejectsTheConflictingCandidateTransaction() {
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, true, List.of());
        HydrologyTerrainSampler terrain = inlandTerrain(true, false);
        HydrologyTile accepted = new HydrologyPlanner(7015L, settings, terrain, solidCaveView()).plan(TILE);
        RiverCourse acceptedCourse = surfaceCourses(accepted).getFirst();
        HydrologyCavePlan acceptedPlan = accepted.cavePlan(acceptedCourse.id()).orElseThrow();
        CavePosition hazard = acceptedPlan.actions().entrySet().stream()
                .filter((Map.Entry<CavePosition, HydrologyCaveAction> entry) ->
                        entry.getValue() == HydrologyCaveAction.WET_SOURCE)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        HydrologyTile rejected = new HydrologyPlanner(
                7015L,
                settings,
                terrain,
                selectiveCaveView(Map.of(hazard, CaveVoxel.INCOMPATIBLE_FLUID))
        ).plan(TILE);

        assertTrue(surfaceCourses(rejected).stream().noneMatch(acceptedCourse::equals));
        assertTrue(rejected.cavePlans().stream().noneMatch(
                (HydrologyCavePlan plan) -> plan.actions().containsKey(hazard)
        ));
        assertTrue(rejected.diagnosticCandidates().stream().anyMatch(
                (HydrologyDiagnosticCandidate candidate) ->
                        candidate.rejection() == HydrologyCandidateRejection.CAVE_CONTAINMENT
        ));
    }

    @Test
    public void receivingSinkholePoolSealsGeneratedSurfaceConnectedCarving() {
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, true, List.of());
        HydrologyTerrainSampler terrain = inlandTerrain(true, false);
        HydrologyTile accepted = new HydrologyPlanner(7016L, settings, terrain, solidCaveView()).plan(TILE);
        RiverCourse course = surfaceCourses(accepted).getFirst();
        HydraulicSegment sinkhole = course.segments().get(course.segments().size() - 2);
        HydraulicSegment grotto = course.segments().getLast();
        CavePosition surfaceLip = new CavePosition(
                sinkhole.start().x(),
                sinkhole.upstreamHeadY(),
                sinkhole.start().z()
        );
        CavePosition exposedPool = new CavePosition(
                grotto.start().x(),
                grotto.upstreamHeadY(),
                grotto.start().z()
        );
        assertEquals(HydrologyCaveAction.WET_SOURCE,
                accepted.cavePlan(course.id()).orElseThrow().actions().get(exposedPool));

        HydrologyTile intentionalOpening = new HydrologyPlanner(
                7016L,
                settings,
                terrain,
                caveView(Map.of(), Set.of(surfaceLip))
        ).plan(TILE);
        assertEquals(1, surfaceCourses(intentionalOpening).size());

        HydrologyTile sealed = new HydrologyPlanner(
                7016L,
                settings,
                terrain,
                caveView(Map.of(), Set.of(exposedPool))
        ).plan(TILE);

        assertEquals(1, surfaceCourses(sealed).size());
        RiverCourse sealedCourse = surfaceCourses(sealed).getFirst();
        assertTrue(sealed.cavePlan(sealedCourse.id()).orElseThrow()
                .baselinePreconditions().get(exposedPool).openToSurface());
    }

    @Test
    public void containedDeepPoolsUseIndependentHeightAndBoundedEllipsoidFootprints() {
        HydrologyPlannerSettings.DeepFluid deepFluid = new HydrologyPlannerSettings.DeepFluid(
                "deep_lava",
                true,
                4D,
                32,
                -180,
                -120,
                8,
                8,
                3,
                3,
                8,
                24,
                3,
                2,
                5,
                2048,
                3,
                true,
                true
        );
        HydrologyPlannerSettings settings = standardSettings(0D, 0D, false, false, List.of(deepFluid));
        HydrologyTerrainSampler highCaves = (int x, int z) -> deepTerrain(70);
        HydrologyTerrainSampler lowCaves = (int x, int z) -> deepTerrain(-40);
        HydrologyTile high = new HydrologyPlanner(1991L, settings, highCaves).plan(TILE);
        HydrologyTile low = new HydrologyPlanner(1991L, settings, lowCaves).plan(TILE);

        assertEquals(high.courses(), low.courses());
        List<RiverCourse> courses = courses(high, RiverCourseType.DEEP_FLUID);
        assertFalse(courses.isEmpty());
        for (RiverCourse course : courses) {
            HydraulicSegment pool = course.segments().getFirst();
            assertEquals(HydrologyFeatureType.DEEP_POOL, pool.type());
            assertTrue(course.segments().stream().anyMatch(
                    (HydraulicSegment segment) -> segment.type() == HydrologyFeatureType.DEEP_CHANNEL));
            assertTrue(pool.upstreamHeadY() >= deepFluid.minimumY());
            assertTrue(pool.upstreamHeadY() <= deepFluid.maximumY());
            int radius = pool.width() / 2;
            HydrologyColumnLayer center = layerForSegment(
                    high.columnAt(pool.start().x(), pool.start().z()).orElseThrow(),
                    pool.id()
            );
            HydrologyColumnLayer edge = null;
            int direction = pool.start().x() + radius < settings.routing().tileSize() ? 1 : -1;
            for (int distance = radius; distance >= 1 && edge == null; distance--) {
                HydrologyColumnSample sample = high.columnAt(
                        pool.start().x() + direction * distance,
                        pool.start().z()
                ).orElse(null);
                if (sample != null) {
                    edge = layerForSegmentOrNull(sample, pool.id());
                }
            }
            assertNotNull(edge);
            assertTrue(center.bedY() < edge.bedY());
            assertTrue(center.ceilingY() > edge.ceilingY());
            assertEquals("deep_lava", center.profileKey());
            assertEquals("flooded", center.biomeKey());
            assertTrue(hasRotationalAsymmetry(high, pool, radius));
            long poolVolume = 0L;
            long courseVolume = 0L;
            for (HydrologyColumnSample column : high.footprint().columns().values()) {
                for (HydrologyColumnLayer layer : column.layers()) {
                    if (layer.feature().segmentId() == pool.id()) {
                        poolVolume += layer.ceilingY() - layer.bedY() + 1L;
                    }
                    if (layer.feature().courseId() == course.id()) {
                        courseVolume += layer.ceilingY() - layer.bedY() + 1L;
                    }
                }
            }
            assertTrue(poolVolume <= deepFluid.maximumVolume());
            assertTrue(courseVolume <= deepFluid.maximumVolume());
        }
    }

    @Test
    public void identicalInputsProduceEqualPlansAcrossRequestOrder() {
        HydrologyPlanner planner = new HydrologyPlanner(
                99121L,
                standardSettings(3D, 2D, true, false, List.of()),
                rollingCoast(112)
        );

        HydrologyTile firstZero = planner.plan(new HydrologyTileKey(0, 0));
        HydrologyTile firstOne = planner.plan(new HydrologyTileKey(1, 0));
        HydrologyTile secondOne = planner.plan(new HydrologyTileKey(1, 0));
        HydrologyTile secondZero = planner.plan(new HydrologyTileKey(0, 0));

        assertEquals(firstZero, secondZero);
        assertEquals(firstOne, secondOne);
    }

    private HydrologyPlanner planner(
            long seed,
            HydrologyPlannerSettings settings,
            HydrologyTerrainSampler sampler
    ) {
        return new HydrologyPlanner(seed, settings, sampler);
    }

    private boolean containsEdge(RiverCourse course, long edgeId) {
        for (DrainageEdge edge : course.drainageEdges()) {
            if (edge.id() == edgeId) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSurfaceEdge(HydrologyTile tile, long edgeId) {
        for (RiverCourse course : surfaceCourses(tile)) {
            if (containsEdge(course, edgeId)) {
                return true;
            }
        }
        return false;
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

    private int tieredTransitionHeight(int distance) {
        if (distance < 32) {
            return 132;
        }
        if (distance < 48) {
            return 124;
        }
        if (distance < 64) {
            return 120;
        }
        return distance < 80 ? 106 : 98;
    }

    private HydrologyPlannerSettings standardSettings(
            double surfaceDensity,
            double undergroundDensity,
            boolean oceanEnabled,
            boolean inlandEnabled,
            List<HydrologyPlannerSettings.DeepFluid> deepFluids
    ) {
        HydrologyPlannerSettings.Source surfaceSources = new HydrologyPlannerSettings.Source(
                true,
                surfaceDensity,
                80,
                0,
                6,
                24
        );
        HydrologyPlannerSettings.Source undergroundSources = new HydrologyPlannerSettings.Source(
                true,
                undergroundDensity,
                Integer.MIN_VALUE,
                0,
                4,
                32
        );
        return new HydrologyPlannerSettings(
                63,
                new HydrologyPlannerSettings.Routing(128, 16, 512, 256, 0, 0, 0.5D, 12D, 0.5D, 0.1D),
                new HydrologyPlannerSettings.Surface(
                        surfaceDensity > 0D || surfaceSources.maximumPerTile() > 0,
                        surfaceSources,
                        4,
                        18,
                        2,
                        4,
                        10,
                        1.5D,
                        HydrologyPlannerSettings.Banks.defaults()),
                new HydrologyPlannerSettings.Hydraulics(4),
                new HydrologyPlannerSettings.Underground(
                        undergroundDensity > 0D,
                        undergroundSources,
                        68,
                        82,
                        4,
                        12,
                        2,
                        4,
                        5,
                        9,
                        true
                ),
                new HydrologyPlannerSettings.Outlets(
                        oceanEnabled,
                        new HydrologyPlannerSettings.Grotto(false, 4, 3, 3, 4096),
                        new HydrologyPlannerSettings.Grotto(inlandEnabled, 4, 3, 3, 4096),
                        inlandEnabled,
                        12,
                        32,
                        2,
                        4
                ),
                stableGeometry(),
                deepFluids
        );
    }

    private HydrologyPlannerSettings.Geometry stableGeometry() {
        HydrologyPlannerSettings.ChannelShape stableChannel =
                new HydrologyPlannerSettings.ChannelShape(2D, 0D, 0D, 11);
        return new HydrologyPlannerSettings.Geometry(
                new HydrologyPlannerSettings.Meanders(224, 72, 0D, 0D, 0D, 0, 75D),
                stableChannel,
                stableChannel,
                stableChannel,
                HydrologyPlannerSettings.Geometry.defaults().drops()
        );
    }

    private HydrologyPlannerSettings organicShapeSettings() {
        HydrologyPlannerSettings base = standardSettings(8D, 0D, true, false, List.of());
        HydrologyPlannerSettings.Surface surface = base.surface();
        HydrologyPlannerSettings.Outlets outlets = base.outlets();
        return new HydrologyPlannerSettings(
                base.seaLevel(),
                new HydrologyPlannerSettings.Routing(
                        512,
                        64,
                        1024,
                        2048, 64, 32,
                        1.5D,
                        24D,
                        2D,
                        0.2D
                ),
                new HydrologyPlannerSettings.Surface(
                        true,
                        new HydrologyPlannerSettings.Source(true, 8D, 80, 0, 8, 128),
                        surface.minimumWidth(),
                        surface.maximumWidth(),
                        surface.minimumDepth(),
                        surface.maximumDepth(),
                        96,
                        surface.shoreWidth(),
                        HydrologyPlannerSettings.Banks.defaults()),
                new HydrologyPlannerSettings.Hydraulics(5),
                base.underground(),
                new HydrologyPlannerSettings.Outlets(
                        true,
                        outlets.coastalGrotto(),
                        outlets.inlandGrotto(),
                        outlets.surfaceSinkholesEnabled(),
                        outlets.coastalCliffMinimumHeight(),
                        48,
                        outlets.maximumOceanApron(),
                        1
                ),
                HydrologyPlannerSettings.Geometry.defaults(),
                List.of()
        );
    }

    private HydrologyTerrainSampler organicShapeTerrain() {
        return (int x, int z) -> {
            if (x >= 448) {
                return oceanTerrain();
            }
            int height = 110
                    - Math.floorDiv(x, 10)
                    + Math.floorDiv(StrictMath.abs(z - 256), 32);
            boolean source = x >= 0 && x < 320;
            return terrain(height, 1D, false, false, source, false, false, false);
        };
    }

    private HydrologyTerrainSampler rollingCoast(int coastX) {
        return (int x, int z) -> {
            if (x >= coastX) {
                return oceanTerrain();
            }
            int height = 118 - Math.floorDiv(x, 12) + (int) StrictMath.round(StrictMath.sin(z / 18D) * 2D);
            return terrain(height, 1D, false, true, x >= 0 && x <= 24, true, true, false);
        };
    }

    private HydrologyTerrainSampler optionalRollingCoast(int coastX) {
        return (int x, int z) -> {
            if (x >= coastX) {
                return oceanTerrain();
            }
            int height = 118 - Math.floorDiv(x, 12) + (int) StrictMath.round(StrictMath.sin(z / 18D) * 2D);
            return terrain(height, 1D, false, true, x >= 0 && x <= 24, false, true, false);
        };
    }

    private HydrologyTerrainSampler inlandTerrain(boolean surfaceSource, boolean undergroundSource) {
        return (int x, int z) -> {
            boolean source = x == 0 && z == 0;
            boolean outlet = x == 64 && z == 64;
            int height = 120 - Math.floorDiv(x + z, 16);
            return new HydrologyTerrainSample(
                    height,
                    1D,
                    false,
                    true,
                    72,
                    74,
                    true,
                    outlet,
                    surfaceSource && source,
                    surfaceSource && source,
                    undergroundSource && source,
                    undergroundSource && source,
                    0D,
                    surfaceSource && source ? 1D : 0D,
                    undergroundSource && source ? 1D : 0D,
                    1D,
                    1D,
                    1D,
                    1D,
                    1D,
                    "parent",
                    "surface",
                    "mouth",
                    "shore",
                    "dry",
                    "flooded",
                    List.of("alpha")
            );
        };
    }

    private CaveVoxelView solidCaveView() {
        return caveView(Map.of(), Set.of());
    }

    private CaveVoxelView selectiveCaveView(Map<CavePosition, CaveVoxel> voxels) {
        return caveView(voxels, Set.of());
    }

    private CaveVoxelView caveView(Map<CavePosition, CaveVoxel> voxels, Set<CavePosition> surfaceOpenings) {
        return new CaveVoxelView() {
            @Override
            public boolean isInWorld(CavePosition position) {
                return position.y() > -4096 && position.y() < 4096;
            }

            @Override
            public CaveVoxel voxelAt(CavePosition position) {
                return voxels.getOrDefault(position, CaveVoxel.SOLID);
            }

            @Override
            public boolean isOpenToSurface(CavePosition position) {
                return surfaceOpenings.contains(position);
            }

            @Override
            public boolean isAboveTerrainSurface(CavePosition position) {
                return false;
            }
        };
    }

    private HydrologyTerrainSample oceanTerrain() {
        return new HydrologyTerrainSample(
                54,
                0D,
                true,
                false,
                30,
                32,
                false,
                false,
                false,
                false,
                false,
                false,
                0D,
                0D,
                0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "ocean_parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                List.of("alpha", "beta")
        );
    }

    private HydrologyTerrainSample blockedTerrain() {
        return new HydrologyTerrainSample(
                100,
                0D,
                false,
                false,
                72,
                74,
                false,
                false,
                false,
                false,
                false,
                false,
                0D,
                0D,
                0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                List.of("alpha")
        );
    }

    private HydrologyTerrainSample terrain(
            int height,
            double slope,
            boolean ocean,
            boolean cave,
            boolean surfaceSource,
            boolean requiredSurface,
            boolean undergroundSource,
            boolean requiredUnderground
    ) {
        return new HydrologyTerrainSample(
                height,
                slope,
                ocean,
                cave,
                72,
                74,
                !ocean,
                !ocean,
                surfaceSource,
                requiredSurface,
                undergroundSource,
                requiredUnderground,
                0D,
                surfaceSource ? 1D : 0D,
                undergroundSource ? 1D : 0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                List.of("beta", "alpha")
        );
    }

    private HydrologyTerrainSample deepTerrain(int caveFloorY) {
        return new HydrologyTerrainSample(
                90,
                0D,
                false,
                true,
                caveFloorY,
                74,
                true,
                false,
                false,
                false,
                false,
                false,
                0D,
                0D,
                0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                List.of("alpha")
        );
    }

    private HydrologyTerrainSample undergroundTerrain(int height, int caveFluidY, boolean source) {
        return new HydrologyTerrainSample(
                height,
                1D,
                false,
                true,
                caveFluidY - 4,
                caveFluidY,
                true,
                true,
                false,
                false,
                source,
                source,
                0D,
                0D,
                source ? 1D : 0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                List.of("alpha", "beta")
        );
    }

    private HydrologyPlannerSettings withSurfaceSources(
            HydrologyPlannerSettings settings,
            HydrologyPlannerSettings.Source sources
    ) {
        HydrologyPlannerSettings.Surface surface = settings.surface();
        return new HydrologyPlannerSettings(
                settings.seaLevel(),
                settings.routing(),
                new HydrologyPlannerSettings.Surface(
                        true,
                        sources,
                        surface.minimumWidth(),
                        surface.maximumWidth(),
                        surface.minimumDepth(),
                        surface.maximumDepth(),
                        surface.maximumIncision(),
                        surface.shoreWidth(),
                        HydrologyPlannerSettings.Banks.defaults()),
                settings.hydraulics(),
                settings.underground(),
                settings.outlets(),
                HydrologyPlannerSettings.Geometry.defaults(),
                settings.deepFluids()
        );
    }

    private HydrologyPlannerSettings withUndergroundSources(
            HydrologyPlannerSettings settings,
            HydrologyPlannerSettings.Source sources
    ) {
        HydrologyPlannerSettings.Underground underground = settings.underground();
        return new HydrologyPlannerSettings(
                settings.seaLevel(),
                settings.routing(),
                settings.surface(),
                settings.hydraulics(),
                new HydrologyPlannerSettings.Underground(
                        true,
                        sources,
                        underground.minimumFluidY(),
                        underground.maximumFluidY(),
                        underground.minimumWidth(),
                        underground.maximumWidth(),
                        underground.minimumDepth(),
                        underground.maximumDepth(),
                        underground.minimumHeadroom(),
                        underground.maximumHeadroom(),
                        underground.connectToExistingCaves()
                ),
                settings.outlets(),
                HydrologyPlannerSettings.Geometry.defaults(),
                settings.deepFluids()
        );
    }

    private HydrologyPlannerSettings withUndergroundFluidRange(
            HydrologyPlannerSettings settings,
            int minimumFluidY,
            int maximumFluidY
    ) {
        HydrologyPlannerSettings.Underground underground = settings.underground();
        return new HydrologyPlannerSettings(
                settings.seaLevel(),
                settings.routing(),
                settings.surface(),
                settings.hydraulics(),
                new HydrologyPlannerSettings.Underground(
                        underground.enabled(),
                        underground.sources(),
                        minimumFluidY,
                        maximumFluidY,
                        underground.minimumWidth(),
                        underground.maximumWidth(),
                        underground.minimumDepth(),
                        underground.maximumDepth(),
                        underground.minimumHeadroom(),
                        underground.maximumHeadroom(),
                        underground.connectToExistingCaves()
                ),
                settings.outlets(),
                HydrologyPlannerSettings.Geometry.defaults(),
                settings.deepFluids()
        );
    }

    private HydrologyPlannerSettings withUndergroundWidth(
            HydrologyPlannerSettings settings,
            int width
    ) {
        HydrologyPlannerSettings.Underground underground = settings.underground();
        return new HydrologyPlannerSettings(
                settings.seaLevel(),
                settings.routing(),
                settings.surface(),
                settings.hydraulics(),
                new HydrologyPlannerSettings.Underground(
                        underground.enabled(),
                        underground.sources(),
                        underground.minimumFluidY(),
                        underground.maximumFluidY(),
                        width,
                        width,
                        underground.minimumDepth(),
                        underground.maximumDepth(),
                        underground.minimumHeadroom(),
                        underground.maximumHeadroom(),
                        underground.connectToExistingCaves()
                ),
                settings.outlets(),
                HydrologyPlannerSettings.Geometry.defaults(),
                settings.deepFluids()
        );
    }

    private HydrologyPlanner plannerWithRoutingSampler(
            long seed,
            HydrologyPlannerSettings settings,
            HydrologyTerrainSampler terrain,
            AtomicInteger samples
    ) {
        HydrologyTerrainSampler countingTerrain = (int x, int z) -> {
            samples.incrementAndGet();
            return terrain.sample(x, z);
        };
        HydrologyRoutingTerrainSampler routingTerrain = new HydrologyRoutingTerrainSampler() {
            @Override
            public HydrologyTerrainSample[] sampleGrid(GridRequest request) {
                HydrologyTerrainSample[] grid = new HydrologyTerrainSample[Math.multiplyExact(
                        request.width(),
                        request.width()
                )];
                for (int gridZ = 0; gridZ < request.width(); gridZ++) {
                    int z = request.minimumZ() + gridZ * request.spacing();
                    for (int gridX = 0; gridX < request.width(); gridX++) {
                        int x = request.minimumX() + gridX * request.spacing();
                        grid[gridZ * request.width() + gridX] = terrain.sample(x, z);
                    }
                }
                return grid;
            }

            @Override
            public NaturalClassification classifyNatural(int blockX, int blockZ) {
                HydrologyTerrainSample sample = terrain.sample(blockX, blockZ);
                return sample.ocean() ? NaturalClassification.OCEAN : NaturalClassification.LAND;
            }
        };
        return new HydrologyPlanner(
                seed,
                settings,
                countingTerrain,
                routingTerrain,
                request -> request.minimum(),
                -4096,
                footprint -> solidCaveView()
        );
    }

    private HydrologyPlannerSettings withOutlets(
            HydrologyPlannerSettings settings,
            HydrologyPlannerSettings.Outlets outlets
    ) {
        return new HydrologyPlannerSettings(
                settings.seaLevel(),
                settings.routing(),
                settings.surface(),
                settings.hydraulics(),
                settings.underground(),
                outlets,
                HydrologyPlannerSettings.Geometry.defaults(),
                settings.deepFluids()
        );
    }

    private HydrologyColumnLayer layerForSegment(HydrologyColumnSample column, long segmentId) {
        for (HydrologyColumnLayer layer : column.layers()) {
            if (layer.feature().segmentId() == segmentId) {
                return layer;
            }
        }
        throw new AssertionError("Missing footprint layer for segment " + segmentId + ".");
    }

    private HydrologyColumnLayer layerForSegmentOrNull(HydrologyColumnSample column, long segmentId) {
        for (HydrologyColumnLayer layer : column.layers()) {
            if (layer.feature().segmentId() == segmentId) {
                return layer;
            }
        }
        return null;
    }

    private boolean hasNonCollinearInterior(List<HydrologyPoint> points) {
        if (points.size() < 3) {
            return false;
        }
        HydrologyPoint start = points.getFirst();
        HydrologyPoint end = points.getLast();
        long deltaX = end.x() - start.x();
        long deltaZ = end.z() - start.z();
        for (int index = 1; index < points.size() - 1; index++) {
            HydrologyPoint point = points.get(index);
            long localX = point.x() - start.x();
            long localZ = point.z() - start.z();
            if (deltaX * localZ - deltaZ * localX != 0L) {
                return true;
            }
        }
        return false;
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

    private double maximumQuantizedStickLength(List<HydrologyPoint> points) {
        int previousDirection = 0;
        double currentLength = 0D;
        double maximumLength = 0D;
        for (int pointIndex = 1; pointIndex < points.size(); pointIndex++) {
            HydrologyPoint previous = points.get(pointIndex - 1);
            HydrologyPoint point = points.get(pointIndex);
            int direction = quantizedStickDirection(point.x() - previous.x(), point.z() - previous.z());
            double length = StrictMath.hypot(point.x() - previous.x(), point.z() - previous.z());
            if (direction == 0) {
                previousDirection = 0;
                currentLength = 0D;
                continue;
            }
            currentLength = direction == previousDirection ? currentLength + length : length;
            previousDirection = direction;
            maximumLength = Math.max(maximumLength, currentLength);
        }
        return maximumLength;
    }

    private int quantizedStickDirection(int deltaX, int deltaZ) {
        if (deltaX == 0 && deltaZ != 0) {
            return deltaZ > 0 ? 1 : 2;
        }
        if (deltaZ == 0 && deltaX != 0) {
            return deltaX > 0 ? 3 : 4;
        }
        if (Math.abs(deltaX) != Math.abs(deltaZ) || deltaX == 0) {
            return 0;
        }
        if (deltaX > 0) {
            return deltaZ > 0 ? 5 : 6;
        }
        return deltaZ > 0 ? 7 : 8;
    }

    private boolean hasRotationalAsymmetry(
            HydrologyTile tile,
            HydraulicSegment segment,
            int radius
    ) {
        for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                boolean present = segmentPresent(tile, segment, deltaX, deltaZ);
                boolean rotated = segmentPresent(tile, segment, -deltaZ, deltaX);
                if (present != rotated) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentPresent(
            HydrologyTile tile,
            HydraulicSegment segment,
            int deltaX,
            int deltaZ
    ) {
        HydrologyColumnSample column = tile.columnAt(
                segment.start().x() + deltaX,
                segment.start().z() + deltaZ
        ).orElse(null);
        return column != null && layerForSegmentOrNull(column, segment.id()) != null;
    }

    private HydraulicSegment segment(HydrologyTile tile, long segmentId) {
        for (RiverCourse course : tile.courses()) {
            for (HydraulicSegment segment : course.segments()) {
                if (segment.id() == segmentId) {
                    return segment;
                }
            }
        }
        throw new AssertionError("Missing hydraulic segment " + segmentId + ".");
    }

    private double distanceToCenterline(HydrologyPoint point, List<HydrologyPoint> centerline) {
        double minimum = Double.POSITIVE_INFINITY;
        for (HydrologyPoint center : centerline) {
            minimum = Math.min(minimum, StrictMath.hypot(point.x() - center.x(), point.z() - center.z()));
        }
        return minimum;
    }

    private List<RiverCourse> surfaceCourses(HydrologyTile tile) {
        return courses(tile, RiverCourseType.SURFACE);
    }

    private List<RiverCourse> courses(HydrologyTile tile, RiverCourseType type) {
        ArrayList<RiverCourse> selected = new ArrayList<>();
        for (RiverCourse course : tile.courses()) {
            if (course.type() == type) {
                selected.add(course);
            }
        }
        return List.copyOf(selected);
    }

    private boolean hasAny(HydrologyTile tile, HydrologyFeatureType... types) {
        Set<HydrologyFeatureType> expected = new HashSet<>(List.of(types));
        for (RiverCourse course : tile.courses()) {
            for (HydraulicSegment segment : course.segments()) {
                if (expected.contains(segment.type())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<HydraulicSegment> segments(HydrologyTile tile, HydrologyFeatureType type) {
        ArrayList<HydraulicSegment> selected = new ArrayList<>();
        for (RiverCourse course : tile.courses()) {
            for (HydraulicSegment segment : course.segments()) {
                if (segment.type() == type) {
                    selected.add(segment);
                }
            }
        }
        return List.copyOf(selected);
    }

    private int firstSegment(RiverCourse course, HydrologyFeatureType... types) {
        Set<HydrologyFeatureType> expected = new HashSet<>(List.of(types));
        for (int index = 0; index < course.segments().size(); index++) {
            if (expected.contains(course.segments().get(index).type())) {
                return index;
            }
        }
        return -1;
    }

    private boolean bruteForceUndergroundHeads(
            int[] minimum,
            int[] maximum,
            int outlet,
            int index,
            int upstreamHead
    ) {
        if (index == minimum.length - 1) {
            return outlet >= minimum[index]
                    && outlet <= maximum[index]
                    && outlet <= upstreamHead;
        }
        int upper = Math.min(maximum[index], upstreamHead);
        for (int head = minimum[index]; head <= upper; head++) {
            if (bruteForceUndergroundHeads(minimum, maximum, outlet, index + 1, head)) {
                return true;
            }
        }
        return false;
    }
}
