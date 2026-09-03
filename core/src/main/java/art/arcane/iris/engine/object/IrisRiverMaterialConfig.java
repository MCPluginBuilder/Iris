package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("An optional block palette painted over the top layers of one part of a river instead of the biome's own layers.")
@Data
public class IrisRiverMaterialConfig {
    @Desc("Paint this palette. When false the biome layers are used as before.")
    private boolean enabled = false;

    @Desc("Blocks to paint. Any solid palette.")
    private IrisMaterialPalette palette = new IrisMaterialPalette();

    @MinNumber(1)
    @MaxNumber(8)
    @Desc("How many layers down from the surface the palette replaces, in blocks.")
    private int depth = 1;
}
