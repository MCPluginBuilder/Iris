package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls underground-river source allocation independently from surface rivers.")
@Data
public class IrisUndergroundRiverSourceConfig {
    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Expected natural underground sources per qualifying hydrology tile.")
    private double density = 0.25D;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Minimum underground source quota considered for qualifying tiles when policy requires headwaters and a legal outlet exists.")
    private int minimumPerTile = 0;

    @MinNumber(0)
    @MaxNumber(8192)
    @Desc("Minimum distance in blocks between natural underground sources.")
    private int minimumSpacing = 512;
}
