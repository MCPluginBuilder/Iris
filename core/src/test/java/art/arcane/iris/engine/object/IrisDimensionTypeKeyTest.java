package art.arcane.iris.engine.object;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IrisDimensionTypeKeyTest {
    @Test
    public void dimensionTypeKeyUsesSanitizedSemanticPackKey() {
        IrisDimension dimension = new IrisDimension();
        dimension.setLoadKey("Overworld");

        assertEquals("overworld", dimension.getDimensionTypeKey());
    }

    @Test
    public void dimensionTypeKeySanitizesUnsafePackCharacters() {
        IrisDimension dimension = new IrisDimension();
        dimension.setLoadKey("Worlds/My Pack");

        assertEquals("worlds_my_pack", dimension.getDimensionTypeKey());
    }

    @Test
    public void customBiomeKeyPreservesFlatDimensionNamespace() {
        IrisDimension dimension = new IrisDimension();
        dimension.setLoadKey("Overworld");

        assertEquals("overworld:aurora", dimension.getCustomBiomeKey("Aurora"));
    }

    @Test
    public void customBiomeKeyMapsRecursiveDimensionPath() {
        IrisDimension dimension = new IrisDimension();
        dimension.setLoadKey("Layers/Sky");

        assertEquals("layers:sky/aurora", dimension.getCustomBiomeKey("Aurora"));
    }
}
