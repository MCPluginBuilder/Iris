package art.arcane.iris.probe;

import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class HydrologyPlannerProbeTest {
    @Test
    public void deterministicAcceptedCoveragePublishesEveryReachableFeatureType() {
        Map<HydrologyFeatureType, HydrologyFeatureRef> coverage =
                HydrologyPlannerProbe.deterministicAcceptedCoverage();

        assertEquals(HydrologyPlannerProbe.REQUIRED_FEATURE_TYPES, coverage.keySet());
        for (Map.Entry<HydrologyFeatureType, HydrologyFeatureRef> entry : coverage.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().type());
        }
    }

    @Test
    public void requiredCoverageExcludesTheRemovedRidgeBore() {
        assertFalse(HydrologyPlannerProbe.REQUIRED_FEATURE_TYPES.contains(HydrologyFeatureType.RIDGE_BORE));
        assertEquals(
                EnumSet.complementOf(EnumSet.of(HydrologyFeatureType.RIDGE_BORE)),
                HydrologyPlannerProbe.REQUIRED_FEATURE_TYPES
        );
    }
}
