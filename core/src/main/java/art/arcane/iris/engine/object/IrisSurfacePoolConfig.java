package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.iris.engine.object.annotations.Required;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("A standing surface pool: a bowl cut into open ground and filled with a fluid, placed where the river policy allows it.")
@Data
public class IrisSurfacePoolConfig {
    @Required
    @Desc("Pool identifier. Policies list it under surfacePools and locators accept it as a selector.")
    private String id = "lava_pool";

    @Required
    @Desc("Fluid palette filling the pool.")
    private IrisMaterialPalette fluidPalette = new IrisMaterialPalette().qclear().qadd("lava");

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Expected pools per hydrology tile where the policy allows them.")
    private double density = 0.75D;

    @MinNumber(32)
    @MaxNumber(8192)
    @Desc("Nominal spacing in blocks between candidate pool sites.")
    private int spacing = 384;

    @MinNumber(2)
    @MaxNumber(16)
    @Desc("Smallest pool radius in blocks.")
    private int minimumRadius = 4;

    @MinNumber(2)
    @MaxNumber(16)
    @Desc("Largest pool radius in blocks.")
    private int maximumRadius = 7;

    @MinNumber(1)
    @MaxNumber(8)
    @Desc("Fluid depth at the centre of the pool.")
    private int depth = 2;

    @RegistryListResource(IrisBiome.class)
    @Desc("Biome applied to the pool bed and its rim. Leave empty to keep the surrounding biome.")
    private String biome = "";
}
