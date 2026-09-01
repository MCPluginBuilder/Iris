package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Controls deterministic river head transitions and hydraulic segment classification.")
@Data
public class IrisRiverHydraulicsConfig {
    @Desc("Target longitudinal length of level pools between head transitions.")
    private IrisStyledRange targetPoolLength = range(80D, 180D, 1024D);

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Normal one-step head loss represented by a riffle.")
    private int riffleDrop = 1;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Largest head loss represented as a gradual cascade.")
    private int maximumGradualDrop = 7;

    @MinNumber(1)
    @MaxNumber(1024)
    @Desc("Largest longitudinal distance used for one gradual cascade transition.")
    private int maximumGradualLength = 24;

    @MinNumber(1)
    @MaxNumber(128)
    @Desc("Minimum required head loss represented by a waterfall.")
    private int waterfallMinimumDrop = 8;

    private static IrisStyledRange range(double min, double max, double zoom) {
        return new IrisStyledRange(min, max, new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom));
    }
}
