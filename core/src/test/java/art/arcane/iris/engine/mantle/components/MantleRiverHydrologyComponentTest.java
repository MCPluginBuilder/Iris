package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.object.IrisGeneratorStyle;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRiverCaveMode;
import art.arcane.iris.engine.object.IrisRiverCaves;
import art.arcane.iris.engine.object.IrisRiverExistingFluidPolicy;
import art.arcane.iris.engine.object.NoiseStyle;
import art.arcane.iris.engine.mantle.ComponentFlag;
import art.arcane.iris.engine.river.RiverEdgeId;
import art.arcane.iris.engine.river.RiverNodeId;
import art.arcane.iris.engine.river.RiverRouteState;
import art.arcane.iris.engine.river.RiverSample;
import art.arcane.iris.engine.river.RiverSection;
import art.arcane.iris.engine.river.cave.CavePosition;
import art.arcane.iris.engine.river.cave.CaveVoxel;
import art.arcane.iris.engine.river.cave.CaveVoxelPrecondition;
import art.arcane.iris.engine.river.cave.CaveVoxelView;
import art.arcane.iris.engine.river.cave.RiverCaveAction;
import art.arcane.iris.engine.river.cave.RiverCaveContainmentPlanner;
import art.arcane.iris.engine.river.cave.RiverCaveFluidPolicy;
import art.arcane.iris.engine.river.cave.RiverCaveMode;
import art.arcane.iris.engine.river.cave.RiverCavePlan;
import art.arcane.iris.engine.river.cave.RiverCavePlannerSettings;
import art.arcane.iris.engine.river.cave.RiverCaveRejection;
import art.arcane.iris.engine.river.cave.RiverCaveSource;
import art.arcane.iris.engine.river.runtime.IrisRiverSurfaceSample;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import art.arcane.volmlib.util.mantle.flag.ReservedFlag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MantleRiverHydrologyComponentTest {
    private final RiverCaveContainmentPlanner planner = new RiverCaveContainmentPlanner();

    @Test
    public void componentRunsBetweenCarvingAndPlacementWithStableFlagOrdinal() {
        ComponentFlag flag = MantleRiverHydrologyComponent.class.getAnnotation(ComponentFlag.class);

        assertEquals(1, MantleRiverHydrologyComponent.PRIORITY);
        assertEquals(ReservedFlag.RIVER_HYDROLOGY, flag.value());
        assertEquals(17, ReservedFlag.FLOATING_OBJECT.ordinal());
        assertEquals(18, ReservedFlag.RIVER_HYDROLOGY.ordinal());
    }

    @Test
    public void enablementIsInertUnlessRiversCarvingAndCaveHydrologyAreActive() {
        IrisDimension dimension = new IrisDimension();

        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.getRivers().setEnabled(true);
        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.getRivers().getCaves().setMode(IrisRiverCaveMode.FLOOD_CLOSED_COMPONENT);
        assertTrue(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.getRivers().getCaves().setMaximumPerReach(0);
        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.getRivers().getCaves().setMaximumPerReach(1);
        dimension.setCarvingEnabled(false);
        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.setCarvingEnabled(true);
        dimension.setUseMantle(false);
        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.setUseMantle(true);
        dimension.getDisabledComponents().add(ReservedFlag.CARVED);
        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
        dimension.getDisabledComponents().clear();
        dimension.getDisabledComponents().add(ReservedFlag.RIVER_HYDROLOGY);
        assertFalse(MantleRiverHydrologyComponent.isEnabledFor(dimension));
    }

    @Test
    public void sinkholeTerminalForcesGeneratedGrottoEvenInClosedMode() {
        assertEquals(
                RiverCaveMode.CLOSED_COMPONENT,
                MantleRiverHydrologyComponent.sourceMode(
                        IrisRiverCaveMode.FLOOD_CLOSED_COMPONENT,
                        false
                )
        );
        assertEquals(
                RiverCaveMode.GENERATED_GROTTO,
                MantleRiverHydrologyComponent.sourceMode(
                        IrisRiverCaveMode.FLOOD_CLOSED_COMPONENT,
                        true
                )
        );
    }

    @Test
    public void wetClosedCavePlansSourcesWithoutChangingBaseline() {
        TestVoxelView view = new TestVoxelView();
        CavePosition target = new CavePosition(8, 42, 8);
        view.set(target, CaveVoxel.CAVE_AIR);
        Map<CavePosition, CaveVoxel> baseline = view.snapshot();
        IrisRiverCaves caves = caves();
        RiverCavePlannerSettings settings = MantleRiverHydrologyComponent.plannerSettings(caves, 91L, null);
        RiverCaveSource source = new RiverCaveSource(
                1L,
                new CavePosition(8, 48, 8),
                target,
                46,
                RiverCaveMode.CLOSED_COMPONENT
        );

        RiverCavePlan plan = planner.plan(view, source, settings);

        assertTrue(plan.accepted());
        assertEquals(RiverCaveAction.WET_SOURCE, plan.actions().get(target));
        assertEquals(baseline, view.snapshot());
    }

    @Test
    public void onlyWetChannelBedsAreEligible() {
        IrisRiverSurfaceSample wet = surface(RiverRouteState.WET, RiverSection.CHANNEL, true);
        IrisRiverSurfaceSample dry = surface(RiverRouteState.DRY, RiverSection.DRY_CHANNEL, false);
        IrisRiverSurfaceSample bank = surface(RiverRouteState.WET, RiverSection.BANK, true);
        IrisRiverSurfaceSample absent = IrisRiverSurfaceSample.none(64D, 63D);

        assertTrue(MantleRiverHydrologyComponent.isWetChannelBed(wet));
        assertFalse(MantleRiverHydrologyComponent.isWetChannelBed(dry));
        assertFalse(MantleRiverHydrologyComponent.isWetChannelBed(bank));
        assertFalse(MantleRiverHydrologyComponent.isWetChannelBed(absent));
    }

    @Test
    public void openLavaAndOversizeCandidatesRejectWhileSolidFallbackCanPlan() {
        IrisRiverCaves caves = caves().setMaxFloodVolume(32);
        RiverCavePlannerSettings settings = MantleRiverHydrologyComponent.plannerSettings(caves, 92L, null);
        RiverCaveSource closed = new RiverCaveSource(
                2L,
                new CavePosition(0, 50, 0),
                new CavePosition(0, 44, 0),
                48,
                RiverCaveMode.CLOSED_COMPONENT
        );
        TestVoxelView open = new TestVoxelView();
        open.set(closed.target(), CaveVoxel.CAVE_AIR);
        open.open(closed.target());
        TestVoxelView lava = new TestVoxelView();
        lava.set(closed.target(), CaveVoxel.LAVA);
        TestVoxelView oversize = new TestVoxelView();
        for (int y = 43; y <= 44; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    oversize.set(new CavePosition(x, y, z), CaveVoxel.CAVE_AIR);
                }
            }
        }

        assertEquals(RiverCaveRejection.OPEN_SURFACE, planner.plan(open, closed, settings).rejection());
        assertEquals(RiverCaveRejection.LAVA_CONTACT, planner.plan(lava, closed, settings).rejection());
        assertEquals(RiverCaveRejection.VOLUME_LIMIT, planner.plan(oversize, closed, settings).rejection());

        IrisRiverCaves fallbackCaves = caves().setMaxFloodVolume(4096);
        RiverCavePlannerSettings fallbackSettings = MantleRiverHydrologyComponent.plannerSettings(
                fallbackCaves,
                92L,
                null
        );
        RiverCaveSource fallback = new RiverCaveSource(
                2L,
                closed.entry(),
                new CavePosition(3, 46, 0),
                closed.waterHeadY(),
                RiverCaveMode.GENERATED_GROTTO
        );
        assertEquals(
                RiverCaveRejection.NONE,
                planner.plan(new TestVoxelView(), fallback, fallbackSettings).rejection()
        );
    }

    @Test
    public void fluidPolicyMappingPreservesReplaceSemantics() {
        assertEquals(
                RiverCaveFluidPolicy.REJECT_EXISTING,
                MantleRiverHydrologyComponent.fluidPolicy(IrisRiverExistingFluidPolicy.REJECT)
        );
        assertEquals(
                RiverCaveFluidPolicy.ALLOW_COMPATIBLE,
                MantleRiverHydrologyComponent.fluidPolicy(IrisRiverExistingFluidPolicy.ALLOW_SAME)
        );
        assertEquals(
                RiverCaveFluidPolicy.REPLACE_CONTAINED,
                MantleRiverHydrologyComponent.fluidPolicy(IrisRiverExistingFluidPolicy.REPLACE)
        );
    }

    @Test
    public void authoredHeadThroatAndProofLimitsMapDirectly() {
        IrisRiverCaves caves = caves()
                .setWaterLevelOffset(-3)
                .setThroatRadius(5)
                .setDryHeadroom(6)
                .setMaxFloodRadius(23)
                .setMaxFloodDepth(17);
        RiverCavePlannerSettings settings = MantleRiverHydrologyComponent.plannerSettings(caves, 93L, null);

        assertEquals(61, MantleRiverHydrologyComponent.waterHeadY(
                surface(RiverRouteState.WET, RiverSection.CHANNEL, true),
                caves
        ));
        assertEquals(5, settings.throatRadius());
        assertEquals(6, settings.dryHeadroom());
        assertEquals(23, settings.maxClosedComponentHorizontalRadius());
        assertEquals(17, settings.maxClosedComponentDepth());
        assertEquals(
                (MantleRiverHydrologyComponent.actionRadius(caves) + 1) * 3,
                MantleRiverHydrologyComponent.candidateHalo(caves)
        );
        assertEquals(
                (MantleRiverHydrologyComponent.actionRadius(caves) + 1) * 4,
                MantleRiverHydrologyComponent.planningHalo(caves)
        );
    }

    @Test
    public void configuredShapeAndWarpChangeDeterministicGrottoFootprints() {
        IrisRiverCaves flatCaves = caves()
                .setGrottoHorizontalRadius(6)
                .setGrottoVerticalRadius(5)
                .setDryHeadroom(1)
                .setMaxFloodVolume(10000)
                .setGrottoShapeStyle(new IrisGeneratorStyle(NoiseStyle.FLAT))
                .setGrottoWarpStyle(new IrisGeneratorStyle(NoiseStyle.SIMPLEX))
                .setGrottoWarpStrength(0D);
        IrisRiverCaves shapedCaves = caves()
                .setGrottoHorizontalRadius(6)
                .setGrottoVerticalRadius(5)
                .setDryHeadroom(1)
                .setMaxFloodVolume(10000)
                .setGrottoShapeStyle(new IrisGeneratorStyle(NoiseStyle.SIMPLEX).zoomed(9D))
                .setGrottoWarpStyle(new IrisGeneratorStyle(NoiseStyle.SIMPLEX))
                .setGrottoWarpStrength(0D);
        IrisRiverCaves warpedCaves = caves()
                .setGrottoHorizontalRadius(6)
                .setGrottoVerticalRadius(5)
                .setDryHeadroom(1)
                .setMaxFloodVolume(10000)
                .setGrottoShapeStyle(new IrisGeneratorStyle(NoiseStyle.SIMPLEX).zoomed(9D))
                .setGrottoWarpStyle(new IrisGeneratorStyle(NoiseStyle.SIMPLEX).zoomed(7D))
                .setGrottoWarpStrength(3D);
        RiverCaveSource source = new RiverCaveSource(
                8L,
                new CavePosition(37, 58, -21),
                new CavePosition(37, 52, -21),
                56,
                RiverCaveMode.GENERATED_GROTTO
        );
        TestVoxelView view = new TestVoxelView();

        RiverCavePlan flat = planner.plan(
                view,
                source,
                MantleRiverHydrologyComponent.plannerSettings(flatCaves, 555L, null)
        );
        RiverCavePlannerSettings shapedSettings = MantleRiverHydrologyComponent.plannerSettings(
                shapedCaves,
                555L,
                null
        );
        RiverCavePlan shaped = planner.plan(view, source, shapedSettings);
        RiverCavePlan shapedRepeat = planner.plan(view, source, shapedSettings);
        RiverCavePlan warped = planner.plan(
                view,
                source,
                MantleRiverHydrologyComponent.plannerSettings(warpedCaves, 555L, null)
        );

        assertTrue(flat.accepted());
        assertTrue(shaped.accepted());
        assertTrue(warped.accepted());
        assertEquals(shaped, shapedRepeat);
        assertNotEquals(mutations(flat), mutations(shaped));
        assertNotEquals(mutations(shaped), mutations(warped));
    }

    @Test
    public void chunkOwnershipIsOrderInvariantAndNonOverlapping() {
        Set<CavePosition> actions = Set.of(
                new CavePosition(15, 40, 0),
                new CavePosition(16, 40, 0),
                new CavePosition(31, 40, 0),
                new CavePosition(32, 40, 0)
        );
        Set<CavePosition> forward = partition(actions, new int[]{0, 1, 2});
        Set<CavePosition> reverse = partition(actions, new int[]{2, 1, 0});

        assertEquals(actions, forward);
        assertEquals(forward, reverse);
    }

    @Test
    public void perChunkCandidateWindowsMatchGlobalDirectOverlapArbitration() {
        IrisRiverCaves caves = caves().setMaxFloodRadius(16).setMaxFloodVolume(1024);
        RiverCavePlannerSettings settings = MantleRiverHydrologyComponent.plannerSettings(caves, 777L, null);
        TestVoxelView view = new TestVoxelView();
        view.set(new CavePosition(14, 45, 0), CaveVoxel.CAVE_AIR);
        view.set(new CavePosition(16, 45, 0), CaveVoxel.CAVE_AIR);
        view.set(new CavePosition(18, 45, 0), CaveVoxel.CAVE_AIR);
        view.set(new CavePosition(200, 45, 0), CaveVoxel.CAVE_AIR);
        List<RiverCaveSource> sources = List.of(
                closedSource(30L, 14, 50),
                closedSource(20L, 16, 51),
                closedSource(10L, 18, 52),
                closedSource(1L, 200, 60)
        );
        Map<CavePosition, RiverCaveAction> global = planner.planAll(view, sources, settings).actions();

        for (int chunkX = 0; chunkX <= 1; chunkX++) {
            List<RiverCaveSource> localSources = candidateWindow(sources, caves, chunkX);
            Map<CavePosition, RiverCaveAction> local = planner.planAll(view, localSources, settings).actions();
            assertEquals(owned(global, chunkX), owned(local, chunkX));
        }
        assertFalse(global.containsKey(new CavePosition(14, 45, 0)));
        assertTrue(global.containsKey(new CavePosition(18, 45, 0)));
    }

    @Test
    public void transactionRejectsAnyChangedActionOrBoundaryGuard() {
        TestVoxelView view = new TestVoxelView();
        CavePosition wet = new CavePosition(0, 20, 0);
        CavePosition guard = new CavePosition(1, 20, 0);
        Map<CavePosition, CaveVoxelPrecondition> preconditions = Map.of(
                wet, new CaveVoxelPrecondition(CaveVoxel.CAVE_AIR, false),
                guard, new CaveVoxelPrecondition(CaveVoxel.SOLID, false)
        );
        view.set(wet, CaveVoxel.CAVE_AIR);

        assertTrue(MantleRiverHydrologyComponent.preconditionsHold(view, preconditions));
        view.set(guard, CaveVoxel.CAVE_AIR);
        assertFalse(MantleRiverHydrologyComponent.preconditionsHold(view, preconditions));
    }

    private IrisRiverCaves caves() {
        return new IrisRiverCaves()
                .setThroatRadius(1)
                .setDryHeadroom(2)
                .setGrottoHorizontalRadius(3)
                .setGrottoVerticalRadius(4)
                .setMaxBoreDepth(24)
                .setMaxFloodRadius(16)
                .setMaxFloodDepth(16)
                .setMaxFloodVolume(512)
                .setGrottoShapeStyle(new IrisGeneratorStyle(NoiseStyle.FLAT))
                .setGrottoWarpStyle(new IrisGeneratorStyle(NoiseStyle.FLAT))
                .setGrottoWarpStrength(0D);
    }

    private IrisRiverSurfaceSample surface(RiverRouteState state, RiverSection section, boolean fluid) {
        RiverEdgeId edge = new RiverEdgeId(new RiverNodeId(1L, 1L), new RiverNodeId(2L, 2L));
        RiverSample river = new RiverSample(
                true,
                state,
                section,
                0D,
                0.5D,
                1D,
                2,
                1,
                8D,
                4D,
                3D,
                false,
                edge
        );
        return new IrisRiverSurfaceSample(river, 68D, 61D, 64D, fluid);
    }

    private Set<CavePosition> mutations(RiverCavePlan plan) {
        Set<CavePosition> positions = new HashSet<>();
        for (Map.Entry<CavePosition, RiverCaveAction> entry : plan.actions().entrySet()) {
            if (entry.getValue() != RiverCaveAction.SEAL_GUARD) {
                positions.add(entry.getKey());
            }
        }
        return positions;
    }

    private Set<CavePosition> partition(Set<CavePosition> actions, int[] order) {
        Set<CavePosition> published = new HashSet<>();
        for (int chunkX : order) {
            for (CavePosition position : actions) {
                if (MantleRiverHydrologyComponent.owns(chunkX, 0, position)) {
                    assertTrue(published.add(position));
                }
            }
        }
        return published;
    }

    private RiverCaveSource closedSource(long id, int x, int head) {
        return new RiverCaveSource(
                id,
                new CavePosition(x, 60, 0),
                new CavePosition(x, 45, 0),
                head,
                RiverCaveMode.CLOSED_COMPONENT
        );
    }

    private List<RiverCaveSource> candidateWindow(
            List<RiverCaveSource> sources,
            IrisRiverCaves caves,
            int chunkX
    ) {
        int halo = MantleRiverHydrologyComponent.candidateHalo(caves);
        int minimum = (chunkX << 4) - halo;
        int maximum = ((chunkX + 1) << 4) + halo;
        List<RiverCaveSource> local = new ArrayList<>();
        for (RiverCaveSource source : sources) {
            if (source.entry().x() >= minimum && source.entry().x() < maximum) {
                local.add(source);
            }
        }
        return local;
    }

    private Map<CavePosition, RiverCaveAction> owned(
            Map<CavePosition, RiverCaveAction> actions,
            int chunkX
    ) {
        Map<CavePosition, RiverCaveAction> owned = new HashMap<>();
        for (Map.Entry<CavePosition, RiverCaveAction> entry : actions.entrySet()) {
            if (MantleRiverHydrologyComponent.owns(chunkX, 0, entry.getKey())) {
                owned.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(owned);
    }

    private static final class TestVoxelView implements CaveVoxelView {
        private final Map<CavePosition, CaveVoxel> voxels = new HashMap<>();
        private final Set<CavePosition> open = new HashSet<>();

        @Override
        public boolean isInWorld(CavePosition position) {
            return position.y() > 0 && position.y() < 128;
        }

        @Override
        public CaveVoxel voxelAt(CavePosition position) {
            return voxels.getOrDefault(position, CaveVoxel.SOLID);
        }

        @Override
        public boolean isOpenToSurface(CavePosition position) {
            return open.contains(position);
        }

        private void set(CavePosition position, CaveVoxel voxel) {
            voxels.put(position, voxel);
        }

        private void open(CavePosition position) {
            open.add(position);
        }

        private Map<CavePosition, CaveVoxel> snapshot() {
            return Map.copyOf(voxels);
        }
    }
}
