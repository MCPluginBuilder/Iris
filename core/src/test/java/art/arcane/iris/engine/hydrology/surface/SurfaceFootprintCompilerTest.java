package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydraulicSegment;
import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import art.arcane.iris.engine.hydrology.HydrologyGeometrySampler;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyPoint;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;
import art.arcane.iris.engine.hydrology.RiverCourse;
import art.arcane.iris.engine.hydrology.RiverCourseType;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SurfaceFootprintCompilerTest {
    private static final int SEA_LEVEL = 60;
    private static final HydrologyGeometrySampler CONSTANT_GEOMETRY = request -> switch (request.field()) {
        case SURFACE_WIDTH -> 6;
        case SURFACE_DEPTH -> 3;
        default -> request.minimum();
    };

    @Test
    public void compiledLayersDescribeAContainedCarveOnlyChannelWithShoreAndBank() {
        HydrologyTerrainSampler sampler = (int x, int z) -> x >= 150 && z > 8
                ? HydrologyTerrainSample.ocean(50, "ocean")
                : HydrologyTerrainSample.openLand(80 + Math.max(0, -z) / 2, 0D, "land");
        RiverCourse course = course(List.of(
                segment(1L, HydrologyFeatureType.SURFACE_POOL, 79, 79, points(0, 60, 79)),
                segment(2L, HydrologyFeatureType.RIFFLE, 79, 78, List.of(new HydrologyPoint(60, 79, 0), new HydrologyPoint(61, 78, 0))),
                segment(3L, HydrologyFeatureType.SURFACE_POOL, 78, 78, points(61, 140, 78))
        ));
        SurfaceFootprint footprint = compiler(sampler).compile(course);

        assertEquals(0, footprint.uncontainedWetCells());
        assertFalse(footprint.columns().isEmpty());
        HashSet<Long> keys = new HashSet<>();
        for (SurfaceLayerColumn column : footprint.columns()) {
            assertTrue(keys.add(RiverFootprint.pack(column.x(), column.z())));
            HydrologyColumnLayer layer = column.layer();
            assertTrue(layer.bedY() <= column.terrain().naturalHeight());
            assertEquals(layer.fluidHeadY(), layer.ceilingY());
            assertEquals(1L, layer.feature().courseId());
            assertTrue(layer.feature().type().isSurface());
            if (layer.channel()) {
                assertTrue(layer.fluidOwned());
                assertTrue(layer.connectedFluid());
                assertTrue(layer.terrainOwned());
                assertTrue(layer.fluidHeadY() < column.terrain().naturalHeight());
                assertTrue(layer.bedY() < layer.fluidHeadY());
            } else {
                assertTrue(layer.grading());
                assertTrue(layer.terrainOwned());
                assertFalse(layer.fluidOwned());
                assertEquals(layer.bedY(), layer.fluidHeadY());
            }
        }
        SurfaceLayerColumn center = column(footprint, 30, 0);
        assertNotNull(center);
        assertTrue(center.layer().channel());
        assertEquals(79, center.layer().fluidHeadY());
        assertTrue(center.layer().feature().source() == false);
        SurfaceLayerColumn source = column(footprint, 0, 0);
        assertNotNull(source);
        assertTrue(source.layer().feature().source());
        SurfaceLayerColumn shore = column(footprint, 30, 4);
        assertNotNull(shore);
        assertTrue(shore.layer().shore());
        assertEquals("land", shore.layer().shoreBiomeKey());
        SurfaceLayerColumn bank = column(footprint, 30, -8);
        assertNotNull(bank);
        assertTrue(bank.layer().grading());
        assertFalse(bank.layer().shore());
        assertNull(column(footprint, 160, 12));
    }

    @Test
    public void mouthOverTheOceanPublishesOnlyANonOwningApron() {
        HydrologyTerrainSampler sampler = (int x, int z) -> x >= 100
                ? HydrologyTerrainSample.ocean(50, "ocean")
                : HydrologyTerrainSample.openLand(70, 0D, "land");
        RiverCourse course = course(List.of(
                segment(1L, HydrologyFeatureType.SURFACE_POOL, 69, 69, points(0, 96, 69)),
                segment(2L, HydrologyFeatureType.WATERFALL, 69, SEA_LEVEL, List.of(new HydrologyPoint(96, 69, 0), new HydrologyPoint(97, SEA_LEVEL, 0))),
                segment(3L, HydrologyFeatureType.MOUTH, SEA_LEVEL, SEA_LEVEL, points(97, 112, SEA_LEVEL))
        ));
        SurfaceFootprint footprint = compiler(sampler).compile(course);

        int aprons = 0;
        for (SurfaceLayerColumn column : footprint.columns()) {
            if (column.terrain().ocean()) {
                assertTrue(column.layer().oceanApron());
                assertFalse(column.layer().terrainOwned());
                assertFalse(column.layer().fluidOwned());
                assertEquals(HydrologyFeatureType.MOUTH, column.layer().feature().type());
                aprons++;
            } else {
                assertFalse(column.layer().oceanApron());
            }
        }
        assertTrue(aprons > 0);
        assertNull(column(footprint, 111, 0));
    }

    @Test
    public void inletColumnsPublishSeaLevelFluidOverALoweredBed() {
        HydrologyTerrainSampler sampler = (int x, int z) -> x >= 100
                ? HydrologyTerrainSample.ocean(50, "ocean")
                : HydrologyTerrainSample.openLand(70, 0D, "land");
        HydrologyPlannerSettings.Inlet inlet = HydrologyPlannerSettings.defaults().surface().banks().inlet();
        ArrayList<HydrologyPoint> ramp = new ArrayList<>();
        for (int x = 30; x <= 39; x++) {
            ramp.add(new HydrologyPoint(x, 69 - (x - 30), 0));
        }
        RiverCourse course = course(List.of(
                segment(1L, HydrologyFeatureType.SURFACE_POOL, 69, 69, points(0, 30, 69)),
                segment(2L, HydrologyFeatureType.CASCADE, 69, SEA_LEVEL, List.copyOf(ramp)),
                segment(3L, HydrologyFeatureType.SURFACE_POOL, SEA_LEVEL, SEA_LEVEL, points(39, 99, SEA_LEVEL)),
                segment(4L, HydrologyFeatureType.MOUTH, SEA_LEVEL, SEA_LEVEL, points(99, 101, SEA_LEVEL))
        ));
        SurfaceFootprint footprint = compiler(sampler).compile(course);

        assertEquals(0, footprint.uncontainedWetCells());
        assertEquals(3, inlet.depth());
        for (int x = 40; x < 100; x++) {
            SurfaceLayerColumn center = column(footprint, x, 0);
            assertNotNull("x=" + x, center);
            HydrologyColumnLayer layer = center.layer();
            assertTrue(layer.channel());
            assertTrue(layer.fluidOwned());
            assertEquals(SEA_LEVEL, layer.fluidHeadY());
            assertTrue("x=" + x + " bed " + layer.bedY(), layer.bedY() <= SEA_LEVEL - inlet.depth());
            assertEquals(sampler.sample(x, 0).mouthBiomeKey(), layer.mouthBiomeKey());
        }
        int shoreZ = -1;
        for (int z = 1; z < 16; z++) {
            SurfaceLayerColumn column = column(footprint, 90, z);
            assertNotNull(column);
            if (!column.layer().channel()) {
                shoreZ = z;
                break;
            }
        }
        assertTrue(shoreZ > 3);
        SurfaceLayerColumn shore = column(footprint, 90, shoreZ);
        assertTrue(shore.layer().shore());
        assertEquals(SEA_LEVEL, shore.layer().bedY());
        assertEquals(SEA_LEVEL, shore.layer().fluidHeadY());
        for (SurfaceLayerColumn column : footprint.columns()) {
            if (column.terrain().ocean()) {
                assertTrue(column.layer().oceanApron());
                assertFalse(column.layer().terrainOwned());
            }
        }
    }

    private static SurfaceLayerColumn column(SurfaceFootprint footprint, int x, int z) {
        for (SurfaceLayerColumn column : footprint.columns()) {
            if (column.x() == x && column.z() == z) {
                return column;
            }
        }
        return null;
    }

    private static SurfaceFootprintCompiler compiler(HydrologyTerrainSampler sampler) {
        return new SurfaceFootprintCompiler(HydrologyPlannerSettings.defaults(), sampler, CONSTANT_GEOMETRY);
    }

    private static RiverCourse course(List<HydraulicSegment> segments) {
        return new RiverCourse(1L, RiverCourseType.SURFACE, OptionalLong.of(9L), OptionalLong.of(8L), "water", 1, List.of(), segments);
    }

    private static HydraulicSegment segment(long id, HydrologyFeatureType type, int upstream, int downstream, List<HydrologyPoint> centerline) {
        return new HydraulicSegment(id, 1L, type, upstream, downstream, 6, 3, false, false, centerline);
    }

    private static List<HydrologyPoint> points(int fromX, int toX, int y) {
        ArrayList<HydrologyPoint> points = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            points.add(new HydrologyPoint(x, y, 0));
        }
        return points;
    }
}
