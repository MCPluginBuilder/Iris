package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyGeometrySampler;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyPoint;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelProfileBuilderTest {
    private static final HydrologyGeometrySampler CONSTANT_GEOMETRY = request -> switch (request.field()) {
        case SURFACE_WIDTH -> 6;
        case SURFACE_DEPTH -> 3;
        default -> request.minimum();
    };

    @Test
    public void cruiseWidthIsConstantAndTapersFromTheHeadwater() {
        SurfaceCenterline centerline = straight(400);
        ChannelProfile profile = builder(1D, 1D).build(centerline, "water", false);

        assertEquals(400, profile.size());
        assertEquals(3D, profile.halfWidth()[200], 1.0E-9D);
        assertEquals(3D, profile.halfWidth()[399], 1.0E-9D);
        assertEquals(3D, profile.depth()[200], 1.0E-9D);
        assertTrue(profile.halfWidth()[0] < 1D);
        for (int station = 1; station < 48; station++) {
            assertTrue(profile.halfWidth()[station] >= profile.halfWidth()[station - 1]);
        }
        assertTrue(profile.depth()[0] >= 1D);
    }

    @Test
    public void directOceanCoursesFlareAtTheMouth() {
        SurfaceCenterline centerline = straight(400);
        ChannelProfile profile = builder(1D, 1D).build(centerline, "water", true);

        assertEquals(3D * 1.6D, profile.halfWidth()[399], 0.05D);
        assertEquals(3D, profile.halfWidth()[300], 1.0E-9D);
    }

    @Test
    public void policyMultipliersScaleWidthDepthAndBank() {
        SurfaceCenterline centerline = straight(400);
        ChannelProfile profile = builder(2D, 0.5D).build(centerline, "water", false);

        assertEquals(6D, profile.halfWidth()[200], 1.0E-9D);
        assertEquals(1.5D, profile.depth()[200], 1.0E-9D);
        assertEquals(1.25D, profile.bankMultiplier()[200], 1.0E-9D);
        assertEquals(6D * 1.25D + 2D, profile.collar(200, 0.25D), 1.0E-9D);
    }

    private static ChannelProfileBuilder builder(double widthMultiplier, double depthMultiplier) {
        HydrologyTerrainSampler sampler = (int x, int z) -> new HydrologyTerrainSample(
                80,
                0D,
                false,
                false,
                48,
                50,
                true,
                true,
                true,
                false,
                false,
                false,
                0D,
                1D,
                1D,
                widthMultiplier,
                depthMultiplier,
                1D,
                1D,
                1.25D,
                "parent",
                "parent",
                "parent",
                "parent",
                "parent",
                "parent",
                List.of("water")
        );
        return new ChannelProfileBuilder(HydrologyPlannerSettings.defaults().surface(), sampler, CONSTANT_GEOMETRY);
    }

    private static SurfaceCenterline straight(int stations) {
        return SurfaceCenterline.densify(path(stations));
    }

    private static List<HydrologyPoint> path(int stations) {
        return List.of(new HydrologyPoint(0, 0, 0), new HydrologyPoint(stations - 1, 0, 0));
    }
}
