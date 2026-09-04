package art.arcane.iris.engine;

import art.arcane.iris.engine.framework.Engine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IrisComplexStudioCacheTest {
    @Test
    public void studioUsesWideNoiseCacheWithoutChangingConfiguredLiveWorldSize() {
        Engine engine = mock(Engine.class);

        when(engine.isStudio()).thenReturn(false);
        assertEquals(1_024, IrisComplex.noiseCacheSize(engine, 1_024));

        when(engine.isStudio()).thenReturn(true);
        assertEquals(32_768, IrisComplex.noiseCacheSize(engine, 1_024));
        assertEquals(65_536, IrisComplex.noiseCacheSize(engine, 65_536));
    }
}
