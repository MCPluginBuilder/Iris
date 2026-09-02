package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
                profiles, List.of()
        );
    }
}
