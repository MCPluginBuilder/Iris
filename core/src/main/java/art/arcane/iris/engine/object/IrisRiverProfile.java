package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.Required;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Reusable river fluid profile selected by river policies.")
@Data
public class IrisRiverProfile {
    @Required
    @Desc("Profile identifier referenced by dimension, region, and biome river policies.")
    private String id = "default";

    @Required
    @Desc("Fluid palette used by accepted river footprints using this profile.")
    private IrisMaterialPalette fluidPalette = new IrisMaterialPalette().qclear().qadd("water");
}
