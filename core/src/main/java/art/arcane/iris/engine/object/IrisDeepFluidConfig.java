package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.Required;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Independent deep-fluid configuration outside the surface and underground river source budgets.")
@Data
public class IrisDeepFluidConfig {
    @Required
    @Desc("Deep-fluid system identifier.")
    private String id = "deep_lava";

    @Required
    @Desc("Fluid palette used by accepted deep pools and short channels.")
    private IrisMaterialPalette fluidPalette = new IrisMaterialPalette().qclear().qadd("lava");

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Expected independent deep-fluid sources per hydrology tile.")
    private double density = 0.125D;

    @MinNumber(16)
    @MaxNumber(8192)
    @Desc("Nominal spacing in blocks between independent deep-fluid source sites.")
    private int spacing = 768;

    @Desc("Deep-fluid elevation in world Y.")
    private IrisStyledRange height = new IrisStyledRange(
            -192D,
            32D,
            new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(1024D)
    );

    @MinNumber(2)
    @MaxNumber(128)
    @Desc("Maximum horizontal radius in blocks of an accepted deep-fluid body.")
    private int horizontalRadius = 14;

    @MinNumber(2)
    @MaxNumber(64)
    @Desc("Maximum vertical radius in blocks of an accepted deep-fluid body.")
    private int verticalRadius = 6;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Wet width in blocks of an accepted short deep-fluid channel.")
    private int channelWidth = 3;

    @MinNumber(1)
    @MaxNumber(32)
    @Desc("Wet depth in blocks of an accepted deep-fluid body.")
    private int depth = 1;

    @MinNumber(1)
    @MaxNumber(63)
    @Desc("Required dry clearance above an accepted deep-fluid body.")
    private int headroom = 6;

    @Desc("Allow accepted sources to form fully contained pools.")
    private boolean containedPools = true;

    @Desc("Allow accepted sources to form short contained channels.")
    private boolean shortChannels = false;
}
