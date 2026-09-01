package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls minimum complete river lengths before a route is published.")
@Data
public class IrisRiverBranchingConfig {
    @MinNumber(0)
    @MaxNumber(32768)
    @Desc("Minimum complete surface source-to-outlet route length in blocks.")
    private int minimumSurfaceCourseLength = 384;

    @MinNumber(0)
    @MaxNumber(32768)
    @Desc("Minimum complete underground source-to-outlet route length in blocks.")
    private int minimumUndergroundCourseLength = 192;

}
