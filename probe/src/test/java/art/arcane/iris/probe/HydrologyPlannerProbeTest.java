package art.arcane.iris.probe;

import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class HydrologyPlannerProbeTest {
    @Test
    public void deterministicAcceptedCoveragePublishesEveryFeatureType() {
        Map<HydrologyFeatureType, HydrologyFeatureRef> coverage =
                HydrologyPlannerProbe.deterministicAcceptedCoverage();

        assertEquals(EnumSet.allOf(HydrologyFeatureType.class), coverage.keySet());
        for (Map.Entry<HydrologyFeatureType, HydrologyFeatureRef> entry : coverage.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().type());
        }
    }
}
