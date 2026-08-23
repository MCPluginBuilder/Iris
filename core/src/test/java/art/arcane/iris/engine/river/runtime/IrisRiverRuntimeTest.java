package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.InferredType;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisGeneratorStyle;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.engine.object.IrisRiverCaveMode;
import art.arcane.iris.engine.object.IrisRiverOverride;
import art.arcane.iris.engine.object.IrisRiverRoutingPolicy;
import art.arcane.iris.engine.object.IrisRiverTerminalMode;
import art.arcane.iris.engine.object.IrisRiverWaterMode;
import art.arcane.iris.engine.object.IrisStyledRange;
import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverNode;
import art.arcane.iris.engine.river.RiverNodeId;
import art.arcane.iris.engine.river.RiverPolyline;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverAnchor;
import art.arcane.iris.engine.river.RiverRoutingContext;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.iris.util.project.stream.interpolation.Interpolated;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class IrisRiverRuntimeTest {
    @Test
    public void terminalTaperUsesMeasuredReachLength() {
        assertEquals(1D, IrisRiverRuntime.terminalWeight(40, 200D, 0.8D), 0D);
        assertEquals(0.5D, IrisRiverRuntime.terminalWeight(40, 200D, 0.9D), 0.0000001D);
        assertEquals(0.5D, IrisRiverRuntime.terminalWeight(40, 20D, 0.5D), 0.0000001D);
    }

    @Test
    public void footprintSamplingBuildsOnlyItsCenterTile() {
        IrisRiverNetwork configuration = configuration(false);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            runtime.sampleFootprint(-4096D, -4096D, 4096D, 4096D);

            assertEquals(1, runtime.completedTileCount());
        }
    }

    @Test
    public void wetTerminalRiverCarvesDownAndPublishesAFluidHead() {
        IrisRiverNetwork configuration = configuration(false);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            IrisRiverSurfaceSample sample = findRiver(runtime, RiverRouteState.WET);

            assertNotNull(sample);
            assertTrue(sample.river().present());
            assertTrue(sample.surfaceFluid());
            assertTrue(sample.terrainHeight() <= sample.naturalHeight());
            assertTrue(sample.waterSurfaceY() >= sample.terrainHeight());
            assertTrue(runtime.completedTileCount() <= 32);
        }
    }

    @Test
    public void failedOceanRouteCanProduceDryTerrainWithoutSurfaceWater() {
        IrisRiverNetwork configuration = configuration(true);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            IrisRiverSurfaceSample sample = findRiver(runtime, RiverRouteState.DRY);

            assertNotNull(sample);
            assertFalse(sample.surfaceFluid());
            assertTrue(sample.terrainHeight() <= sample.naturalHeight());
            assertTrue(sample.waterSurfaceY() == sample.terrainHeight());
        }
    }

    @Test
    public void localSinkholeOverrideMakesRequiredOceanTerminalWet() {
        IrisRiverNetwork configuration = configuration(true);
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion())) {
            IrisRiverSurfaceSample sample = findRiver(runtime, RiverRouteState.WET);

            assertNotNull(sample);
            assertTrue(sample.surfaceFluid());
        }
    }

    @Test
    public void sinkholeTerminalPublishesAGuaranteedSpecificCaveAnchor() {
        IrisRiverNetwork configuration = configuration(true);
        configuration.getTopology().setMaxRouteReaches(1);
        configuration.getCaves()
                .setMode(IrisRiverCaveMode.GROTTO_OR_CLOSED_COMPONENT)
                .setMaximumPerReach(1);
        configuration.getCaves().getEntry()
                .setChance(0D)
                .setInfluence(0D)
                .setStyle(flat());
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion())) {
            List<RiverAnchor> anchors = runtime.candidateAnchors(-128, -128, 256, 256, 16D, 994L);
            RiverAnchor terminal = null;
            for (RiverAnchor anchor : anchors) {
                if (runtime.isTerminalCaveAnchor(anchor)) {
                    terminal = anchor;
                    break;
                }
            }

            assertNotNull(terminal);
            assertTrue(runtime.acceptsCaveAnchor(terminal));
            IrisRiverSurfaceSample terminalSample = runtime.sample(
                    StrictMath.floor(terminal.x()),
                    StrictMath.floor(terminal.z())
            );
            assertTrue(terminalSample.surfaceFluid());
            assertTrue(Math.round(terminalSample.terrainHeight())
                    < Math.round(terminalSample.waterSurfaceY()));
            for (RiverAnchor anchor : anchors) {
                if (anchor.reachId().equals(terminal.reachId())
                        && !runtime.isTerminalCaveAnchor(anchor)) {
                    assertFalse(runtime.acceptsCaveAnchor(anchor));
                }
            }
            configuration.getCaves().setMaximumPerReach(0);
            assertFalse(runtime.acceptsCaveAnchor(terminal));
        }
    }

    @Test
    public void localSuppressOverrideRemovesDimensionSinkholeTerminalRoute() {
        IrisRiverNetwork configuration = configuration(true);
        configuration.getTerrain().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO);
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SUPPRESS));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion())) {
            assertFalse(hasRiver(runtime));
        }
    }

    @Test
    public void inactiveCaveHydrologySuppressesLocalSinkholeTerminalRoute() {
        IrisRiverNetwork configuration = configuration(true);
        IrisBiome land = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setTerminalMode(IrisRiverTerminalMode.SINKHOLE_GROTTO));

        try (IrisRiverRuntime runtime = runtime(configuration, land, new IrisRegion(), false)) {
            assertFalse(hasRiver(runtime));
        }
    }

    @Test
    public void caveEntryGateHonorsWetStateAndMaximumPerReach() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getCaves().setMode(IrisRiverCaveMode.FLOOD_CLOSED_COMPONENT);
        configuration.getCaves().setMaximumPerReach(1);
        configuration.getCaves().getEntry()
                .setChance(0.35D)
                .setInfluence(0D)
                .setStyle(flat());

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            List<RiverAnchor> anchors = runtime.candidateAnchors(0, 0, 256, 256, 16D, 773L);
            Map<RiverEdgeId, Integer> acceptedPerReach = new HashMap<>();
            boolean acceptedAfterRejectedRawIndex = false;
            for (RiverAnchor anchor : anchors) {
                if (runtime.acceptsCaveAnchor(anchor)) {
                    acceptedPerReach.merge(anchor.reachId(), 1, Integer::sum);
                    acceptedAfterRejectedRawIndex |= anchor.index() >= 1;
                }
            }

            assertFalse(acceptedPerReach.isEmpty());
            for (int accepted : acceptedPerReach.values()) {
                assertEquals(1, accepted);
            }
            assertTrue(acceptedAfterRejectedRawIndex);
        }
    }

    @Test
    public void finalPolylineSupercoverRejectsOneBlockedColumnMissedByWidthSpacing() {
        IrisRiverNetwork configuration = configuration(false);
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        IrisBiome blocked = new IrisBiome()
                .setInferredType(InferredType.LAND)
                .setRiverOverride(new IrisRiverOverride().setRoutingPolicy(IrisRiverRoutingPolicy.BLOCK));
        ProceduralStream<IrisBiome> biomes = ProceduralStream.of(
                (x, z) -> x == 1D && z == 0D ? blocked : land,
                Interpolated.of(value -> 0D, value -> land)
        );

        try (IrisRiverRuntime runtime = runtime(configuration, constantHeight(80D), biomes)) {
            assertFalse(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void finalPolylineSupercoverRejectsOneUnincisableTerrainSpike() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getTerrain().setMaxIncision(48);
        ProceduralStream<Double> height = ProceduralStream.of(
                (x, z) -> x == 1D && z == 0D ? 200D : 80D,
                Interpolated.DOUBLE
        );

        try (IrisRiverRuntime runtime = runtime(configuration, height, constantLandBiome())) {
            assertFalse(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void regionalDepthMultiplierCannotCollapseWetChannelBelowOneBlock() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getTerrain()
                .setDepth(range(2D))
                .setBedRoughness(0.65D)
                .setBedRoughnessStyle(flat());
        IrisRegion shallow = new IrisRegion()
                .setRiverOverride(new IrisRiverOverride().setDepthMultiplier(0.1D));

        try (IrisRiverRuntime runtime = runtime(
                configuration,
                constantHeight(80D),
                constantLandBiome(),
                shallow
        )) {
            assertTrue(runtime.allowsReach(straightReach(80D, 79D)));
        }
    }

    @Test
    public void terracedWaterUsesFixedInteriorPoolsAndPreservesNodeHeads() {
        IrisRiverNetwork configuration = configuration(false);
        configuration.getWater()
                .setMode(IrisRiverWaterMode.TERRACED)
                .setMaximumPoolRise(8)
                .setDropHeight(2)
                .setPoolLength(96);

        try (IrisRiverRuntime runtime = runtime(configuration)) {
            assertEquals(71D, runtime.terracedWaterSurface(72D, 64D, 600D, 155D / 600D), 0D);
            assertEquals(69D, runtime.terracedWaterSurface(72D, 64D, 600D, 156D / 600D), 0D);
            assertEquals(69D, runtime.terracedWaterSurface(72D, 64D, 600D, 251D / 600D), 0D);
            assertEquals(67D, runtime.terracedWaterSurface(72D, 64D, 600D, 252D / 600D), 0D);
            assertEquals(65D, runtime.terracedWaterSurface(72D, 64D, 600D, 443D / 600D), 0D);
            assertEquals(63D, runtime.terracedWaterSurface(72D, 64D, 600D, 444D / 600D), 0D);
            assertEquals(63D, runtime.terracedWaterSurface(72D, 64D, 600D, 1D), 0D);
            assertEquals(
                    runtime.terracedWaterSurface(72D, 64D, 600D, 1D),
                    runtime.terracedWaterSurface(64D, 63D, 600D, 0D),
                    0D
            );
            assertEquals(67D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.33D), 0D);
            assertEquals(65D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.34D), 0D);
            assertEquals(65D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.66D), 0D);
            assertEquals(63D, runtime.terracedWaterSurface(68D, 64D, 100D, 0.67D), 0D);
        }
    }

    private static IrisRiverRuntime runtime(IrisRiverNetwork configuration) {
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        IrisRegion region = new IrisRegion();
        return runtime(configuration, land, region);
    }

    private static IrisRiverRuntime runtime(IrisRiverNetwork configuration, IrisBiome land, IrisRegion region) {
        return runtime(configuration, land, region, true);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            IrisBiome land,
            IrisRegion region,
            boolean caveHydrologyActive
    ) {
        ProceduralStream<Double> height = ProceduralStream.of(
                (x, z) -> 180D - x * 0.01D - z * 0.015D,
                Interpolated.DOUBLE
        );
        ProceduralStream<IrisBiome> biome = ProceduralStream.of(
                (x, z) -> land,
                Interpolated.of(value -> 0D, value -> land)
        );
        return runtime(configuration, height, biome, region, caveHydrologyActive);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome
    ) {
        return runtime(configuration, height, biome, new IrisRegion());
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome,
            IrisRegion region
    ) {
        return runtime(configuration, height, biome, region, true);
    }

    private static IrisRiverRuntime runtime(
            IrisRiverNetwork configuration,
            ProceduralStream<Double> height,
            ProceduralStream<IrisBiome> biome,
            IrisRegion region,
            boolean caveHydrologyActive
    ) {
        ProceduralStream<Double> slope = ProceduralStream.ofDouble((x, z) -> 0.025D);
        ProceduralStream<IrisRegion> regions = ProceduralStream.of(
                (x, z) -> region,
                Interpolated.of(value -> 0D, value -> region)
        );
        return new IrisRiverRuntime(new IrisRiverRuntimeContext(
                4829759234L,
                configuration,
                mock(IrisData.class),
                63,
                caveHydrologyActive,
                height,
                slope,
                biome,
                regions
        ));
    }

    private static RiverRoutingContext straightReach(double fromHeight, double toHeight) {
        RiverNode from = new RiverNode(
                new RiverNodeId(0L, 0L),
                0D,
                0D,
                fromHeight,
                fromHeight,
                fromHeight,
                fromHeight,
                false,
                true
        );
        RiverNode to = new RiverNode(
                new RiverNodeId(1L, 0L),
                32D,
                0D,
                toHeight,
                toHeight,
                toHeight,
                toHeight,
                false,
                true
        );
        return new RiverRoutingContext(
                RiverEdgeId.of(from.id(), to.id()),
                from,
                to,
                new RiverPolyline(new double[]{0D, 32D}, new double[]{0D, 0D})
        );
    }

    private static ProceduralStream<Double> constantHeight(double height) {
        return ProceduralStream.of((x, z) -> height, Interpolated.DOUBLE);
    }

    private static ProceduralStream<IrisBiome> constantLandBiome() {
        IrisBiome land = new IrisBiome().setInferredType(InferredType.LAND);
        return ProceduralStream.of(
                (x, z) -> land,
                Interpolated.of(value -> 0D, value -> land)
        );
    }

    private static IrisRiverNetwork configuration(boolean requireOcean) {
        IrisRiverNetwork configuration = new IrisRiverNetwork().setEnabled(true);
        configuration.getTopology()
                .setCellSize(64)
                .setTileCells(1)
                .setSiteJitter(0D)
                .setMaxRouteReaches(4)
                .setSinkSearchReaches(3)
                .setRequireOcean(requireOcean);
        configuration.getTopology().getSource()
                .setChance(1D)
                .setInfluence(0D)
                .setStyle(flat());
        configuration.getTopology().getContinuation()
                .setChance(1D)
                .setInfluence(0D)
                .setStyle(flat());
        configuration.getTopology().setRoutingStyle(flat());
        configuration.getTerrain()
                .setChannelWidth(range(24D))
                .setBankWidth(range(12D))
                .setDepth(range(5D))
                .setMaxIncision(512)
                .setMeanderStrength(0D)
                .setMeanderStyle(flat())
                .setBedRoughness(0D)
                .setBedRoughnessStyle(flat())
                .setDryContinuationChance(1D);
        configuration.getTerrain().getIncision()
                .setChance(1D)
                .setInfluence(0D)
                .setStyle(flat());
        configuration.getBiomes().setSelectionStyle(flat());
        return configuration;
    }

    private static IrisRiverSurfaceSample findRiver(IrisRiverRuntime runtime, RiverRouteState state) {
        for (int x = -128; x < 192; x += 2) {
            for (int z = -128; z < 192; z += 2) {
                IrisRiverSurfaceSample sample = runtime.sample(x, z);
                if (sample.river().present() && sample.river().state() == state) {
                    return sample;
                }
            }
        }
        return null;
    }

    private static boolean hasRiver(IrisRiverRuntime runtime) {
        for (int x = 0; x < 64; x += 2) {
            for (int z = 0; z < 64; z += 2) {
                if (runtime.sample(x, z).river().present()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IrisStyledRange range(double value) {
        return new IrisStyledRange(value, value, flat());
    }

    private static IrisGeneratorStyle flat() {
        return new IrisGeneratorStyle(NoiseStyle.FLAT);
    }
}
