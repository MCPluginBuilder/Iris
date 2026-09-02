package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HydrologyCrossTileSurfaceAdmissionTest {
    @Test
    public void rejectsClusteredIndependentMouths() {
        HydrologyCrossTileSurfaceAdmission.Claim current = claim(
                10L,
                100L,
                List.of(new HydrologyPoint(0, 70, 0), new HydrologyPoint(100, 63, 0)),
                true
        );
        HydrologyCrossTileSurfaceAdmission.Claim blocker = claim(
                20L,
                200L,
                List.of(new HydrologyPoint(0, 70, 48), new HydrologyPoint(112, 63, 24)),
                true
        );

        HydrologyCrossTileSurfaceAdmission.Result result = HydrologyCrossTileSurfaceAdmission.admit(
                List.of(current),
                List.of(new HydrologyCrossTileSurfaceAdmission.RankedClaim(
                        new HydrologyTileKey(0, 0),
                        0,
                        blocker
                )),
                128
        );

        assertEquals(1, result.rejections().size());
        assertEquals(current.courseId(), result.rejections().getFirst().loser().courseId());
    }

    @Test
    public void rejectsCrossingUnrelatedCourses() {
        HydrologyCrossTileSurfaceAdmission.Claim current = claim(
                10L,
                100L,
                List.of(new HydrologyPoint(0, 70, 0), new HydrologyPoint(100, 65, 100)),
                false
        );
        HydrologyCrossTileSurfaceAdmission.Claim blocker = claim(
                20L,
                200L,
                List.of(new HydrologyPoint(0, 70, 100), new HydrologyPoint(100, 65, 0)),
                false
        );

        HydrologyCrossTileSurfaceAdmission.Result result = HydrologyCrossTileSurfaceAdmission.admit(
                List.of(current),
                List.of(new HydrologyCrossTileSurfaceAdmission.RankedClaim(
                        new HydrologyTileKey(0, 0),
                        0,
                        blocker
                )),
                64
        );

        assertEquals(1, result.rejections().size());
    }

    @Test
    public void rejectsNearbySourcesWhenTheirCoursesImmediatelyDiverge() {
        HydrologyCrossTileSurfaceAdmission.Claim current = claim(
                10L,
                100L,
                List.of(new HydrologyPoint(0, 70, 0), new HydrologyPoint(512, 63, 0)),
                true
        );
        HydrologyCrossTileSurfaceAdmission.Claim blocker = claim(
                20L,
                200L,
                List.of(new HydrologyPoint(0, 70, 128), new HydrologyPoint(-512, 63, 128)),
                true
        );

        HydrologyCrossTileSurfaceAdmission.Result result = HydrologyCrossTileSurfaceAdmission.admit(
                List.of(current),
                List.of(new HydrologyCrossTileSurfaceAdmission.RankedClaim(
                        new HydrologyTileKey(0, 0),
                        0,
                        blocker
                )),
                192
        );

        assertEquals(1, result.rejections().size());
    }

    @Test
    public void keepsSeparatedNetworks() {
        HydrologyCrossTileSurfaceAdmission.Claim current = claim(
                10L,
                100L,
                List.of(new HydrologyPoint(0, 70, 0), new HydrologyPoint(100, 63, 0)),
                true
        );
        HydrologyCrossTileSurfaceAdmission.Claim blocker = claim(
                20L,
                200L,
                List.of(new HydrologyPoint(0, 70, 256), new HydrologyPoint(100, 63, 256)),
                true
        );

        HydrologyCrossTileSurfaceAdmission.Result result = HydrologyCrossTileSurfaceAdmission.admit(
                List.of(current),
                List.of(new HydrologyCrossTileSurfaceAdmission.RankedClaim(
                        new HydrologyTileKey(0, 0),
                        0,
                        blocker
                )),
                128
        );

        assertTrue(result.rejections().isEmpty());
    }

    private HydrologyCrossTileSurfaceAdmission.Claim claim(
            long courseId,
            long outletId,
            List<HydrologyPoint> centerline,
            boolean reachesOutlet
    ) {
        return new HydrologyCrossTileSurfaceAdmission.Claim(
                courseId,
                outletId,
                centerline.getLast(),
                reachesOutlet,
                4,
                centerline
        );
    }
}
