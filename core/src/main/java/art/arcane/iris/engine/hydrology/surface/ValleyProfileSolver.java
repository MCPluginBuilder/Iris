package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyCandidateRejection;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;

import java.util.Objects;

public final class ValleyProfileSolver {
    private final HydrologyPlannerSettings.Surface surface;
    private final HydrologyTerrainSampler sampler;
    private final int seaLevel;
    private final int minimumCourseLength;

    public ValleyProfileSolver(
            HydrologyPlannerSettings.Surface surface,
            HydrologyTerrainSampler sampler,
            int seaLevel,
            int minimumCourseLength
    ) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.seaLevel = seaLevel;
        this.minimumCourseLength = minimumCourseLength;
    }

    public ValleyProfile solve(
            SurfaceCenterline centerline,
            ChannelProfile channel,
            SurfaceTerminal terminal,
            int terminalHead
    ) {
        int count = centerline.size();
        int[] crossMin = new int[count];
        int[] crossMax = new int[count];
        int[] centerNatural = new int[count];
        double[] incisionMultiplier = new double[count];
        double roughness = surface.banks().roughness();
        int exposed = count;
        for (int station = 0; station < count; station++) {
            double outline = channel.halfWidth()[station] * (1D + roughness);
            double reach = outline + 2D;
            int stationX = centerline.x()[station];
            int stationZ = centerline.z()[station];
            HydrologyTerrainSample center = sampler.sample(stationX, stationZ);
            if (!SurfaceCellAdmission.writable(center, seaLevel)) {
                exposed = station;
                break;
            }
            centerNatural[station] = center.naturalHeight();
            incisionMultiplier[station] = center.incisionMultiplier();
            double normalX = centerline.normalX(station);
            double normalZ = centerline.normalZ(station);
            int minimum = Integer.MAX_VALUE;
            int maximum = Integer.MIN_VALUE;
            boolean blocked = false;
            for (double offset = -reach; offset <= reach && !blocked; offset += 0.5D) {
                int cellX = (int) StrictMath.round(stationX + normalX * offset);
                int cellZ = (int) StrictMath.round(stationZ + normalZ * offset);
                HydrologyTerrainSample terrain = sampler.sample(cellX, cellZ);
                if (!SurfaceCellAdmission.writable(terrain, seaLevel)) {
                    blocked = true;
                    break;
                }
                if (Math.abs(offset) <= outline + 0.25D) {
                    continue;
                }
                minimum = Math.min(minimum, terrain.naturalHeight());
                maximum = Math.max(maximum, terrain.naturalHeight());
            }
            if (blocked) {
                exposed = station;
                break;
            }
            crossMin[station] = minimum;
            crossMax[station] = maximum;
        }
        if (exposed < 2 || exposed < minimumCourseLength) {
            return ValleyProfile.rejected(HydrologyCandidateRejection.COURSE_TOO_SHORT);
        }
        if (exposed < count && terminal != SurfaceTerminal.OCEAN_MOUTH) {
            return ValleyProfile.rejected(HydrologyCandidateRejection.SURFACE_EXPOSURE);
        }
        int inset = surface.banks().inset();
        int[] head = new int[count];
        for (int station = 0; station < exposed; station++) {
            int ceiling = crossMin[station] - inset;
            head[station] = station == 0 ? ceiling : Math.min(head[station - 1], ceiling);
        }
        if (terminal == SurfaceTerminal.OCEAN_MOUTH || terminal == SurfaceTerminal.COASTAL_GROTTO) {
            for (int station = 0; station < exposed; station++) {
                head[station] = Math.max(head[station], seaLevel);
            }
        }
        if (terminal == SurfaceTerminal.OCEAN_MOUTH) {
            for (int station = exposed; station < count; station++) {
                head[station] = seaLevel;
            }
        }
        if (terminal == SurfaceTerminal.SINKHOLE && head[exposed - 1] < terminalHead) {
            return ValleyProfile.rejected(HydrologyCandidateRejection.SURFACE_SINKHOLE_CLEARANCE);
        }
        if (terminal == SurfaceTerminal.COASTAL_GROTTO && head[exposed - 1] < terminalHead) {
            return ValleyProfile.rejected(HydrologyCandidateRejection.SURFACE_HEAD_RANGE);
        }
        int maximumIncision = surface.maximumIncision();
        for (int station = 0; station < exposed; station++) {
            int permitted = Math.min(maximumIncision, (int) StrictMath.floor(maximumIncision * incisionMultiplier[station]));
            int bed = head[station] - (int) StrictMath.round(channel.depth()[station]);
            if (centerNatural[station] - bed > permitted) {
                return ValleyProfile.rejected(HydrologyCandidateRejection.SURFACE_CORRIDOR_UNSUPPORTED);
            }
        }
        return new ValleyProfile(head, crossMin, crossMax, centerNatural, exposed, null);
    }
}
