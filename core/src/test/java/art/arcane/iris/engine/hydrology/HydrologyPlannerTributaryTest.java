package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HydrologyPlannerTributaryTest {
    private static final HydrologyTileKey TILE = new HydrologyTileKey(0, 0);

    @Test
    public void aSecondSourceJoinsTheMainStemAsATributaryWhenAllowed() {
        HydrologyTerrainSampler terrain = HydrologyPlannerTributaryTest::valley;

        HydrologyTile single = new HydrologyPlanner(19L, settings(0), terrain).plan(TILE);
        HydrologyTile split = new HydrologyPlanner(19L, settings(1), terrain).plan(TILE);

        List<RiverCourse> singleCourses = surface(single);
        List<RiverCourse> splitCourses = surface(split);
        assertEquals(single.diagnosticCandidates().toString(), 1, singleCourses.size());
        assertEquals(split.diagnosticCandidates().toString(), 2, splitCourses.size());
        RiverCourse stem = null;
        RiverCourse tributary = null;
        for (RiverCourse course : splitCourses) {
            if (stem == null || stations(course) > stations(stem)) {
                tributary = stem;
                stem = course;
            } else {
                tributary = course;
            }
        }
        assertNotNull(stem);
        assertNotNull(tributary);
        assertEquals(stem.outletId(), tributary.outletId());
        assertTrue(stem.sourceNodeId().getAsLong() != tributary.sourceNodeId().getAsLong());

        HydrologyPoint confluence = tributary.segments().getLast().end();
        Integer stemHead = null;
        for (HydraulicSegment segment : stem.segments()) {
            for (HydrologyPoint point : segment.centerline()) {
                if (point.x() == confluence.x() && point.z() == confluence.z()) {
                    stemHead = point.y();
                }
            }
        }
        assertNotNull("tributary ends on the stem", stemHead);
        assertTrue(confluence.y() >= stemHead);
        assertTrue(stations(tributary) >= 192);
        assertTrue(stations(tributary) < stations(stem));
        for (HydraulicSegment segment : tributary.segments()) {
            assertTrue(segment.upstreamHeadY() >= segment.downstreamHeadY());
        }
    }

    private static int stations(RiverCourse course) {
        int stations = 0;
        for (HydraulicSegment segment : course.segments()) {
            stations += segment.centerline().size();
        }
        return stations;
    }

    private static List<RiverCourse> surface(HydrologyTile tile) {
        ArrayList<RiverCourse> courses = new ArrayList<>();
        for (RiverCourse course : tile.courses()) {
            if (course.type() == RiverCourseType.SURFACE) {
                courses.add(course);
            }
        }
        return courses;
    }

    /** A plain sloping east into the sea with a valley along z = 0 that both flanks drain into. */
    private static HydrologyTerrainSample valley(int x, int z) {
        if (x >= 900) {
            return HydrologyTerrainSample.ocean(50, "sea");
        }
        // The valley floor wanders so the stem never runs straight along the lattice, and the eastward
        // fall is steeper than the valley flanks so routes drift into the valley at a shallow angle.
        int valleyCenter = (int) Math.round(Math.sin(x / 150D) * 40D);
        int valley = Math.max(0, 12 - Math.abs(z - valleyCenter) / 20);
        int wave = (int) Math.round(Math.sin(x / 29D + z / 43D) * 3D + Math.sin(z / 19D - x / 61D + 1D) * 2D);
        int height = 240 - x / 5 - valley + wave;
        boolean source = x >= 32 && x <= 96 && (Math.abs(z - 300) <= 40 || Math.abs(z - 150) <= 40);
        return new HydrologyTerrainSample(
                height,
                0D,
                false,
                false,
                height - 40,
                height - 38,
                true,
                true,
                source,
                false,
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
                "land",
                "land",
                "land",
                "land",
                "land",
                "land",
                List.of("default"),
                List.of()
        );
    }

    private static HydrologyPlannerSettings settings(int tributaries) {
        HydrologyPlannerSettings base = HydrologyPlannerSettings.defaults();
        HydrologyPlannerSettings.Routing routing = new HydrologyPlannerSettings.Routing(
                1024, 64, base.routing().maximumRouteNodes(), 4096, 384, 192, 1.5D, 24D, 2D, 0.2D, 1D, tributaries);
        HydrologyPlannerSettings.Source sources = new HydrologyPlannerSettings.Source(true, 4D, 0, 0, 4, 128);
        HydrologyPlannerSettings.Surface surface = new HydrologyPlannerSettings.Surface(
                true, sources, 4, 8, 2, 4, 40, 1.5D, HydrologyPlannerSettings.Banks.defaults());
        HydrologyPlannerSettings.Underground underground = new HydrologyPlannerSettings.Underground(
                false, base.underground().sources(), -48, 72, 3, 8, 1, 3, 6, 14, true);
        HydrologyPlannerSettings.Outlets outlets = new HydrologyPlannerSettings.Outlets(
                true,
                base.outlets().coastalGrotto(),
                base.outlets().inlandGrotto(),
                false,
                base.outlets().coastalCliffMinimumHeight(),
                base.outlets().mouthLevelingDistance(),
                base.outlets().maximumOceanApron(),
                1
        );
        return new HydrologyPlannerSettings(
                base.seaLevel(),
                routing,
                surface,
                base.hydraulics(),
                underground,
                outlets,
                base.geometry(),
                List.of(),
                List.of()
        );
    }
}
