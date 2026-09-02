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
    public void flatTerrainProducesAContainedChannelWithLipAndNoWriteAboveNatural() {
        Compiled compiled = compile(300, (x, z) -> 80, SurfaceTerminal.SINKHOLE, 40);

        assertEquals(0, compiled.field().uncontainedWetCells());
        SurfaceColumn center = compiled.field().column(150, 0);
        assertNotNull(center);
        assertEquals(SurfaceRole.CHANNEL, center.role());
        assertEquals(79, center.headY());
        assertTrue(center.height() <= 76);
        SurfaceColumn edge = compiled.field().column(150, 3);
        assertNotNull(edge);
        assertEquals(SurfaceRole.CHANNEL, edge.role());
        assertTrue(edge.height() > center.height());
        assertTrue(edge.height() <= 78);
        SurfaceColumn shore = compiled.field().column(150, 4);
        assertNotNull(shore);
        assertEquals(SurfaceRole.SHORE, shore.role());
        assertEquals(80, shore.height());
        assertNoWriteAboveNatural(compiled);
        assertChannelContained(compiled);
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
        HydrologyPlannerSettings.Surface surface = zeroRoughnessSurface();
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
        HydrologyPlannerSettings.Surface defaults = HydrologyPlannerSettings.defaults().surface();
        return new HydrologyPlannerSettings.Surface(
                true,
                defaults.sources(),
                4, 8, 2, 4, 1, 1, 10, 1.5D, 4, 32, false, 1, 1,
                new HydrologyPlannerSettings.Banks(1, 1, 3D, 4, 32, 0D, 16, 2, 6, 1.6D, true)
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
        assertEquals(1D, ErosionFieldCompiler.bowl(0D), 1e-9);
        assertEquals(1D, ErosionFieldCompiler.bowl(0.45D), 1e-9);
        assertTrue(ErosionFieldCompiler.bowl(0.6D) > 0.8D);
        assertTrue(ErosionFieldCompiler.bowl(0.8D) > 0.3D && ErosionFieldCompiler.bowl(0.8D) < 0.7D);
        assertEquals(0D, ErosionFieldCompiler.bowl(1D), 1e-9);
        double previous = 1D;
        for (double normalized = 0D; normalized <= 1D; normalized += 0.05D) {
            double value = ErosionFieldCompiler.bowl(normalized);
            assertTrue(value <= previous + 1e-9);
            previous = value;
        }
    }

    @Test
    public void everyCellTouchingWaterKeepsTheLipAcrossHeadSteps() {
        Compiled compiled = compile(400, (x, z) -> 120 - x / 8, SurfaceTerminal.SINKHOLE, 40);
        int freeboard = zeroRoughnessSurface().banks().freeboard();
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
                    int required = Math.min(neighbour.terrain().naturalHeight(), column.headY() + freeboard);
                    assertTrue(neighbour.x() + "," + neighbour.z() + " height " + neighbour.height() + " < " + required,
                            neighbour.height() >= required);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100);
    }

    @Test
    public void aLowCellBesideTheChannelLowersTheHeadSoTheLipSurvives() {
        Compiled compiled = compile(300, (x, z) -> x == 150 && z == 4 ? 79 : 80, SurfaceTerminal.SINKHOLE, 40);

        SurfaceColumn center = compiled.field().column(150, 0);
        assertNotNull(center);
        assertTrue("head " + center.headY(), center.headY() <= 78);
        SurfaceColumn low = compiled.field().column(150, 4);
        assertEquals(SurfaceRole.SHORE, low.role());
        assertNotNull(low);
        assertTrue(low.height() >= center.headY() + 1);
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
}
