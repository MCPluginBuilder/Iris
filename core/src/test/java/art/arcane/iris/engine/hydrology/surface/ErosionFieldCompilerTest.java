package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyGeometrySampler;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyPoint;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import org.junit.Test;

import java.util.List;
import java.util.function.IntBinaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ErosionFieldCompilerTest {
    private static final int SEA_LEVEL = 60;
    private static final HydrologyGeometrySampler CONSTANT_GEOMETRY = request -> switch (request.field()) {
        case SURFACE_WIDTH -> 6;
        case SURFACE_DEPTH -> 3;
        default -> request.minimum();
    };

    @Test
    public void flatTerrainHoldsTheWaterFlushWithTheBankAndContainedByDefault() {
        Compiled compiled = compile(300, (x, z) -> 80, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        SurfaceColumn center = compiled.field().column(150, 0);
        assertNotNull(center);
        assertEquals(SurfaceRole.CHANNEL, center.role());
        assertEquals(80, center.headY());
        assertTrue(center.height() <= 77);
        SurfaceColumn edge = compiled.field().column(150, 3);
        assertNotNull(edge);
        assertEquals(SurfaceRole.CHANNEL, edge.role());
        assertTrue(edge.height() > center.height());
        assertTrue(edge.height() <= 79);
        SurfaceColumn shore = compiled.field().column(150, 4);
        assertNotNull(shore);
        assertEquals(SurfaceRole.SHORE, shore.role());
        assertEquals(80, shore.height());
        assertNoWriteAboveNatural(compiled);
        assertChannelContained(compiled);
    }

    @Test
    public void aConfiguredSinkLowersTheWaterBelowTheBankByThatMuch() {
        HydrologyTerrainSampler flat = (int x, int z) -> HydrologyTerrainSample.openLand(80, 0D, "land");
        Compiled sunk = compile(zeroRoughnessSurface(1, HydrologyPlannerSettings.Erosion.defaults()), flat, 300, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, sunk.field().uncontainedWetCells());
        assertEquals(79, sunk.field().column(150, 0).headY());
        SurfaceColumn shore = sunk.field().column(150, 4);
        assertEquals(SurfaceRole.SHORE, shore.role());
        assertEquals(80, shore.height());
        assertChannelContained(sunk);
    }

    @Test
    public void disabledErosionKeepsTheChannelShoreAndLipButNoValley() {
        HydrologyTerrainSampler hillside = (int x, int z) -> HydrologyTerrainSample.openLand(90 + Math.max(0, z) / 2, 0D, "land");
        HydrologyPlannerSettings.Erosion off = new HydrologyPlannerSettings.Erosion(false, 12, 0.45D, 1D, 0.5D);
        Compiled compiled = compile(zeroRoughnessSurface(0, off), hillside, 300, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        assertChannelContained(compiled);
        assertNoWriteAboveNatural(compiled);
        for (SurfaceColumn column : compiled.field().columns().values()) {
            assertTrue(column.role() != SurfaceRole.BANK);
        }
        assertNotNull(compiled.field().column(150, 0));
        assertEquals(SurfaceRole.SHORE, compiled.field().column(150, 4).role());
    }

    @Test
    public void erosionDefaultsReproduceTheValleyBitForBitAndACurveReshapesIt() {
        HydrologyTerrainSampler hillside = (int x, int z) -> HydrologyTerrainSample.openLand(90 + Math.max(0, z) / 2, 0D, "land");
        Compiled defaults = compile(zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults()), hillside, 300, SurfaceTerminal.SINKHOLE, 40);
        Compiled explicit = compile(zeroRoughnessSurface(0, new HydrologyPlannerSettings.Erosion(true, 12, 0.45D, 1D, 0.5D)), hillside, 300, SurfaceTerminal.SINKHOLE, 40);
        Compiled steep = compile(zeroRoughnessSurface(0, new HydrologyPlannerSettings.Erosion(true, 12, 0.45D, 2D, 0.5D)), hillside, 300, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(defaults.field().columns().size(), explicit.field().columns().size());
        for (SurfaceColumn column : defaults.field().columns().values()) {
            SurfaceColumn same = explicit.field().column(column.x(), column.z());
            assertNotNull(same);
            assertEquals(column.height(), same.height());
            assertEquals(column.headY(), same.headY());
            assertEquals(column.role(), same.role());
        }
        boolean differs = false;
        for (SurfaceColumn column : defaults.field().columns().values()) {
            SurfaceColumn other = steep.field().column(column.x(), column.z());
            if (column.role() == SurfaceRole.BANK && other != null && other.height() != column.height()) {
                differs = true;
                break;
            }
        }
        assertTrue(differs);
        assertChannelContained(steep);
        assertNoWriteAboveNatural(steep);
    }

    @Test
    public void blendCurveOneIsTheEasedBlendAndHigherCurvesHoldTheBankTopLonger() {
        assertEquals(SurfaceNoise.smoothStep(0.3D), ErosionFieldCompiler.blend(0.3D, 1D), 0D);
        assertTrue(ErosionFieldCompiler.blend(0.3D, 2D) < ErosionFieldCompiler.blend(0.3D, 1D));
        assertTrue(ErosionFieldCompiler.blend(0.3D, 0.5D) > ErosionFieldCompiler.blend(0.3D, 1D));
        assertEquals(1D, ErosionFieldCompiler.blend(1D, 3D), 0D);
    }

    @Test
    public void hillsideCutGetsABankWhoseWidthGrowsWithTheCut() {
        Compiled compiled = compile(300, (x, z) -> 90 + Math.max(0, z) / 2, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        assertNoWriteAboveNatural(compiled);
        assertChannelContained(compiled);
        int lastBank = -1;
        int previous = Integer.MIN_VALUE;
        for (int z = 4; z < 60; z++) {
            SurfaceColumn column = compiled.field().column(150, z);
            if (column == null) {
                break;
            }
            if (column.role() == SurfaceRole.BANK) {
                lastBank = z;
                assertTrue(column.height() >= previous);
                previous = column.height();
            }
        }
        assertTrue(lastBank > 12);
        int stepOutsideChannel = 0;
        for (int z = 6; z <= lastBank; z++) {
            stepOutsideChannel = Math.max(
                    stepOutsideChannel,
                    Math.abs(compiled.field().column(150, z).height() - compiled.field().column(150, z - 1).height())
            );
        }
        assertTrue(stepOutsideChannel <= 1);
    }

    @Test
    public void slopingTerrainKeepsBanksSmoothAndContained() {
        Compiled compiled = compile(400, (x, z) -> 120 - x / 8, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        assertNoWriteAboveNatural(compiled);
        assertChannelContained(compiled);
        for (int x = 20; x < 380; x++) {
            for (int z = 5; z < 12; z++) {
                SurfaceColumn column = compiled.field().column(x, z);
                SurfaceColumn next = compiled.field().column(x + 1, z);
                if (column == null || next == null || column.role() == SurfaceRole.CHANNEL || next.role() == SurfaceRole.CHANNEL) {
                    continue;
                }
                assertTrue(Math.abs(column.height() - next.height()) <= 1);
            }
        }
    }

    @Test
    public void oceanAndSubmergedCellsReceiveNoWrites() {
        HydrologyTerrainSampler sampler = (int x, int z) -> x >= 240 || z > 20
                ? HydrologyTerrainSample.ocean(50, "ocean")
                : HydrologyTerrainSample.openLand(z <= -20 ? SEA_LEVEL : 100 - x / 6, 0D, "land");
        Compiled compiled = compile(sampler, 300, SurfaceTerminal.OCEAN_MOUTH, SEA_LEVEL);

        assertEquals(0, compiled.field().uncontainedWetCells());
        for (SurfaceColumn column : compiled.field().columns().values()) {
            HydrologyTerrainSample terrain = sampler.sample(column.x(), column.z());
            if (terrain.ocean() || terrain.naturalHeight() <= SEA_LEVEL) {
                assertTrue(column.apron());
                assertEquals(terrain.naturalHeight(), column.height());
            }
        }
        assertNoWriteAboveNatural(compiled);
    }

    @Test
    public void inletBanksAreCutToSeaLevelAndBlendBackToTheNaturalValley() {
        HydrologyTerrainSampler coast = (int x, int z) -> {
            if (x >= 240) {
                return HydrologyTerrainSample.ocean(50, "ocean");
            }
            int height = x < 200 ? 100 - x / 6 : 66 + (x - 200) * 18 / 40;
            return HydrologyTerrainSample.openLand(height, 0D, "land");
        };
        HydrologyPlannerSettings.Inlet inlet = new HydrologyPlannerSettings.Inlet(32, 3, 32);
        HydrologyPlannerSettings.Surface surface = withBanks(zeroRoughnessSurface(), zeroRoughnessSurface().banks().withInlet(inlet));
        Compiled compiled = compile(surface, coast, 244, SurfaceTerminal.OCEAN_MOUTH, SEA_LEVEL);

        assertEquals(0, compiled.field().uncontainedWetCells());
        assertNoWriteAboveNatural(compiled);
        assertChannelContained(compiled);
        int oceanWrites = 0;
        for (SurfaceColumn column : compiled.field().columns().values()) {
            HydrologyTerrainSample terrain = coast.sample(column.x(), column.z());
            if ((terrain.ocean() || terrain.naturalHeight() <= SEA_LEVEL) && !(column.apron() && column.height() == terrain.naturalHeight())) {
                oceanWrites++;
            }
        }
        assertEquals(0, oceanWrites);
        for (int x = 240 - inlet.length(); x < 240; x++) {
            SurfaceColumn center = compiled.field().column(x, 0);
            assertNotNull(center);
            assertEquals(SurfaceRole.CHANNEL, center.role());
            assertEquals(SEA_LEVEL, center.headY());
            assertTrue(center.height() < SEA_LEVEL);
        }
        int pinned = 220;
        int shoreZ = -1;
        for (int z = 1; z < 12; z++) {
            SurfaceColumn column = compiled.field().column(pinned, z);
            assertNotNull(column);
            if (column.role() != SurfaceRole.CHANNEL) {
                shoreZ = z;
                break;
            }
        }
        assertTrue(shoreZ > 0);
        SurfaceColumn shore = compiled.field().column(pinned, shoreZ);
        assertEquals(SurfaceRole.SHORE, shore.role());
        assertEquals(SEA_LEVEL, shore.height());
        assertTrue(coast.sample(pinned, shoreZ).naturalHeight() > SEA_LEVEL + 10);
        int previous = SEA_LEVEL;
        int banks = 0;
        int lastHeight = SEA_LEVEL;
        for (int z = shoreZ + 1; z < 60; z++) {
            SurfaceColumn column = compiled.field().column(pinned, z);
            if (column == null) {
                break;
            }
            assertTrue(column.role() == SurfaceRole.SHORE || column.role() == SurfaceRole.BANK);
            assertTrue(column.height() >= previous);
            assertTrue(column.height() - previous <= 1);
            previous = column.height();
            lastHeight = column.height();
            banks++;
        }
        assertTrue(banks > 8);
        assertTrue(lastHeight >= coast.sample(pinned, 0).naturalHeight() - 1);
    }

    private static HydrologyPlannerSettings.Surface withBanks(HydrologyPlannerSettings.Surface surface, HydrologyPlannerSettings.Banks banks) {
        return new HydrologyPlannerSettings.Surface(
                surface.enabled(), surface.sources(), surface.minimumWidth(), surface.maximumWidth(),
                surface.minimumDepth(), surface.maximumDepth(), surface.maximumIncision(), surface.shoreWidth(), banks);
    }

    @Test
    public void bankColumnsBeyondTheBlendAreNotWritten() {
        Compiled compiled = compile(300, (x, z) -> 80, SurfaceTerminal.SINKHOLE, 40);

        assertNull(compiled.field().column(150, 40));
    }

    private static void assertNoWriteAboveNatural(Compiled compiled) {
        for (SurfaceColumn column : compiled.field().columns().values()) {
            assertTrue(column.height() <= column.terrain().naturalHeight());
        }
    }

    private static void assertChannelContained(Compiled compiled) {
        for (SurfaceColumn column : compiled.field().columns().values()) {
            if (column.role() != SurfaceRole.CHANNEL || column.apron()) {
                continue;
            }
            int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] offset : offsets) {
                SurfaceColumn neighbour = compiled.field().column(column.x() + offset[0], column.z() + offset[1]);
                if (neighbour == null) {
                    assertEquals(SEA_LEVEL, column.headY());
                    continue;
                }
                if (neighbour.role() == SurfaceRole.CHANNEL) {
                    continue;
                }
                assertTrue(neighbour.height() >= column.headY());
            }
        }
    }

    private static Compiled compile(int stations, IntBinaryOperator height, SurfaceTerminal terminal, int terminalHead) {
        HydrologyTerrainSampler sampler = (int x, int z) -> HydrologyTerrainSample.openLand(height.applyAsInt(x, z), 0D, "land");
        return compile(sampler, stations, terminal, terminalHead);
    }

    private static Compiled compile(HydrologyTerrainSampler sampler, int stations, SurfaceTerminal terminal, int terminalHead) {
        return compile(zeroRoughnessSurface(), sampler, stations, terminal, terminalHead);
    }

    private static Compiled compile(
            HydrologyPlannerSettings.Surface surface,
            HydrologyTerrainSampler sampler,
            int stations,
            SurfaceTerminal terminal,
            int terminalHead
    ) {
        List<HydrologyPoint> path = List.of(new HydrologyPoint(0, 0, 0), new HydrologyPoint(stations - 1, 0, 0));
        SurfaceCenterline centerline = SurfaceCenterline.densify(path);
        ChannelProfile channel = new ChannelProfileBuilder(surface, sampler, CONSTANT_GEOMETRY)
                .build(centerline, "water", terminal == SurfaceTerminal.OCEAN_MOUTH);
        ValleyProfile valley = new ValleyProfileSolver(surface, sampler, SEA_LEVEL, 64)
                .solve(centerline, channel, terminal, terminalHead);
        assertNull(valley.rejection());
        ErosionField field = new ErosionFieldCompiler(surface, sampler, SEA_LEVEL)
                .compile(1234L, centerline, channel, valley, terminal, 8);
        return new Compiled(field, valley);
    }

    private static HydrologyPlannerSettings.Surface zeroRoughnessSurface() {
        return zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults());
    }

    private static HydrologyPlannerSettings.Surface zeroRoughnessSurface(int sink, HydrologyPlannerSettings.Erosion erosion) {
        return zeroRoughnessSurface(sink, erosion, new HydrologyPlannerSettings.Ponds(
                new HydrologyPlannerSettings.Pond(false, 5, 9, 3),
                new HydrologyPlannerSettings.Pond(false, 4, 7, 3)));
    }

    private static HydrologyPlannerSettings.Surface zeroRoughnessSurface(
            int sink,
            HydrologyPlannerSettings.Erosion erosion,
            HydrologyPlannerSettings.Ponds ponds
    ) {
        HydrologyPlannerSettings.Surface defaults = HydrologyPlannerSettings.defaults().surface();
        return new HydrologyPlannerSettings.Surface(
                true,
                defaults.sources(),
                4, 8, 2, 4, 10, 1.5D,
                new HydrologyPlannerSettings.Banks(sink, 3D, 4, 32, 0D, 16, 2, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, erosion, ponds)
        );
    }

    private record Compiled(ErosionField field, ValleyProfile valley) {
    }

    static long key(int x, int z) {
        return RiverFootprint.pack(x, z);
    }

    @Test
    public void blendWidthSmoothingSpreadsASpikeAcrossItsNeighbours() {
        double[][] widths = new double[9][2];
        for (int station = 0; station < widths.length; station++) {
            widths[station][0] = 4D;
            widths[station][1] = 4D;
        }
        widths[4][1] = 34D;

        double[][] smoothed = ErosionFieldCompiler.smoothWidths(widths, 2);

        for (int station = 0; station < widths.length; station++) {
            assertEquals(4D, smoothed[station][0], 1e-9);
        }
        assertEquals(4D, smoothed[0][1], 1e-9);
        assertEquals(10D, smoothed[2][1], 1e-9);
        assertEquals(10D, smoothed[4][1], 1e-9);
        assertEquals(10D, smoothed[6][1], 1e-9);
        assertEquals(4D, smoothed[8][1], 1e-9);
        for (int station = 1; station < widths.length; station++) {
            assertTrue(Math.abs(smoothed[station][1] - smoothed[station - 1][1]) <= 6D + 1e-9);
        }
    }

    @Test
    public void bedBowlIsLevelAcrossTheThalwegAndOneBlockAtTheEdge() {
        assertEquals(1D, ErosionFieldCompiler.bowl(0D, 0.45D), 1e-9);
        assertEquals(1D, ErosionFieldCompiler.bowl(0.45D, 0.45D), 1e-9);
        assertTrue(ErosionFieldCompiler.bowl(0.6D, 0.45D) > 0.8D);
        assertTrue(ErosionFieldCompiler.bowl(0.8D, 0.45D) > 0.3D && ErosionFieldCompiler.bowl(0.8D, 0.45D) < 0.7D);
        assertEquals(0D, ErosionFieldCompiler.bowl(1D, 0.45D), 1e-9);
        double previous = 1D;
        for (double normalized = 0D; normalized <= 1D; normalized += 0.05D) {
            double value = ErosionFieldCompiler.bowl(normalized, 0.45D);
            assertTrue(value <= previous + 1e-9);
            previous = value;
        }
    }

    @Test
    public void everyCellTouchingWaterKeepsTheLipAcrossHeadSteps() {
        Compiled compiled = compile(400, (x, z) -> 120 - x / 8, SurfaceTerminal.SINKHOLE, 40);
        int sink = zeroRoughnessSurface().banks().sink();
        int checked = 0;

        for (SurfaceColumn column : compiled.field().columns().values()) {
            if (column.role() != SurfaceRole.CHANNEL) {
                continue;
            }
            for (int deltaX = -1; deltaX <= 1; deltaX++) {
                for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                    SurfaceColumn neighbour = compiled.field().column(column.x() + deltaX, column.z() + deltaZ);
                    if (neighbour == null || neighbour.role() == SurfaceRole.CHANNEL) {
                        continue;
                    }
                    int required = Math.min(neighbour.terrain().naturalHeight(), column.headY() + sink);
                    assertTrue(neighbour.x() + "," + neighbour.z() + " height " + neighbour.height() + " < " + required,
                            neighbour.height() >= required);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    public void aDipOneBlockBelowTheWaterAnywhereBesideTheChannelIsHeldByALip() {
        // Whatever the solver sampled, no water cell may border a lower dry cell: a one-block dip
        // beside the channel is either answered by a lower head or by a one-block lip on the dip.
        for (int dipZ = 2; dipZ <= 6; dipZ++) {
            for (int dipX = 148; dipX <= 152; dipX++) {
                int finalZ = dipZ;
                int finalX = dipX;
                Compiled compiled = compile(300, (x, z) -> x == finalX && z == finalZ ? 79 : 80, SurfaceTerminal.SINKHOLE, 40);
                assertEquals("dip at " + finalX + "," + finalZ, 0, compiled.field().uncontainedWetCells());
                SurfaceColumn dip = compiled.field().column(finalX, finalZ);
                if (dip != null) {
                    assertTrue("lip taller than one block at " + finalX + "," + finalZ, dip.height() <= 80);
                }
            }
        }
    }

    @Test
    public void aLowCellBesideTheChannelLowersTheHeadSoTheBankStillHoldsIt() {
        Compiled compiled = compile(300, (x, z) -> x == 150 && z == 4 ? 79 : 80, SurfaceTerminal.SINKHOLE, 40);

        SurfaceColumn center = compiled.field().column(150, 0);
        assertNotNull(center);
        assertTrue("head " + center.headY(), center.headY() <= 79);
        SurfaceColumn low = compiled.field().column(150, 4);
        assertEquals(SurfaceRole.SHORE, low.role());
        assertNotNull(low);
        assertTrue(low.height() >= center.headY());
        assertEquals(0, compiled.field().uncontainedWetCells());
    }

    @Test
    public void oceanApronStartsAtTheFirstOceanStationAndStopsAtTheLimit() {
        HydrologyTerrainSampler sampler = (int x, int z) -> x >= 200
                ? HydrologyTerrainSample.ocean(SEA_LEVEL - 4, "sea")
                : HydrologyTerrainSample.openLand(SEA_LEVEL + 3, 0D, "land");
        HydrologyPlannerSettings.Surface surface = zeroRoughnessSurface();
        List<HydrologyPoint> path = List.of(new HydrologyPoint(0, 0, 0), new HydrologyPoint(259, 0, 0));
        SurfaceCenterline centerline = SurfaceCenterline.densify(path);
        ChannelProfile channel = new ChannelProfileBuilder(surface, sampler, CONSTANT_GEOMETRY)
                .build(centerline, "water", true);
        ValleyProfile valley = new ValleyProfileSolver(surface, sampler, SEA_LEVEL, 64)
                .solve(centerline, channel, SurfaceTerminal.OCEAN_MOUTH, SEA_LEVEL);
        assertNull(valley.rejection());

        ErosionField field = new ErosionFieldCompiler(surface, sampler, SEA_LEVEL)
                .compile(99L, centerline, channel, valley, SurfaceTerminal.OCEAN_MOUTH, 3);

        int firstApron = Integer.MAX_VALUE;
        int lastApron = Integer.MIN_VALUE;
        for (SurfaceColumn column : field.columns().values()) {
            if (column.apron()) {
                firstApron = Math.min(firstApron, column.x());
                lastApron = Math.max(lastApron, column.x());
                assertEquals(column.terrain().naturalHeight(), column.height());
            }
        }
        assertEquals(200, firstApron);
        assertEquals(202, lastApron);
    }
    @Test
    public void policyShoreBiomeWidthWidensTheShoreRoleOverUntouchedGround() {
        Compiled compiled = compile((int x, int z) -> land(80, 12D), 300, SurfaceTerminal.SINKHOLE, 40);

        SurfaceColumn geometricShore = compiled.field().column(150, 4);
        assertNotNull(geometricShore);
        assertEquals(SurfaceRole.SHORE, geometricShore.role());
        SurfaceColumn band = compiled.field().column(150, 12);
        assertNotNull(band);
        assertEquals(SurfaceRole.SHORE, band.role());
        assertEquals(80, band.height());
        assertNull(compiled.field().column(150, 17));
        assertNoWriteAboveNatural(compiled);
        assertChannelContained(compiled);
    }

    @Test
    public void zeroShoreBiomeWidthLeavesTheGeometricShoreWithTheBankRole() {
        Compiled compiled = compile((int x, int z) -> land(80, 0D), 300, SurfaceTerminal.SINKHOLE, 40);

        SurfaceColumn geometricShore = compiled.field().column(150, 4);
        assertNotNull(geometricShore);
        assertEquals(SurfaceRole.BANK, geometricShore.role());
        assertEquals(80, geometricShore.height());
        assertNull(compiled.field().column(150, 6));
        assertChannelContained(compiled);
    }

    private static HydrologyTerrainSample land(int height, double shoreBiomeWidth) {
        HydrologyTerrainSample open = HydrologyTerrainSample.openLand(height, 0D, "land");
        return new HydrologyTerrainSample(
                open.naturalHeight(), open.slope(), open.ocean(), open.caveAvailable(), open.caveFloorY(), open.caveFluidY(),
                open.transitAllowed(), open.outletAllowed(), open.surfaceSourceAllowed(), open.surfaceSourceRequired(),
                open.undergroundSourceAllowed(), open.undergroundSourceRequired(), open.routingCost(),
                open.surfaceSourceWeight(), open.undergroundSourceWeight(), open.widthMultiplier(), open.depthMultiplier(),
                open.incisionMultiplier(), open.routingMultiplier(), open.bankMultiplier(), open.parentBiomeKey(),
                open.surfaceBiomeKey(), open.mouthBiomeKey(), open.shoreBiomeKey(), open.bankBiomeKey(),
                open.floodedCaveBiomeKey(), open.preferredProfileKeys(), open.surfacePoolKeys(), shoreBiomeWidth, null
        );
    }
    @Test
    public void aSourcePondOpensAroundTheHeadwaterAtTheSourceHead() {
        HydrologyTerrainSampler flat = (int x, int z) -> HydrologyTerrainSample.openLand(80, 0D, "land");
        HydrologyPlannerSettings.Ponds ponds = new HydrologyPlannerSettings.Ponds(
                new HydrologyPlannerSettings.Pond(true, 10, 10, 3),
                new HydrologyPlannerSettings.Pond(false, 4, 7, 3));
        Compiled compiled = compile(zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults(), ponds), flat, 300, SurfaceTerminal.SINKHOLE, 40);
        Compiled plain = compile(300, (x, z) -> 80, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        assertChannelContained(compiled);
        assertNoWriteAboveNatural(compiled);
        SurfaceColumn centre = compiled.field().column(0, 0);
        assertEquals(SurfaceRole.CHANNEL, centre.role());
        assertEquals(80, centre.headY());
        assertTrue(centre.height() <= 77);
        SurfaceColumn behind = compiled.field().column(-9, 0);
        assertNotNull(behind);
        assertEquals(SurfaceRole.CHANNEL, behind.role());
        assertEquals(80, behind.headY());
        SurfaceColumn beside = compiled.field().column(0, 9);
        assertEquals(SurfaceRole.CHANNEL, beside.role());
        assertEquals(0, behind.station());
        SurfaceColumn plainBehind = plain.field().column(-9, 0);
        assertTrue(plainBehind == null || plainBehind.role() != SurfaceRole.CHANNEL);
        assertEquals(SurfaceRole.SHORE, compiled.field().column(-11, 0).role());
        assertNotNull(compiled.field().column(150, 0));
        assertEquals(SurfaceRole.CHANNEL, compiled.field().column(150, 0).role());
        assertTrue(compiled.field().column(150, 4).role() == SurfaceRole.SHORE);
    }

    @Test
    public void aTerminalPondOpensAtAnInlandEndButNotAtAnOceanMouth() {
        HydrologyTerrainSampler flat = (int x, int z) -> HydrologyTerrainSample.openLand(80, 0D, "land");
        HydrologyPlannerSettings.Ponds ponds = new HydrologyPlannerSettings.Ponds(
                new HydrologyPlannerSettings.Pond(false, 5, 9, 3),
                new HydrologyPlannerSettings.Pond(true, 5, 5, 2));
        Compiled inland = compile(zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults(), ponds), flat, 300, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, inland.field().uncontainedWetCells());
        assertChannelContained(inland);
        SurfaceColumn end = inland.field().column(299, 0);
        assertEquals(SurfaceRole.CHANNEL, end.role());
        assertTrue(end.height() <= 78);
        SurfaceColumn beyond = inland.field().column(303, 0);
        assertNotNull(beyond);
        assertEquals(SurfaceRole.CHANNEL, beyond.role());
        assertEquals(299, beyond.station());
        SurfaceColumn plainBeyond = compile(300, (x, z) -> 80, SurfaceTerminal.SINKHOLE, 40).field().column(303, 0);
        assertTrue(plainBeyond == null || plainBeyond.role() != SurfaceRole.CHANNEL);

        HydrologyTerrainSampler coast = (int x, int z) -> x >= 240
                ? HydrologyTerrainSample.ocean(50, "ocean")
                : HydrologyTerrainSample.openLand(100 - x / 6, 0D, "land");
        Compiled mouth = compile(zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults(), ponds), coast, 300, SurfaceTerminal.OCEAN_MOUTH, SEA_LEVEL);
        int wetBeyondTheShore = 0;
        for (SurfaceColumn column : mouth.field().columns().values()) {
            if (column.role() == SurfaceRole.CHANNEL && !column.apron() && column.x() >= 236 && Math.abs(column.z()) > 4) {
                wetBeyondTheShore++;
            }
        }
        assertEquals(0, wetBeyondTheShore);
    }

    @Test
    public void aPondShrinksWhereTheGroundFallsAwayAroundItsRim() {
        HydrologyTerrainSampler ridge = (int x, int z) -> HydrologyTerrainSample.openLand(z > 7 || z < -7 ? 70 : 80, 0D, "land");
        HydrologyPlannerSettings.Ponds ponds = new HydrologyPlannerSettings.Ponds(
                new HydrologyPlannerSettings.Pond(true, 4, 12, 3),
                new HydrologyPlannerSettings.Pond(false, 4, 7, 3));
        Compiled compiled = compile(zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults(), ponds), ridge, 300, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        assertChannelContained(compiled);
        assertNoWriteAboveNatural(compiled);
        assertEquals(80, compiled.field().column(0, 0).headY());
        SurfaceColumn nearRim = compiled.field().column(0, 4);
        assertEquals(SurfaceRole.CHANNEL, nearRim.role());
        for (SurfaceColumn column : compiled.field().columns().values()) {
            if (column.role() == SurfaceRole.CHANNEL) {
                assertTrue(Math.abs(column.z()) <= 7);
            }
        }
    }

    @Test
    public void pondsAreDeterministicForACourseSeed() {
        HydrologyTerrainSampler flat = (int x, int z) -> HydrologyTerrainSample.openLand(80, 0D, "land");
        HydrologyPlannerSettings.Surface surface = zeroRoughnessSurface(0, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults());
        Compiled first = compile(surface, flat, 300, SurfaceTerminal.SINKHOLE, 40);
        Compiled second = compile(surface, flat, 300, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(first.field().columns().size(), second.field().columns().size());
        for (SurfaceColumn column : first.field().columns().values()) {
            SurfaceColumn same = second.field().column(column.x(), column.z());
            assertNotNull(same);
            assertEquals(column.height(), same.height());
            assertEquals(column.role(), same.role());
        }
        assertEquals(0, first.field().uncontainedWetCells());
    }
}
