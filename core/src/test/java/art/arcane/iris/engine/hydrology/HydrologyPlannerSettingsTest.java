package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HydrologyPlannerSettingsTest {
    @Test
    public void routingUsesIndependentSurfaceAndUndergroundCourseFloors() {
        HydrologyPlannerSettings.Routing routing = HydrologyPlannerSettings.defaults().routing();

        assertEquals(384, routing.minimumCourseLength(true));
        assertEquals(192, routing.minimumCourseLength(false));
    }

    @Test
    public void refinementSpacingIsDerivedFromTheSampleLattice() {
        assertEquals(4, HydrologyPlannerSettings.Routing.refinementSpacing(64));
        assertEquals(2, HydrologyPlannerSettings.Routing.refinementSpacing(50));
        assertEquals(1, HydrologyPlannerSettings.Routing.refinementSpacing(45));
        assertEquals(4, HydrologyPlannerSettings.defaults().routing().refinementSpacing());
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
                List.of(overlongChannel), List.of(),
                0D,
                HydrologyPlannerSettings.SeaCaves.disabled())
        );
    }

    @Test
    public void canonicalBoundaryValuesAreAccepted() {
        HydrologyPlannerSettings.Routing routing = new HydrologyPlannerSettings.Routing(
                512,
                512,
                4,
                256,
                0,
                0,
                0D,
                0D,
                0D,
                0D,
                1D,
                0
        );
        HydrologyPlannerSettings.Source sources = new HydrologyPlannerSettings.Source(true, 1D, 0, 0, 1, 0);
        HydrologyPlannerSettings.Surface surface = new HydrologyPlannerSettings.Surface(
                true,
                sources,
                1,
                1,
                1,
                1,
                0,
                1D,
                HydrologyPlannerSettings.Banks.defaults());
        HydrologyPlannerSettings.Hydraulics hydraulics = new HydrologyPlannerSettings.Hydraulics(1);
        HydrologyPlannerSettings.Grotto grotto = new HydrologyPlannerSettings.Grotto(true, 1, 1, 1, 1);
        HydrologyPlannerSettings.Outlets outlets = new HydrologyPlannerSettings.Outlets(
                true,
                grotto,
                grotto,
                true,
                0,
                0,
                64,
                1,
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
    public void bankSettingsRejectValuesOutsideTheirBounds() {
        HydrologyPlannerSettings.Banks banks = HydrologyPlannerSettings.Banks.defaults();

        assertEquals(0, banks.sink());
        assertTrue(banks.erosion().enabled());
        assertEquals(12, banks.erosion().smoothingRadius());
        assertTrue(banks.ponds().source().enabled());
        assertTrue(banks.ponds().terminal().enabled());
        assertEquals(3D, banks.blendSlope(), 0D);
        assertEquals(4, banks.minimumBlendWidth());
        assertEquals(32, banks.maximumBlendWidth());
        assertEquals(6, banks.waterfallMinimumDrop());
        assertTrue(banks.exposeCutStrata());
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(-1, 3D, 4, 32, 0.25D, 16, 2, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(1, 0D, 4, 32, 0.25D, 16, 2, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(1, 3D, 40, 32, 0.25D, 16, 2, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(1, 3D, 4, 32, 0.25D, 16, 2, 0, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(1, 3D, 4, 32, 0.25D, 16, 0, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(1, 3D, 4, 32, 1.5D, 16, 2, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true, HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Surface(
                true,
                new HydrologyPlannerSettings.Source(true, 1D, 0, 0, 1, 0),
                1, 1, 1, 1, 0, 1D,
                null));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Erosion(true, -1, 0.45D, 1D, 0.5D));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Erosion(true, 12, 1D, 1D, 0.5D));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Erosion(true, 12, 0.45D, 0D, 0.5D));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Erosion(true, 12, 0.45D, 1D, -0.1D));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Pond(true, 0, 4, 3));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Pond(true, 5, 4, 3));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Pond(true, 4, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Ponds(null, new HydrologyPlannerSettings.Pond(true, 4, 6, 3)));
    }

    @Test
    public void inletSettingsRejectValuesOutsideTheirBounds() {
        HydrologyPlannerSettings.Inlet inlet = HydrologyPlannerSettings.defaults().surface().banks().inlet();

        assertEquals(64, inlet.length());
        assertEquals(3, inlet.depth());
        assertEquals(32, inlet.maximumIncision());
        assertEquals(new HydrologyPlannerSettings.Inlet(0, 0, 0), HydrologyPlannerSettings.Inlet.none());
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Inlet(-1, 3, 32));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Inlet(1025, 3, 32));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Inlet(64, -1, 32));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Inlet(64, 65, 32));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Inlet(64, 3, -1));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Inlet(64, 3, 513));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Banks(
                0, 3D, 4, 32, 0.25D, 16, 2, 6, 1.6D, null, 2.5D, 24, true,
                HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults()));
        new HydrologyPlannerSettings.Inlet(1024, 64, 512);
    }

    @Test
    public void publicationRadiusGrowsWithTheMouthFlare() {
        HydrologyPlannerSettings base = HydrologyPlannerSettings.defaults();
        HydrologyPlannerSettings.Banks banks = base.surface().banks();
        HydrologyPlannerSettings.Banks flared = new HydrologyPlannerSettings.Banks(
                banks.sink(), banks.blendSlope(), banks.minimumBlendWidth(), banks.maximumBlendWidth(), banks.roughness(),
                banks.roughnessWavelength(), banks.cascadeRun(), banks.waterfallMinimumDrop(), 4D, banks.inlet(),
                banks.springWidthRatio(), banks.springLength(), banks.exposeCutStrata(), banks.erosion(), banks.ponds());
        HydrologyPlannerSettings.Surface surface = base.surface();
        HydrologyPlannerSettings wide = new HydrologyPlannerSettings(
                base.seaLevel(), base.routing(),
                new HydrologyPlannerSettings.Surface(surface.enabled(), surface.sources(), surface.minimumWidth(),
                        surface.maximumWidth(), surface.minimumDepth(), surface.maximumDepth(), surface.maximumIncision(),
                        surface.shoreWidth(), flared),
                base.hydraulics(), base.underground(), base.outlets(), base.geometry(), base.deepFluids(), base.surfacePools(), 0D, HydrologyPlannerSettings.SeaCaves.disabled());

        assertEquals(1.6D, banks.mouthFlareRatio(), 0D);
        int widened = (int) StrictMath.ceil(surface.maximumWidth() * (4D - banks.mouthFlareRatio()) / 2D);
        assertTrue(wide.publicationRadius() - base.publicationRadius() >= widened - 1);
        assertTrue(wide.publicationRadius() > base.publicationRadius());
    }

    @Test
    public void nonPositiveWaterfallThresholdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Hydraulics(0));
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
                1,
                1)
        );
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Outlets(
                true,
                accepted,
                accepted,
                true,
                0,
                0,
                65,
                1,
                1)
        );
    }

    @Test
    public void coastalOutletBudgetIsBounded() {
        HydrologyPlannerSettings.Grotto grotto = new HydrologyPlannerSettings.Grotto(true, 1, 1, 1, 1);
        HydrologyPlannerSettings.Outlets separate = new HydrologyPlannerSettings.Outlets(
                true,
                grotto,
                grotto,
                true,
                0,
                0,
                1,
                3,
                0
        );

        assertEquals(0, separate.maximumCoastalPerTile());
        assertEquals(256, new HydrologyPlannerSettings.Outlets(true, grotto, grotto, true, 0, 0, 1, 3, 256)
                .maximumCoastalPerTile());
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Outlets(
                true, grotto, grotto, true, 0, 0, 1, 3, -1));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.Outlets(
                true, grotto, grotto, true, 0, 0, 1, 3, 257));
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

    @Test
    public void seaCaveBoundsAreEnforcedAndTheDefaultsPlanNone() {
        HydrologyPlannerSettings.SeaCaves seaCaves = new HydrologyPlannerSettings.SeaCaves(true, 3, 160, 8, 12);
        assertEquals(3, seaCaves.maximumPerTile());
        assertFalse(HydrologyPlannerSettings.SeaCaves.disabled().enabled());
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, -1, 160, 8, 12));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 65, 160, 8, 12));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 3, 15, 8, 12));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 3, 8193, 8, 12));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 3, 160, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 3, 160, 129, 12));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 3, 160, 8, -1));
        assertThrows(IllegalArgumentException.class, () -> new HydrologyPlannerSettings.SeaCaves(true, 3, 160, 8, 129));

        HydrologyPlannerSettings base = HydrologyPlannerSettings.defaults();
        assertEquals(HydrologyPlannerSettings.SeaCaves.disabled(), base.seaCaves());
        HydrologyPlannerSettings withCaves = new HydrologyPlannerSettings(
                base.seaLevel(),
                base.routing(),
                base.surface(),
                base.hydraulics(),
                base.underground(),
                base.outlets(),
                base.geometry(),
                base.deepFluids(),
                base.surfacePools(),
                base.widestShoreBiomeWidth(),
                seaCaves
        );
        assertEquals(seaCaves, withCaves.seaCaves());
        assertTrue(base.fingerprint() != withCaves.fingerprint());
    }

    @Test
    public void seaCavesAloneReachTheChamberAndItsSweepBeyondTheHalo() {
        HydrologyPlannerSettings base = HydrologyPlannerSettings.defaults();
        HydrologyPlannerSettings.Source noSources = new HydrologyPlannerSettings.Source(false, 0D, 0, 0, 0, 0);
        HydrologyPlannerSettings.Surface surface = new HydrologyPlannerSettings.Surface(
                false, noSources, 4, 8, 2, 4, 10, 1.5D, HydrologyPlannerSettings.Banks.defaults());
        HydrologyPlannerSettings.Underground underground = new HydrologyPlannerSettings.Underground(
                false, noSources, -48, 72, 3, 8, 1, 3, 6, 14, true, 0);
        HydrologyPlannerSettings quiet = new HydrologyPlannerSettings(
                base.seaLevel(),
                base.routing(),
                surface,
                base.hydraulics(),
                underground,
                base.outlets(),
                base.geometry(),
                List.of(),
                List.of(),
                0D,
                HydrologyPlannerSettings.SeaCaves.disabled()
        );
        HydrologyPlannerSettings withCaves = new HydrologyPlannerSettings(
                base.seaLevel(),
                base.routing(),
                surface,
                base.hydraulics(),
                underground,
                base.outlets(),
                base.geometry(),
                List.of(),
                List.of(),
                surface.shoreWidth(),
                new HydrologyPlannerSettings.SeaCaves(true, 1, 64, 8, 12)
        );

        int alignedHalo = Math.min(base.routing().maximumRouteLength(), base.routing().sampleSpacing() * 2)
                / base.routing().sampleSpacing() * base.routing().sampleSpacing();
        assertEquals(0, quiet.publicationRadius());
        assertEquals(alignedHalo + base.outlets().coastalGrotto().horizontalRadius() + 12 + 1, withCaves.publicationRadius());
    }
}
