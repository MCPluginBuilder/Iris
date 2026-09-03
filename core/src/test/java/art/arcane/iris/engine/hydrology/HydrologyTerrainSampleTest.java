package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class HydrologyTerrainSampleTest {
    @Test
    public void reslopingReusesAnAlreadyNormalizedProfileList() {
        HydrologyTerrainSample sample = sample(List.of("deep", "water"));

        HydrologyTerrainSample resloped = sample.withSlope(2D);

        assertEquals(2D, resloped.slope(), 0D);
        assertSame(sample.preferredProfileKeys(), resloped.preferredProfileKeys());
    }

    @Test
    public void profileNormalizationStillTrimsSortsAndDeduplicates() {
        HydrologyTerrainSample sample = sample(Arrays.asList(" water ", "", null, "deep", "water"));

        assertEquals(List.of("deep", "water"), sample.preferredProfileKeys());
    }

    @Test
    public void normalizedMutableProfilesAreDefensivelyCopied() {
        ArrayList<String> profiles = new ArrayList<>(List.of("deep", "water"));
        HydrologyTerrainSample sample = sample(profiles);

        profiles.clear();

        assertEquals(List.of("deep", "water"), sample.preferredProfileKeys());
        assertThrows(UnsupportedOperationException.class, () -> sample.preferredProfileKeys().add("other"));
    }

    @Test
    public void emptyProfilesStillUseDefaultAndInvalidSlopeStillFails() {
        assertEquals(List.of("default"), sample(List.of()).preferredProfileKeys());
        assertThrows(IllegalArgumentException.class, () -> sample(-1D, List.of("water")));
    }

    private static HydrologyTerrainSample sample(List<String> profiles) {
        return sample(1D, profiles);
    }

    private static HydrologyTerrainSample sample(double slope, List<String> profiles) {
        return new HydrologyTerrainSample(
                80,
                slope,
                false,
                true,
                40,
                50,
                true,
                true,
                true,
                false,
                true,
                false,
                1D,
                1D,
                1D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                profiles, List.of(),
                Double.NaN,
                null
        );
    }
    @Test
    public void shoreBiomeWidthInheritsWhenUnsetAndRejectsNegatives() {
        HydrologyTerrainSample unset = HydrologyTerrainSample.openLand(80, 0D, "parent");
        assertTrue(Double.isNaN(unset.shoreBiomeWidth()));
        assertEquals(1.5D, unset.shoreBiomeWidth(1.5D), 0D);

        HydrologyTerrainSample wide = sample(List.of("default"), 12D, null);
        assertEquals(12D, wide.shoreBiomeWidth(1.5D), 0D);
        assertThrows(IllegalArgumentException.class, () -> sample(List.of("default"), -1D, null));
        assertThrows(IllegalArgumentException.class, () -> sample(List.of("default"), Double.POSITIVE_INFINITY, null));
    }

    @Test
    public void confinesKeyIsNormalizedAndDecidesWhereWaterMayDrain() {
        HydrologyTerrainSample open = sample(List.of("default"), Double.NaN, " ");
        HydrologyTerrainSample west = sample(List.of("default"), Double.NaN, "region:west");
        HydrologyTerrainSample east = sample(List.of("default"), Double.NaN, "region:east");

        assertNull(open.confinesKey());
        assertTrue(open.drainsInto(west));
        assertTrue(west.drainsInto(west));
        assertFalse(west.drainsInto(east));
        assertFalse(west.drainsInto(open));
    }

    private static HydrologyTerrainSample sample(List<String> profiles, double shoreBiomeWidth, String confinesKey) {
        return new HydrologyTerrainSample(
                80, 0D, false, false, 48, 50, true, true, true, false, false, false,
                0D, 1D, 1D, 1D, 1D, 1D, 1D, 1D,
                "parent", "parent", "parent", "parent", "parent", "parent",
                profiles, List.of(), shoreBiomeWidth, confinesKey
        );
    }
}
