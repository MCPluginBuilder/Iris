package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls surface-river source allocation.")
@Data
public class IrisSurfaceRiverSourceConfig {
    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Expected natural surface headwaters per qualifying hydrology tile.")
    private double density = 0.5D;

    @MinNumber(-2048)
    @MaxNumber(2048)
    @Desc("Minimum natural terrain elevation eligible for a surface headwater.")
    private int minimumElevation = 88;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Minimum source quota considered for qualifying tiles when policy requires headwaters and a legal outlet exists.")
    private int minimumPerTile = 0;

    @MinNumber(0)
    @MaxNumber(8192)
    @Desc("Minimum distance in blocks between natural surface headwaters.")
    private int minimumSpacing = 384;
}
