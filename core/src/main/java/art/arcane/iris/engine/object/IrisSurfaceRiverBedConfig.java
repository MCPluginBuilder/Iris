package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls the material under and beside surface river water.")
@Data
public class IrisSurfaceRiverBedConfig {
    @Desc("Allow sand, gravel and other falling blocks in the bed, shore and eroded banks. When false they are replaced by the padding palette.")
    private boolean allowGravityBlocks = false;

    @MinNumber(0)
    @MaxNumber(8)
    @Desc("Blocks below the bed that are also kept free of falling blocks, so nothing under the water can collapse.")
    private int padding = 2;

    @Desc("Blocks used in place of falling blocks in the bed, padding, shore and banks.")
    private IrisMaterialPalette paddingPalette = new IrisMaterialPalette().qclear().qadd("clay").qadd("dirt");

    @Desc("Optional palette painted over the wet channel bed under the water instead of the biome's own layers. Disabled by default, which keeps the surface biome's layers.")
    private IrisRiverMaterialConfig material = new IrisRiverMaterialConfig();
}
