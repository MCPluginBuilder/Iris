package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Ponds at the two ends of a surface river: the spring it rises from and, for a river that ends inland, the pool it drains into.")
@Data
public class IrisSurfaceRiverPondsConfig {
    @Desc("The spring pond every surface river rises from.")
    private IrisSurfaceRiverPondConfig source = new IrisSurfaceRiverPondConfig(6, 12, 3);

    @Desc("The pond a river that ends inland drains into; rivers that reach the ocean get none.")
    private IrisSurfaceRiverPondConfig terminal = new IrisSurfaceRiverPondConfig(4, 7, 3);
}
