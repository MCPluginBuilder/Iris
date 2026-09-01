package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HydrologyPlannerSettingsTest {
    @Test
    public void branchingUsesIndependentSurfaceAndUndergroundCourseFloors() {
        HydrologyPlannerSettings.Branching branching = new HydrologyPlannerSettings.Branching(384, 192);

        assertEquals(384, branching.minimumCourseLength(true));
        assertEquals(192, branching.minimumCourseLength(false));
    }

    @Test
    public void crossTileAdmissionRejectsPublicationEnvelopesBeyondFourColorsPerAxis() {
        HydrologyPlannerSettings base = HydrologyPlannerSettings.defaults();
        assertEquals(0.5D, base.surface().sources().density(), 0D);
        assertEquals(0.25D, base.underground().sources().density(), 0D);
        HydrologyPlannerSettings.DeepFluid overlongChannel = new HydrologyPlannerSettings.DeepFluid(
                "deep",
                true,
                1D,
                64,
                -64,
                -32,
                1,
                1,
                1,
                1,
                1,
                3072,
                1,
                1,
                1,
                64,
                1,
                false,
                true
        );

        assertEquals(2, base.crossTileColorPeriod());
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings(
                base.seaLevel(),
                base.routing(),
                base.surface(),
                base.hydraulics(),
                base.underground(),
                base.outlets(),
                HydrologyPlannerSettings.Geometry.defaults(),
                List.of(overlongChannel)
        ));
    }

    @Test
    public void canonicalBoundaryValuesAreAccepted() {
        HydrologyPlannerSettings.Routing routing = new HydrologyPlannerSettings.Routing(
                512,
                512,
                64,
                4,
                256,
                new HydrologyPlannerSettings.Branching(0, 0),
                0D,
                0D,
                0D,
                0D
        );
        HydrologyPlannerSettings.Source sources = new HydrologyPlannerSettings.Source(true, 1D, 0, 0, 1, 0);
        HydrologyPlannerSettings.Surface surface = new HydrologyPlannerSettings.Surface(
                true,
                sources,
                1,
                1,
                1,
                1,
                1,
                1,
                0,
                1D,
                0,
                0,
                false,
                0,
                0
        );
        HydrologyPlannerSettings.Hydraulics hydraulics = new HydrologyPlannerSettings.Hydraulics(
                8,
                8,
                0,
                0,
                1,
                1
        );
        HydrologyPlannerSettings.Grotto grotto = new HydrologyPlannerSettings.Grotto(true, 1, 1, 1, 1);
        HydrologyPlannerSettings.Outlets outlets = new HydrologyPlannerSettings.Outlets(
                true,
                grotto,
                grotto,
                true,
                0,
                0,
                64,
                1
        );
        HydrologyPlannerSettings.DeepFluid deepFluid = new HydrologyPlannerSettings.DeepFluid(
                "deep",
                true,
                1D,
                8192,
                -64,
                -32,
                1,
                1,
                1,
                1,
                1,
                2730,
                1,
                1,
                1,
                64,
                1,
                false,
                true
        );

        assertEquals(256, routing.maximumRouteLength());
        assertEquals(0, surface.maximumIncision());
        assertEquals(1, hydraulics.waterfallMinimumDrop());
        assertEquals(0, outlets.mouthLevelingDistance());
        assertEquals(1, grotto.maximumVolume());
        assertEquals(2730, deepFluid.maximumChannelLength());
    }

    @Test
    public void inconsistentHydraulicThresholdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Hydraulics(
                8,
                16,
                0,
                2,
                2,
                2
        ));
    }

    @Test
    public void valuesOutsideCanonicalBoundsAreRejected() {
        HydrologyPlannerSettings.Grotto accepted = new HydrologyPlannerSettings.Grotto(true, 1, 1, 1, 1);
        assertEquals(1, accepted.maximumVolume());
        assertThrows(IllegalArgumentException.class,
                () -> new HydrologyPlannerSettings.Grotto(true, 1, 1, 1, 0));
        HydrologyPlannerSettings.Grotto disabled = new HydrologyPlannerSettings.Grotto(false, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Outlets(
                true,
                accepted,
                disabled,
                true,
                0,
                0,
                1,
                1
        ));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Outlets(
                true,
                accepted,
                accepted,
                true,
                0,
                0,
                65,
                1
        ));
    }

    @Test
    public void dropGeometryNarrowsFlowAndExpandsItsReceivingBasin() {
        HydrologyPlannerSettings.Drops drops = new HydrologyPlannerSettings.Drops(
                2,
                1.4D,
                2,
                0.45D,
                2,
                1.8D,
                8
        );

        assertEquals(5, drops.flowWidth(10));
        assertEquals(1, drops.stepLimit(HydrologyFeatureType.CASCADE));
        assertEquals(2, drops.stepLimit(HydrologyFeatureType.UNDERGROUND_DROP));
        assertEquals(2, drops.flowDepth(6));
        assertEquals(9, drops.basinWidth(5));
        assertEquals(5, drops.basinDepth(2, 8));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Drops(
                2,
                1.4D,
                2,
                0.45D,
                4,
                1.8D,
                3
        ));
    }

    @Test
    public void deepSiteAdmissionUsesItsOwnHeadAndEnvelope() {
        HydrologyPlannerSettings.DeepFluid deepFluid = new HydrologyPlannerSettings.DeepFluid(
                "deep",
                true,
                1D,
                1024,
                16,
                96,
                4,
                4,
                3,
                3,
                0,
                0,
                2,
                2,
                4,
                4096,
                1,
                true,
                false
        );
        HydrologyTerrainSample terrainWithoutUndergroundRiverCave = HydrologyTerrainSample.openLand(100, 1D, "parent");

        assertFalse(HydrologyPlanner.deepSiteFits(terrainWithoutUndergroundRiverCave, deepFluid, 3, 3, 0));
        assertTrue(HydrologyPlanner.deepSiteFits(terrainWithoutUndergroundRiverCave, deepFluid, 3, 3, -64));
        assertTrue(HydrologyPlanner.deepSiteFits(terrainWithoutUndergroundRiverCave, deepFluid, 4, 3, 0));
        assertTrue(HydrologyPlanner.deepSiteFits(terrainWithoutUndergroundRiverCave, deepFluid, 80, 3, 0));
        assertFalse(HydrologyPlanner.deepSiteFits(terrainWithoutUndergroundRiverCave, deepFluid, 96, 3, 0));
        assertFalse(HydrologyPlanner.deepSiteFits(
                HydrologyTerrainSample.ocean(50, "ocean"),
                deepFluid,
                40,
                3,
                0
        ));
    }
}
