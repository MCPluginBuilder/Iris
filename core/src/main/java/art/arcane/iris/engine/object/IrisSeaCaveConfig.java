package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Sea caves: coastal grottos that open from the ocean into the coast without a river. The chamber size, headroom and volume cap are the coastal grotto's.")
@Data
public class IrisSeaCaveConfig {
    @Desc("Plan sea caves along the coast. Requires the coastal grotto to be enabled.")
    private boolean enabled = true;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Sea caves accepted per planning tile; the steepest owned coast is taken first.")
    private int maximumPerTile = 3;

    @MinNumber(16)
    @MaxNumber(8192)
    @Desc("Least distance in blocks between two sea caves. Must be at least twice the coastal grotto horizontalRadius.")
    private int minimumSpacing = 160;

    @MinNumber(1)
    @MaxNumber(128)
    @Desc("The coast must stand this many blocks above the sea both at the shoreline and at the back of the chamber.")
    private int minimumCoastHeight = 8;

    @MinNumber(0)
    @MaxNumber(128)
    @Desc("How far inland from the shoreline the chamber is swept. 0 leaves a single chamber at the shore.")
    private int depth = 12;
}
