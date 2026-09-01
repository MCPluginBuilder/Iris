package art.arcane.iris.probe;

import art.arcane.iris.engine.hydrology.RiverFootprint;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RiverTransectProbeTest {
    @Test
    public void sectionStationsSitAtTenThirtyFiftySeventyAndNinetyPercent() {
        assertArrayEquals(new int[] {2, 6, 10, 14, 18}, RiverTransectProbe.sectionStations(20));
        assertArrayEquals(new int[] {0, 0, 0, 0, 0}, RiverTransectProbe.sectionStations(1));
        assertArrayEquals(new int[] {0, 0, 1, 2, 2}, RiverTransectProbe.sectionStations(3));
    }

    @Test
    public void summaryCountsCutsBankStepsOceanWritesAndSpillingChannels() {
        Map<Long, RiverTransectProbe.ColumnView> columns = new HashMap<>();
        put(columns, 0, 0, 72, 68, 70, RiverTransectProbe.Role.CHANNEL);
        put(columns, 1, 0, 72, 71, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.BANK);
        put(columns, -1, 0, 72, 69, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.BANK);
        put(columns, 0, 1, 72, 70, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.SHORE);
        put(columns, 0, -1, 72, 72, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.NONE);
        put(columns, 2, 0, 72, 72, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.NONE);
        put(columns, 5, 5, 60, 58, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.NONE);
        put(columns, 6, 6, 60, 60, 63, RiverTransectProbe.Role.APRON);

        RiverTransectProbe.CourseSummary summary = RiverTransectProbe.summarize(7L, 12, 63, columns);

        assertEquals(7L, summary.id());
        assertEquals(12, summary.stations());
        assertEquals(4, summary.ownedColumns());
        assertEquals(1, summary.minimumCut());
        assertEquals(4, summary.maximumCut());
        assertEquals(1, summary.maximumBankStep());
        assertEquals(1, summary.oceanWrites());
        assertEquals(1, summary.uncontainedWetCells());
        assertFalse(summary.passes());
    }

    @Test
    public void containedCourseAwayFromTheOceanPasses() {
        Map<Long, RiverTransectProbe.ColumnView> columns = new HashMap<>();
        put(columns, 0, 0, 72, 68, 70, RiverTransectProbe.Role.CHANNEL);
        put(columns, 1, 0, 72, 71, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.BANK);
        put(columns, -1, 0, 72, 71, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.BANK);
        put(columns, 0, 1, 72, 70, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.SHORE);
        put(columns, 0, -1, 72, 71, RiverTransectProbe.NO_WATER, RiverTransectProbe.Role.SHORE);

        RiverTransectProbe.CourseSummary summary = RiverTransectProbe.summarize(1L, 3, 63, columns);

        assertTrue(summary.passes());
        assertEquals(0, summary.oceanWrites());
        assertEquals(0, summary.uncontainedWetCells());
    }

    private static void put(
            Map<Long, RiverTransectProbe.ColumnView> columns,
            int x,
            int z,
            int natural,
            int terrain,
            int water,
            RiverTransectProbe.Role role
    ) {
        columns.put(RiverFootprint.pack(x, z), new RiverTransectProbe.ColumnView(x, z, natural, terrain, water, role));
    }
}
