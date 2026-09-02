package art.arcane.iris.engine.hydrology.surface;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurfaceNoiseTest {
    @Test
    public void valuesStayInRangeAndAreDeterministic() {
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                double value = SurfaceNoise.value(77L, x, z, 16);
                assertTrue(value >= 0D && value <= 1D);
                assertEquals(value, SurfaceNoise.value(77L, x, z, 16), 0D);
                double signed = SurfaceNoise.signed(77L, x, z, 16);
                assertTrue(signed >= -1D && signed <= 1D);
            }
        }
    }

    @Test
    public void neighbouringCellsAreCoherent() {
        double maximumStep = 0D;
        for (int x = 0; x < 200; x++) {
            maximumStep = Math.max(maximumStep, Math.abs(SurfaceNoise.value(5L, x, 3, 16) - SurfaceNoise.value(5L, x + 1, 3, 16)));
        }
        assertTrue(maximumStep < 0.2D);
    }

    @Test
    public void smoothStepEasesBetweenZeroAndOne() {
        assertEquals(0D, SurfaceNoise.smoothStep(0D), 0D);
        assertEquals(1D, SurfaceNoise.smoothStep(1D), 0D);
        assertEquals(0.5D, SurfaceNoise.smoothStep(0.5D), 1.0E-9D);
        assertTrue(SurfaceNoise.smoothStep(0.25D) < 0.25D);
        assertTrue(SurfaceNoise.smoothStep(0.75D) > 0.75D);
    }
}
