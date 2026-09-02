package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.volmlib.util.collection.KList;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Dimension-owned physical hydrology configuration.")
@Data
public class IrisHydrology {
    @Desc("Terrain-guided surface and underground river configuration.")
    private IrisRiverHydrology rivers = new IrisRiverHydrology();

    @ArrayType(type = IrisDeepFluidConfig.class)
    @Desc("Independent deep-fluid systems that do not consume the surface or underground river source budgets.")
    private KList<IrisDeepFluidConfig> deepFluids = new KList<>();

    @ArrayType(type = IrisSurfacePoolConfig.class, min = 0)
    @Desc("Standing surface pools such as lava pools. Each region or biome opts in through riverPolicy.surfacePools.")
    private KList<IrisSurfacePoolConfig> surfacePools = new KList<>();
}
