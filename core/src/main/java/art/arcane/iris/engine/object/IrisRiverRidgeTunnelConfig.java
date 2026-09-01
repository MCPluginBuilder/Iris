package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls short contained ridge bores on otherwise viable surface courses.")
@Data
public class IrisRiverRidgeTunnelConfig {
    @Desc("Allow short ridge bores when they preserve the accepted drainage course.")
    private boolean enabled = true;

    @MinNumber(1)
    @MaxNumber(4096)
    @Desc("Maximum accepted ridge-bore length in blocks.")
    private int maximumLength = 192;

    @MinNumber(1)
    @MaxNumber(128)
    @Desc("Dry vertical clearance above the river head inside a ridge bore.")
    private int headroom = 10;
}
