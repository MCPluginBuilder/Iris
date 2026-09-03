package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("The shape of the eroded valley side between the shore band and natural terrain.")
public enum IrisRiverBlendStyle {
    @Desc("An eased S-curve from the bank top out to natural terrain, flat at both ends and steepest across the middle of the band; blendCurve skews it.")
    SMOOTH,
    @Desc("A straight slope from the bank top to natural terrain with a sharp shoulder at the shore and a sharp lip at the top; blendCurve bends it.")
    LINEAR,
    @Desc("A hollowed valley side that climbs fast beside the shore and flattens out toward natural terrain; blendCurve deepens the hollow.")
    CONCAVE,
    @Desc("The eased curve cut into level steps, terraceSteps of them between the bank top and natural terrain.")
    TERRACED,
    @Desc("A level bench at the bank top for cliffFraction of the band, then a vertical wall up to natural terrain.")
    CLIFF
}
