package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("A round pond at one end of a surface river, holding the river's water level there.")
@Data
public class IrisSurfaceRiverPondConfig {
    @Desc("Dig the pond.")
    private boolean enabled = true;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Smallest pond radius in blocks; the radius is chosen per river between the minimum and the maximum, and shrinks where the ground falls away around the rim.")
    private int minimumRadius = 6;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Largest pond radius in blocks.")
    private int maximumRadius = 12;

    @MinNumber(1)
    @MaxNumber(8)
    @Desc("Depth of the pond bowl below the water surface at its center.")
    private int depth = 3;

    public IrisSurfaceRiverPondConfig(int minimumRadius, int maximumRadius, int depth) {
        this.minimumRadius = minimumRadius;
        this.maximumRadius = maximumRadius;
        this.depth = depth;
    }
}
