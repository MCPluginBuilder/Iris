package art.arcane.iris.engine.hydrology;

import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.CavePositionIndex;
import art.arcane.iris.engine.hydrology.cave.CaveVoxel;
import art.arcane.iris.engine.hydrology.cave.CaveVoxelView;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveAction;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveCandidate;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveContainmentPlanner;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveFluidPolicy;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveGrottoShape;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveMode;
import art.arcane.iris.engine.hydrology.cave.HydrologyCavePlan;
import art.arcane.iris.engine.hydrology.cave.HydrologyCavePlannerSettings;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveRejection;
import art.arcane.iris.engine.hydrology.cave.HydrologyCaveSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class HydrologyCaveCourseFilter {
    private static final long DIAGNOSTIC_SALT = 0x43415645464c5452L;
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] HORIZONTAL_NEIGHBORS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final CaveVoxelView GENERATED_CHANNEL_VIEW = new CaveVoxelView() {
        @Override
        public boolean isInWorld(CavePosition position) {
            return true;
        }

        @Override
        public CaveVoxel voxelAt(CavePosition position) {
            return CaveVoxel.UNCONDITIONAL;
        }

        @Override
        public boolean isOpenToSurface(CavePosition position) {
            return false;
        }

        @Override
        public boolean isAboveTerrainSurface(CavePosition position) {
            return false;
        }
    };

    private final HydrologyCaveContainmentPlanner planner;
    private final CaveVoxelView view;
    private final Options options;
    private final Map<CandidateKey, HydrologyCaveCandidate> candidateCache;
    private final HydrologyCaveContainmentPlanner.ValidationCache validationCache;
    private final HydrologyObservedPlannedSurface plannedSurface;

    HydrologyCaveCourseFilter(CaveVoxelView view, Options options) {
        this(view, options, null, null, null);
    }

    HydrologyCaveCourseFilter(
            CaveVoxelView view,
            Options options,
            Map<CandidateKey, HydrologyCaveCandidate> candidateCache
    ) {
        this(view, options, candidateCache, null, null);
    }

    HydrologyCaveCourseFilter(
            CaveVoxelView view,
            Options options,
            Map<CandidateKey, HydrologyCaveCandidate> candidateCache,
            HydrologyCaveContainmentPlanner.ValidationCache validationCache,
            HydrologyObservedPlannedSurface plannedSurface
    ) {
        this.planner = new HydrologyCaveContainmentPlanner();
        this.options = Objects.requireNonNull(options);
        CaveVoxelView observedView = Objects.requireNonNull(view);
        this.view = options.connectToExistingCaves() ? observedView : GENERATED_CHANNEL_VIEW;
        this.candidateCache = candidateCache;
        this.validationCache = validationCache;
        this.plannedSurface = plannedSurface;
    }

    Result filter(
            List<DrainageNode> nodes,
            List<DrainageEdge> edges,
            List<RiverOutlet> outlets,
            List<RiverCourse> courses,
            Iterable<HydrologyColumnSample> columns,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        return filter(nodes, edges, outlets, courses, columns, null, diagnostics);
    }

    Result filter(
            List<DrainageNode> nodes,
            List<DrainageEdge> edges,
            List<RiverOutlet> outlets,
            List<RiverCourse> courses,
            HydrologyFootprintCompiler.ValidationRaster validation,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        return filter(nodes, edges, outlets, courses, null, validation, diagnostics);
    }

    private Result filter(
            List<DrainageNode> nodes,
            List<DrainageEdge> edges,
            List<RiverOutlet> outlets,
            List<RiverCourse> courses,
            Iterable<HydrologyColumnSample> columns,
            HydrologyFootprintCompiler.ValidationRaster validation,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        List<RiverCourse> normalizedCourses = withoutZeroContributionEdges(courses);
        LinkedHashMap<Long, RiverCourse> publishedCoursesById = new LinkedHashMap<>(normalizedCourses.size());
        for (RiverCourse course : normalizedCourses) {
            publishedCoursesById.put(course.id(), course);
        }
        ArrayList<RiverCourse> caveCourses = new ArrayList<>(normalizedCourses.size());
        for (RiverCourse course : normalizedCourses) {
            if (representativeCaveSegment(course) != null) {
                caveCourses.add(course);
            }
        }
        LinkedHashMap<Long, CandidateKey> candidateKeys = new LinkedHashMap<>(caveCourses.size());
        LinkedHashMap<Long, HydrologyCaveCandidate> cachedCandidates = new LinkedHashMap<>(caveCourses.size());
        ArrayList<RiverCourse> uncachedCourses = new ArrayList<>(caveCourses.size());
        for (RiverCourse course : caveCourses) {
            CandidateKey key = CandidateKey.create(course, options);
            candidateKeys.put(course.id(), key);
            HydrologyCaveCandidate cached = candidateCache == null ? null : candidateCache.get(key);
            if (cached == null) {
                uncachedCourses.add(course);
            } else {
                cachedCandidates.put(course.id(), cached);
            }
        }
        Set<Long> preflightRejectedCourseIds = validation == null
                ? preflightRejectedCourseIds(uncachedCourses, columns, diagnostics)
                : preflightRejectedCourseIds(uncachedCourses, validation, diagnostics);
        ArrayList<RiverCourse> candidateCourses = new ArrayList<>(caveCourses.size());
        for (RiverCourse course : caveCourses) {
            if (!preflightRejectedCourseIds.contains(course.id())) {
                candidateCourses.add(course);
            }
        }
        uncachedCourses.removeIf((RiverCourse course) -> preflightRejectedCourseIds.contains(course.id()));
        LinkedHashMap<Long, CandidateBuilder> builders = validation == null
                ? candidateBuilders(uncachedCourses, columns)
                : candidateBuilders(uncachedCourses, validation);
        if (candidateCourses.isEmpty() && preflightRejectedCourseIds.isEmpty()) {
            Graph graph = acceptedGraph(nodes, edges, outlets, normalizedCourses);
            return new Result(
                    graph.nodes(),
                    graph.edges(),
                    graph.outlets(),
                    normalizedCourses,
                    List.of()
            );
        }

        HashSet<Long> rejectedCourseIds = new HashSet<>(preflightRejectedCourseIds);
        ArrayList<HydrologyCaveCandidate> candidates = new ArrayList<>(candidateCourses.size());
        Set<HydrologyCaveCandidate> exposureValidatedCandidates =
                Collections.newSetFromMap(new IdentityHashMap<>());
        LinkedHashMap<Long, RiverCourse> coursesById = new LinkedHashMap<>(candidateCourses.size());
        for (RiverCourse course : candidateCourses) {
            CandidateKey key = candidateKeys.get(course.id());
            HydrologyCaveCandidate candidate = cachedCandidates.get(course.id());
            if (candidate == null) {
                CandidateBuilder builder = builders.get(course.id());
                if (builder == null) {
                    throw new IllegalStateException("Hydrology cave candidate raster was empty.");
                }
                candidate = builder.build(options);
                if (candidateCache != null) {
                    candidateCache.put(key, candidate);
                }
                exposureValidatedCandidates.add(candidate);
            }
            SurfaceComposition composition = validation == null
                    ? SurfaceComposition.accepted(candidate)
                    : composeSurfacePublication(candidate, course, validation, publishedCoursesById);
            if (!composition.accepted()) {
                if (rejectedCourseIds.add(course.id())) {
                    HydraulicSegment representative = representativeCaveSegment(course);
                    addDiagnostic(
                            course,
                            representative,
                            representative.type(),
                            HydrologyCandidateRejection.CAVE_CONTAINMENT,
                            diagnostics,
                            HydrologyCaveRejection.OVERLAPPING_SOURCE.ordinal()
                    );
                }
                continue;
            }
            candidate = composition.candidate();
            if (course.surfaceSinkholeContinuation()) {
                exposureValidatedCandidates.add(candidate);
            }
            candidates.add(candidate);
            coursesById.put(course.id(), course);
        }
        alignSharedTerminalCandidateActions(candidates, coursesById, exposureValidatedCandidates);
        List<HydrologyCavePlan> plans = planner.validateAllPlans(
                view,
                candidates,
                validationCache,
                plannedSurface,
                exposureValidatedCandidates,
                (HydrologyCaveCandidate first, HydrologyCaveCandidate second) ->
                        compatibleSharedCandidates(first, second, coursesById)
        );
        plans = alignSharedTerminalPlanActions(plans, candidates, coursesById);
        ArrayList<HydrologyCavePlan> acceptedPlans = new ArrayList<>();
        for (HydrologyCavePlan plan : plans) {
            if (plan.accepted()) {
                acceptedPlans.add(plan);
                continue;
            }
            long courseId = plan.source().sourceId();
            if (rejectedCourseIds.add(courseId)) {
                addDiagnostic(coursesById.get(courseId), plan, diagnostics);
            }
        }
        if (rejectedCourseIds.isEmpty()) {
            Graph graph = acceptedGraph(nodes, edges, outlets, normalizedCourses);
            return new Result(
                    graph.nodes(),
                    graph.edges(),
                    graph.outlets(),
                    normalizedCourses,
                    List.copyOf(acceptedPlans)
            );
        }

        ArrayList<RiverCourse> acceptedCourses = new ArrayList<>();
        for (RiverCourse course : normalizedCourses) {
            if (!rejectedCourseIds.contains(course.id())) {
                acceptedCourses.add(course);
            }
        }
        Graph graph = acceptedGraph(nodes, edges, outlets, acceptedCourses);
        return new Result(
                graph.nodes(),
                graph.edges(),
                graph.outlets(),
                List.copyOf(acceptedCourses),
                List.copyOf(acceptedPlans)
        );
    }

    private boolean compatibleSharedCandidates(
            HydrologyCaveCandidate first,
            HydrologyCaveCandidate second,
            Map<Long, RiverCourse> coursesById
    ) {
        if (!first.profileKey().equals(second.profileKey())) {
            return false;
        }
        RiverCourse firstCourse = coursesById.get(first.source().sourceId());
        RiverCourse secondCourse = coursesById.get(second.source().sourceId());
        return sharesDrainageOutlet(firstCourse, secondCourse)
                || sharesTerminalGrotto(firstCourse, secondCourse, first);
    }

    private List<HydrologyCavePlan> alignSharedTerminalPlanActions(
            List<HydrologyCavePlan> plans,
            List<HydrologyCaveCandidate> candidates,
            Map<Long, RiverCourse> coursesById
    ) {
        HashMap<Long, HydrologyCaveCandidate> candidatesById = new HashMap<>(candidates.size());
        for (HydrologyCaveCandidate candidate : candidates) {
            candidatesById.put(candidate.source().sourceId(), candidate);
        }
        ArrayList<HydrologyCavePlan> aligned = new ArrayList<>(plans);
        for (int firstIndex = 0; firstIndex < aligned.size() - 1; firstIndex++) {
            for (int secondIndex = firstIndex + 1; secondIndex < aligned.size(); secondIndex++) {
                HydrologyCavePlan first = aligned.get(firstIndex);
                HydrologyCavePlan second = aligned.get(secondIndex);
                HydrologyCaveCandidate firstCandidate = candidatesById.get(first.source().sourceId());
                HydrologyCaveCandidate secondCandidate = candidatesById.get(second.source().sourceId());
                if (!first.accepted()
                        || !second.accepted()
                        || firstCandidate == null
                        || secondCandidate == null
                        || !compatibleSharedCandidates(firstCandidate, secondCandidate, coursesById)) {
                    continue;
                }
                LinkedHashMap<CavePosition, HydrologyCaveAction> firstActions = null;
                LinkedHashMap<CavePosition, HydrologyCaveAction> secondActions = null;
                for (Map.Entry<CavePosition, HydrologyCaveAction> entry : first.actions().entrySet()) {
                    HydrologyCaveAction secondAction = second.actions().get(entry.getKey());
                    if (secondAction == null || secondAction == entry.getValue()) {
                        continue;
                    }
                    HydrologyCaveAction sharedAction = sharedTerminalAction(entry.getValue(), secondAction);
                    if (firstActions == null) {
                        firstActions = new LinkedHashMap<>(first.actions());
                        secondActions = new LinkedHashMap<>(second.actions());
                    }
                    firstActions.put(entry.getKey(), sharedAction);
                    secondActions.put(entry.getKey(), sharedAction);
                }
                if (firstActions == null) {
                    continue;
                }
                aligned.set(firstIndex, withActions(first, firstActions));
                aligned.set(secondIndex, withActions(second, secondActions));
            }
        }
        return List.copyOf(aligned);
    }

    private HydrologyCavePlan withActions(
            HydrologyCavePlan plan,
            Map<CavePosition, HydrologyCaveAction> actions
    ) {
        return new HydrologyCavePlan(
                plan.source(),
                plan.rejection(),
                actions,
                plan.baselinePreconditions(),
                plan.arbitrationWinnerSourceId()
        );
    }

    private void alignSharedTerminalCandidateActions(
            List<HydrologyCaveCandidate> candidates,
            Map<Long, RiverCourse> coursesById,
            Set<HydrologyCaveCandidate> exposureValidatedCandidates
    ) {
        for (int firstIndex = 0; firstIndex < candidates.size() - 1; firstIndex++) {
            for (int secondIndex = firstIndex + 1; secondIndex < candidates.size(); secondIndex++) {
                HydrologyCaveCandidate first = candidates.get(firstIndex);
                HydrologyCaveCandidate second = candidates.get(secondIndex);
                RiverCourse firstCourse = coursesById.get(first.source().sourceId());
                RiverCourse secondCourse = coursesById.get(second.source().sourceId());
                if (!sharesDrainageOutlet(firstCourse, secondCourse)
                        && !sharesTerminalGrotto(firstCourse, secondCourse, first)) {
                    continue;
                }
                LinkedHashMap<CavePosition, HydrologyCaveAction> firstActions = null;
                LinkedHashMap<CavePosition, HydrologyCaveAction> secondActions = null;
                for (Map.Entry<CavePosition, HydrologyCaveAction> entry : first.actions().entrySet()) {
                    HydrologyCaveAction secondAction = second.actions().get(entry.getKey());
                    if (secondAction == null || secondAction == entry.getValue()) {
                        continue;
                    }
                    HydrologyCaveAction sharedAction = sharedTerminalAction(entry.getValue(), secondAction);
                    if (firstActions == null) {
                        firstActions = new LinkedHashMap<>(first.actions());
                        secondActions = new LinkedHashMap<>(second.actions());
                    }
                    firstActions.put(entry.getKey(), sharedAction);
                    secondActions.put(entry.getKey(), sharedAction);
                }
                if (firstActions == null) {
                    continue;
                }
                HydrologyCaveCandidate alignedFirst = withActions(first, firstActions);
                HydrologyCaveCandidate alignedSecond = withActions(second, secondActions);
                replaceExposureCandidate(exposureValidatedCandidates, first, alignedFirst);
                replaceExposureCandidate(exposureValidatedCandidates, second, alignedSecond);
                candidates.set(firstIndex, alignedFirst);
                candidates.set(secondIndex, alignedSecond);
            }
        }
    }

    private HydrologyCaveAction sharedTerminalAction(
            HydrologyCaveAction first,
            HydrologyCaveAction second
    ) {
        return sharedTerminalActionPriority(first) <= sharedTerminalActionPriority(second)
                ? first
                : second;
    }

    private int sharedTerminalActionPriority(HydrologyCaveAction action) {
        return switch (action) {
            case FALLING_FLUID -> 0;
            case WET_SOURCE -> 1;
            case DRY_AIR -> 2;
            case SEAL_GUARD -> 3;
        };
    }

    private HydrologyCaveCandidate withActions(
            HydrologyCaveCandidate candidate,
            Map<CavePosition, HydrologyCaveAction> actions
    ) {
        return new HydrologyCaveCandidate(
                candidate.source(),
                candidate.profileKey(),
                candidate.settings(),
                candidate.allowDryCaveConnections(),
                actions,
                candidate.intentionalOpenings()
        );
    }

    private void replaceExposureCandidate(
            Set<HydrologyCaveCandidate> exposureValidatedCandidates,
            HydrologyCaveCandidate existing,
            HydrologyCaveCandidate replacement
    ) {
        if (exposureValidatedCandidates.remove(existing)) {
            exposureValidatedCandidates.add(replacement);
        }
    }

    private SurfaceComposition composeSurfacePublication(
            HydrologyCaveCandidate candidate,
            RiverCourse course,
            HydrologyFootprintCompiler.ValidationRaster validation,
            Map<Long, RiverCourse> publishedCoursesById
    ) {
        LinkedHashMap<CavePosition, HydrologyCaveAction> composedActions = null;
        for (HydrologyColumnSample caveSample : validation.columnsForCourse(course.id())) {
            int maximumCaveY = maximumCaveY(caveSample, course.id());
            if (maximumCaveY == Integer.MIN_VALUE) {
                continue;
            }
            int plannedTerrainHeight = validation.plannedSurface().resolve(
                    caveSample.x(),
                    caveSample.z(),
                    caveSample.naturalHeight()
            );
            if (maximumCaveY <= plannedTerrainHeight) {
                continue;
            }
            HydrologyColumnSample surfaceSample = validation.surfaceColumnAt(
                    caveSample.x(),
                    caveSample.z(),
                    caveSample.naturalHeight()
            );
            if (surfaceSample == null) {
                continue;
            }
            for (int y = plannedTerrainHeight + 1; y <= maximumCaveY; y++) {
                HydrologyColumnSample.SurfacePublicationCell surfaceCell = surfaceSample
                        .surfacePublicationCellAt(y)
                        .orElse(null);
                if (surfaceCell == null) {
                    continue;
                }
                CavePosition position = new CavePosition(caveSample.x(), y, caveSample.z());
                HydrologyCaveAction caveAction = candidate.actions().get(position);
                if (caveAction == null) {
                    continue;
                }
                long surfaceCourseId = surfaceCell.layer().feature().courseId();
                RiverCourse surfaceCourse = publishedCoursesById.get(surfaceCourseId);
                boolean sharedTerminalGrotto = surfaceCourseId != course.id()
                        && sharesTerminalGrotto(course, surfaceCourse, candidate);
                if (!candidate.profileKey().equals(surfaceCell.layer().profileKey())
                        || (surfaceCourseId != course.id()
                        && !candidate.intentionalOpenings().contains(position)
                        && !sharedTerminalGrotto)) {
                    return SurfaceComposition.rejected();
                }
                if (caveAction == surfaceCell.action()) {
                    continue;
                }
                if (surfaceCourseId != course.id()
                        && !sharesDrainageOutlet(course, surfaceCourse)
                        && !sharedTerminalGrotto) {
                    return SurfaceComposition.rejected();
                }
                if (composedActions == null) {
                    composedActions = new LinkedHashMap<>(candidate.actions());
                }
                composedActions.put(position, surfaceCell.action());
            }
        }
        if (composedActions == null) {
            return SurfaceComposition.accepted(candidate);
        }
        return SurfaceComposition.accepted(new HydrologyCaveCandidate(
                candidate.source(),
                candidate.profileKey(),
                candidate.settings(),
                candidate.allowDryCaveConnections(),
                composedActions,
                candidate.intentionalOpenings()
        ));
    }

    private boolean sharesTerminalGrotto(
            RiverCourse first,
            RiverCourse second,
            HydrologyCaveCandidate candidate
    ) {
        if (second == null || !first.profileKey().equals(second.profileKey())) {
            return false;
        }
        HydraulicSegment firstGrotto = terminalGrotto(first);
        HydraulicSegment secondGrotto = terminalGrotto(second);
        if (firstGrotto == null || secondGrotto == null) {
            return false;
        }
        HydrologyPoint firstPoint = firstGrotto.end();
        HydrologyPoint secondPoint = secondGrotto.end();
        int horizontalRadius = Math.addExact(
                candidate.settings().grottoHorizontalRadius(),
                Math.max(1, (int) StrictMath.ceil(secondGrotto.width() / 2D))
        );
        int verticalRadius = Math.addExact(
                candidate.settings().grottoVerticalRadius(),
                Math.max(1, secondGrotto.depth())
        );
        return firstPoint.distanceSquared2D(secondPoint) <= (long) horizontalRadius * horizontalRadius
                && Math.abs(firstPoint.y() - secondPoint.y()) <= verticalRadius;
    }

    private HydraulicSegment terminalGrotto(RiverCourse course) {
        if (course == null || course.segments().isEmpty()) {
            return null;
        }
        HydraulicSegment segment = course.segments().getLast();
        return segment.type().isGrotto() ? segment : null;
    }

    private boolean sharesDrainageOutlet(RiverCourse first, RiverCourse second) {
        return second != null
                && first.outletId().isPresent()
                && second.outletId().isPresent()
                && first.outletId().getAsLong() == second.outletId().getAsLong();
    }

    private int maximumCaveY(HydrologyColumnSample sample, long courseId) {
        int maximumY = Integer.MIN_VALUE;
        for (HydrologyColumnLayer layer : sample.layers()) {
            if (layer.feature().courseId() == courseId
                    && isCaveLayer(layer)
                    && !layer.oceanApron()
                    && layer.channel()
                    && layer.terrainOwned()) {
                maximumY = Math.max(maximumY, layer.ceilingY());
            }
        }
        return maximumY;
    }

    Set<Long> preflightRejectedCourseIds(
            List<RiverCourse> courses,
            Iterable<HydrologyColumnSample> columns,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        LinkedHashMap<Long, CandidateSpanBuilder> builders = candidateSpanBuilders(courses, columns);
        return preflightRejectedCourseIds(builders, diagnostics);
    }

    private Set<Long> preflightRejectedCourseIds(
            List<RiverCourse> courses,
            HydrologyFootprintCompiler.ValidationRaster validation,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        LinkedHashMap<Long, CandidateSpanBuilder> builders = candidateSpanBuilders(courses, validation);
        return preflightRejectedCourseIds(builders, diagnostics);
    }

    private Set<Long> preflightRejectedCourseIds(
            LinkedHashMap<Long, CandidateSpanBuilder> builders,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        if (builders.isEmpty()) {
            return Set.of();
        }
        HashSet<Long> rejectedCourseIds = new HashSet<>();
        for (CandidateSpanBuilder builder : builders.values()) {
            HydrologyFeatureType oversizedGrotto = builder.oversizedGrotto(options);
            boolean oversizedCourse = builder.positionCount()
                    > HydrologyCavePlannerSettings.MAXIMUM_PLANNED_MUTATIONS;
            if (oversizedGrotto == null && !oversizedCourse) {
                continue;
            }
            rejectedCourseIds.add(builder.course().id());
            addDiagnostic(
                    builder.course(),
                    builder.representative(),
                    oversizedGrotto == null ? builder.representative().type() : oversizedGrotto,
                    HydrologyCandidateRejection.VOLUME_LIMIT,
                    diagnostics,
                    HydrologyCandidateRejection.VOLUME_LIMIT.ordinal()
            );
        }
        for (CandidateSpanBuilder builder : builders.values()) {
            if (rejectedCourseIds.contains(builder.course().id())
                    || !builder.exposed(view)
                    || builder.allowsIntentionalSurfaceExposure()) {
                continue;
            }
            rejectedCourseIds.add(builder.course().id());
            addDiagnostic(
                    builder.course(),
                    builder.representative(),
                    builder.representative().type(),
                    HydrologyCandidateRejection.CAVE_CONTAINMENT,
                    diagnostics,
                    HydrologyCaveRejection.OPEN_SURFACE.ordinal()
            );
        }
        return Set.copyOf(rejectedCourseIds);
    }

    private List<RiverCourse> withoutZeroContributionEdges(List<RiverCourse> courses) {
        ArrayList<RiverCourse> normalized = new ArrayList<>(courses.size());
        boolean changed = false;
        for (RiverCourse course : courses) {
            ArrayList<DrainageEdge> contributingEdges = new ArrayList<>(course.drainageEdges().size());
            for (DrainageEdge edge : course.drainageEdges()) {
                if (edge.totalContributingSources() > 0) {
                    contributingEdges.add(edge);
                } else {
                    changed = true;
                }
            }
            if (contributingEdges.size() == course.drainageEdges().size()) {
                normalized.add(course);
                continue;
            }
            normalized.add(new RiverCourse(
                    course.id(),
                    course.type(),
                    course.sourceNodeId(),
                    course.outletId(),
                    course.profileKey(),
                    course.discharge(),
                    contributingEdges,
                    course.segments()
            ));
        }
        return changed ? List.copyOf(normalized) : courses;
    }

    private LinkedHashMap<Long, CandidateSpanBuilder> candidateSpanBuilders(
            List<RiverCourse> courses,
            Iterable<HydrologyColumnSample> columns
    ) {
        LinkedHashMap<Long, CandidateSpanBuilder> builders = new LinkedHashMap<>();
        for (RiverCourse course : courses) {
            HydraulicSegment representative = representativeCaveSegment(course);
            if (representative != null) {
                builders.put(course.id(), new CandidateSpanBuilder(course, representative));
            }
        }
        if (builders.isEmpty()) {
            return builders;
        }

        for (HydrologyColumnSample sample : columns) {
            for (HydrologyColumnLayer layer : sample.layers()) {
                CandidateSpanBuilder builder = builders.get(layer.feature().courseId());
                if (builder == null || !isCaveLayer(layer) || layer.oceanApron()
                        || !layer.channel() || !layer.terrainOwned()) {
                    continue;
                }
                builder.addAction(sample.x(), sample.z(), layer);
            }
            for (HydrologyColumnLayer layer : sample.layers()) {
                CandidateSpanBuilder builder = builders.get(layer.feature().courseId());
                if (builder != null && layer.oceanApron()) {
                    builder.addOceanOpening(sample.x(), sample.z(), sample.naturalHeight() + 1, layer.ceilingY());
                }
            }
        }
        builders.values().removeIf(CandidateSpanBuilder::isEmpty);
        return builders;
    }

    private LinkedHashMap<Long, CandidateSpanBuilder> candidateSpanBuilders(
            List<RiverCourse> courses,
            HydrologyFootprintCompiler.ValidationRaster validation
    ) {
        LinkedHashMap<Long, CandidateSpanBuilder> builders = new LinkedHashMap<>();
        for (RiverCourse course : courses) {
            HydraulicSegment representative = representativeCaveSegment(course);
            if (representative == null) {
                continue;
            }
            CandidateSpanBuilder builder = new CandidateSpanBuilder(course, representative);
            for (HydrologyColumnSample sample : validation.columnsForCourse(course.id())) {
                int maximumCaveY = Integer.MIN_VALUE;
                for (HydrologyColumnLayer layer : sample.layers()) {
                    if (layer.feature().courseId() != course.id()
                            || !isCaveLayer(layer)
                            || layer.oceanApron()
                            || !layer.channel()
                            || !layer.terrainOwned()) {
                        continue;
                    }
                    builder.addAction(sample.x(), sample.z(), layer);
                    maximumCaveY = Math.max(maximumCaveY, layer.ceilingY());
                }
                if (maximumCaveY != Integer.MIN_VALUE
                        && validation.ownsSurfaceChannelAt(
                        sample.x(),
                        sample.z(),
                        sample.naturalHeight(),
                        course.id()
                )) {
                    int plannedTerrainHeight = validation.plannedSurface().resolve(
                            sample.x(),
                            sample.z(),
                            sample.naturalHeight()
                    );
                    builder.addOpeningNeighborhood(
                            sample.x(),
                            sample.z(),
                            plannedTerrainHeight + 1,
                            maximumCaveY
                    );
                }
                for (HydrologyColumnLayer layer : sample.layers()) {
                    if (layer.feature().courseId() == course.id() && layer.oceanApron()) {
                        builder.addOceanOpening(
                                sample.x(),
                                sample.z(),
                                sample.naturalHeight() + 1,
                                layer.ceilingY()
                        );
                    }
                }
            }
            builder.addAdjacentSurfaceOpenings(validation);
            if (!builder.isEmpty()) {
                builders.put(course.id(), builder);
            }
        }
        return builders;
    }

    private LinkedHashMap<Long, CandidateBuilder> candidateBuilders(
            List<RiverCourse> courses,
            Iterable<HydrologyColumnSample> columns
    ) {
        LinkedHashMap<Long, CandidateBuilder> builders = new LinkedHashMap<>();
        for (RiverCourse course : courses) {
            HydraulicSegment representative = representativeCaveSegment(course);
            if (representative != null) {
                builders.put(course.id(), new CandidateBuilder(course, representative));
            }
        }
        if (builders.isEmpty()) {
            return builders;
        }

        for (HydrologyColumnSample sample : columns) {
            for (HydrologyColumnLayer layer : sample.layers()) {
                CandidateBuilder builder = builders.get(layer.feature().courseId());
                if (builder == null || !isCaveLayer(layer) || layer.oceanApron()
                        || !layer.channel() || !layer.terrainOwned()) {
                    continue;
                }
                for (int y = layer.bedY() + 1; y <= layer.ceilingY(); y++) {
                    CavePosition position = new CavePosition(sample.x(), y, sample.z());
                    builder.addAction(position, actionAt(layer, y));
                    builder.addSurfaceOpening(layer, position);
                }
            }
            for (HydrologyColumnLayer layer : sample.layers()) {
                CandidateBuilder builder = builders.get(layer.feature().courseId());
                if (builder == null || !layer.oceanApron()) {
                    continue;
                }
                for (int y = sample.naturalHeight() + 1; y <= layer.ceilingY(); y++) {
                    builder.addOpening(new CavePosition(sample.x(), y, sample.z()));
                }
            }
        }
        builders.values().removeIf(CandidateBuilder::isEmpty);
        return builders;
    }

    private LinkedHashMap<Long, CandidateBuilder> candidateBuilders(
            List<RiverCourse> courses,
            HydrologyFootprintCompiler.ValidationRaster validation
    ) {
        LinkedHashMap<Long, CandidateBuilder> builders = new LinkedHashMap<>();
        for (RiverCourse course : courses) {
            HydraulicSegment representative = representativeCaveSegment(course);
            if (representative == null) {
                continue;
            }
            CandidateBuilder builder = new CandidateBuilder(course, representative);
            for (HydrologyColumnSample sample : validation.columnsForCourse(course.id())) {
                int maximumCaveY = Integer.MIN_VALUE;
                for (HydrologyColumnLayer layer : sample.layers()) {
                    if (layer.feature().courseId() != course.id()
                            || !isCaveLayer(layer)
                            || layer.oceanApron()
                            || !layer.channel()
                            || !layer.terrainOwned()) {
                        continue;
                    }
                    for (int y = layer.bedY() + 1; y <= layer.ceilingY(); y++) {
                        CavePosition position = new CavePosition(sample.x(), y, sample.z());
                        builder.addAction(position, actionAt(layer, y));
                        builder.addSurfaceOpening(layer, position);
                    }
                    maximumCaveY = Math.max(maximumCaveY, layer.ceilingY());
                }
                if (maximumCaveY != Integer.MIN_VALUE
                        && validation.ownsSurfaceChannelAt(
                        sample.x(),
                        sample.z(),
                        sample.naturalHeight(),
                        course.id()
                )) {
                    int plannedTerrainHeight = validation.plannedSurface().resolve(
                            sample.x(),
                            sample.z(),
                            sample.naturalHeight()
                    );
                    for (int y = plannedTerrainHeight + 1; y <= maximumCaveY; y++) {
                        builder.addOpeningNeighborhood(new CavePosition(sample.x(), y, sample.z()));
                    }
                }
                for (HydrologyColumnLayer layer : sample.layers()) {
                    if (layer.feature().courseId() != course.id() || !layer.oceanApron()) {
                        continue;
                    }
                    for (int y = sample.naturalHeight() + 1; y <= layer.ceilingY(); y++) {
                        builder.addOpening(new CavePosition(sample.x(), y, sample.z()));
                    }
                }
            }
            builder.addAdjacentSurfaceOpenings(validation);
            if (!builder.isEmpty()) {
                builders.put(course.id(), builder);
            }
        }
        return builders;
    }

    private static HydraulicSegment representativeCaveSegment(RiverCourse course) {
        HydraulicSegment selected = null;
        for (HydraulicSegment segment : course.segments()) {
            if (!segment.type().isUnderground() && !segment.type().isDeepFluid()) {
                continue;
            }
            if (selected == null || candidatePriority(segment.type()) < candidatePriority(selected.type())) {
                selected = segment;
            }
        }
        return selected;
    }

    private static int candidatePriority(HydrologyFeatureType type) {
        if (type.isGrotto()) {
            return 0;
        }
        if (type.isDeepFluid()) {
            return 1;
        }
        return 2;
    }

    private boolean isCaveLayer(HydrologyColumnLayer layer) {
        return layer.feature().type().isUnderground() || layer.feature().type().isDeepFluid();
    }

    private HydrologyCaveAction actionAt(HydrologyColumnLayer layer, int y) {
        if (y > layer.fluidHeadY()) {
            return HydrologyCaveAction.DRY_AIR;
        }
        if (layer.fallingFluid() && y < layer.fluidHeadY()) {
            return HydrologyCaveAction.FALLING_FLUID;
        }
        return HydrologyCaveAction.WET_SOURCE;
    }

    private void addDiagnostic(
            RiverCourse course,
            HydrologyCavePlan plan,
            List<HydrologyDiagnosticCandidate> diagnostics
    ) {
        HydraulicSegment representative = representativeCaveSegment(course);
        addDiagnostic(
                course,
                representative,
                representative.type(),
                HydrologyCandidateRejection.CAVE_CONTAINMENT,
                diagnostics,
                plan.rejection().ordinal()
        );
    }

    private void addDiagnostic(
            RiverCourse course,
            HydraulicSegment representative,
            HydrologyFeatureType type,
            HydrologyCandidateRejection rejection,
            List<HydrologyDiagnosticCandidate> diagnostics,
            int rejectionSalt
    ) {
        HydrologyCandidateKind kind = course.type() == RiverCourseType.DEEP_FLUID
                ? HydrologyCandidateKind.DEEP_FLUID
                : type.isGrotto() ? HydrologyCandidateKind.OUTLET : HydrologyCandidateKind.SOURCE;
        diagnostics.add(new HydrologyDiagnosticCandidate(
                HydrologyHash.mix(
                        course.id(),
                        DIAGNOSTIC_SALT,
                        rejectionSalt
                ),
                kind,
                type,
                representative.start(),
                rejection
        ));
    }

    private static Graph acceptedGraph(
            List<DrainageNode> nodes,
            List<DrainageEdge> edges,
            List<RiverOutlet> outlets,
            List<RiverCourse> courses
    ) {
        LinkedHashSet<Long> nodeIds = new LinkedHashSet<>();
        LinkedHashSet<Long> edgeIds = new LinkedHashSet<>();
        LinkedHashSet<Long> outletIds = new LinkedHashSet<>();
        for (RiverCourse course : courses) {
            if (course.sourceNodeId().isPresent()) {
                nodeIds.add(course.sourceNodeId().getAsLong());
            }
            if (course.outletId().isPresent()) {
                outletIds.add(course.outletId().getAsLong());
            }
            for (DrainageEdge edge : course.drainageEdges()) {
                if (edge.totalContributingSources() == 0) {
                    continue;
                }
                edgeIds.add(edge.id());
                nodeIds.add(edge.upstreamNodeId());
                nodeIds.add(edge.downstreamNodeId());
                outletIds.add(edge.outletId());
            }
        }
        ArrayList<DrainageNode> acceptedNodes = new ArrayList<>();
        for (DrainageNode node : nodes) {
            if (nodeIds.contains(node.id())) {
                acceptedNodes.add(node);
                outletIds.add(node.outletId());
            }
        }
        ArrayList<DrainageEdge> acceptedEdges = new ArrayList<>();
        for (DrainageEdge edge : edges) {
            if (edgeIds.contains(edge.id()) && edge.totalContributingSources() > 0) {
                acceptedEdges.add(edge);
            }
        }
        ArrayList<RiverOutlet> acceptedOutlets = new ArrayList<>();
        for (RiverOutlet outlet : outlets) {
            if (outletIds.contains(outlet.id())) {
                acceptedOutlets.add(outlet);
            }
        }
        return new Graph(
                List.copyOf(acceptedNodes),
                List.copyOf(acceptedEdges),
                List.copyOf(acceptedOutlets)
        );
    }

    static Result withoutCourses(Result result, Set<Long> rejectedCourseIds) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(rejectedCourseIds);
        if (rejectedCourseIds.isEmpty()) {
            return result;
        }
        ArrayList<RiverCourse> acceptedCourses = new ArrayList<>(result.courses().size());
        for (RiverCourse course : result.courses()) {
            if (!rejectedCourseIds.contains(course.id())) {
                acceptedCourses.add(course);
            }
        }
        ArrayList<HydrologyCavePlan> acceptedPlans = new ArrayList<>(result.cavePlans().size());
        for (HydrologyCavePlan plan : result.cavePlans()) {
            if (!rejectedCourseIds.contains(plan.source().sourceId())) {
                acceptedPlans.add(plan);
            }
        }
        Graph graph = acceptedGraph(
                result.nodes(),
                result.edges(),
                result.outlets(),
                acceptedCourses
        );
        return new Result(
                graph.nodes(),
                graph.edges(),
                graph.outlets(),
                List.copyOf(acceptedCourses),
                List.copyOf(acceptedPlans)
        );
    }

    static HydrologyDiagnosticCandidate overlapDiagnostic(RiverCourse course, long winnerSourceId) {
        Objects.requireNonNull(course);
        HydraulicSegment representative = representativeCaveSegment(course);
        if (representative == null) {
            throw new IllegalArgumentException("Cross-tile cave arbitration requires a cave-bearing course.");
        }
        HydrologyFeatureType type = representative.type();
        HydrologyCandidateKind kind = course.type() == RiverCourseType.DEEP_FLUID
                ? HydrologyCandidateKind.DEEP_FLUID
                : type.isGrotto() ? HydrologyCandidateKind.OUTLET : HydrologyCandidateKind.SOURCE;
        return new HydrologyDiagnosticCandidate(
                HydrologyHash.mix(
                        course.id(),
                        DIAGNOSTIC_SALT,
                        HydrologyCaveRejection.OVERLAPPING_SOURCE.ordinal(),
                        winnerSourceId
                ),
                kind,
                type,
                representative.start(),
                HydrologyCandidateRejection.CAVE_CONTAINMENT
        );
    }

    record Result(
            List<DrainageNode> nodes,
            List<DrainageEdge> edges,
            List<RiverOutlet> outlets,
            List<RiverCourse> courses,
            List<HydrologyCavePlan> cavePlans
    ) {
    }

    private record Graph(
            List<DrainageNode> nodes,
            List<DrainageEdge> edges,
            List<RiverOutlet> outlets
    ) {
    }

    private record SurfaceComposition(
            HydrologyCaveCandidate candidate,
            boolean accepted
    ) {
        private static SurfaceComposition accepted(HydrologyCaveCandidate candidate) {
            return new SurfaceComposition(Objects.requireNonNull(candidate), true);
        }

        private static SurfaceComposition rejected() {
            return new SurfaceComposition(null, false);
        }
    }

    record Options(
            boolean connectToExistingCaves,
            int coastalGrottoMaximumVolume,
            int inlandGrottoMaximumVolume
    ) {
        Options {
            if (coastalGrottoMaximumVolume < 1 || inlandGrottoMaximumVolume < 1) {
                throw new IllegalArgumentException("Grotto maximum volumes must be positive.");
            }
        }

        private int maximumVolume(HydrologyFeatureType type) {
            return switch (type) {
                case COASTAL_GROTTO -> coastalGrottoMaximumVolume;
                case INLAND_GROTTO -> inlandGrottoMaximumVolume;
                default -> Integer.MAX_VALUE;
            };
        }
    }

    record CandidateKey(
            long courseId,
            RiverCourseType courseType,
            String profileKey,
            List<HydraulicSegment> segments,
            Options options
    ) {
        CandidateKey {
            Objects.requireNonNull(courseType, "courseType");
            if (profileKey == null || profileKey.isBlank()) {
                throw new IllegalArgumentException("profileKey must not be blank");
            }
            profileKey = profileKey.trim();
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
            Objects.requireNonNull(options, "options");
        }

        private static CandidateKey create(RiverCourse course, Options options) {
            return new CandidateKey(
                    course.id(),
                    course.type(),
                    course.profileKey(),
                    course.segments(),
                    options
            );
        }
    }

    static final class CandidateCache extends LinkedHashMap<CandidateKey, HydrologyCaveCandidate> {
        private static final int DEFAULT_MAXIMUM_ENTRIES = 256;
        private static final long DEFAULT_MAXIMUM_RETAINED_POSITIONS = 262_144L;

        private final int maximumEntries;
        private final long maximumRetainedPositions;
        private long retainedPositions;

        CandidateCache() {
            this(DEFAULT_MAXIMUM_ENTRIES, DEFAULT_MAXIMUM_RETAINED_POSITIONS);
        }

        CandidateCache(int maximumEntries, long maximumRetainedPositions) {
            super(16, 0.75F, true);
            if (maximumEntries < 1) {
                throw new IllegalArgumentException("Maximum candidate cache entries must be positive.");
            }
            if (maximumRetainedPositions < 1L) {
                throw new IllegalArgumentException("Maximum retained candidate positions must be positive.");
            }
            this.maximumEntries = maximumEntries;
            this.maximumRetainedPositions = maximumRetainedPositions;
        }

        @Override
        public HydrologyCaveCandidate put(CandidateKey key, HydrologyCaveCandidate candidate) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(candidate);
            HydrologyCaveCandidate previous = super.remove(key);
            if (previous != null) {
                retainedPositions -= weight(previous);
            }
            long weight = weight(candidate);
            if (weight > maximumRetainedPositions) {
                return previous;
            }
            super.put(key, candidate);
            retainedPositions += weight;
            trim();
            return previous;
        }

        @Override
        public HydrologyCaveCandidate remove(Object key) {
            HydrologyCaveCandidate removed = super.remove(key);
            if (removed != null) {
                retainedPositions -= weight(removed);
            }
            return removed;
        }

        @Override
        public void putAll(Map<? extends CandidateKey, ? extends HydrologyCaveCandidate> candidates) {
            for (Map.Entry<? extends CandidateKey, ? extends HydrologyCaveCandidate> entry
                    : candidates.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void clear() {
            super.clear();
            retainedPositions = 0L;
        }

        long retainedPositions() {
            return retainedPositions;
        }

        private void trim() {
            Iterator<Map.Entry<CandidateKey, HydrologyCaveCandidate>> iterator = entrySet().iterator();
            while (iterator.hasNext()
                    && (size() > maximumEntries || retainedPositions > maximumRetainedPositions)) {
                Map.Entry<CandidateKey, HydrologyCaveCandidate> eldest = iterator.next();
                retainedPositions -= weight(eldest.getValue());
                iterator.remove();
            }
        }

        private static long weight(HydrologyCaveCandidate candidate) {
            return Math.max(1L, (long) candidate.actions().size() + candidate.intentionalOpenings().size());
        }
    }

    private static final class CandidateSpanBuilder {
        private final RiverCourse course;
        private final HydraulicSegment representative;
        private final LinkedHashMap<Long, SpanSet> actions;
        private final LinkedHashMap<Long, SpanSet> openings;
        private final LinkedHashMap<Long, SpanSet> coastalGrottoActions;
        private final LinkedHashMap<Long, SpanSet> inlandGrottoActions;
        private final List<SurfaceOpening> surfaceOpenings;
        private int minimumX;
        private int maximumX;
        private int minimumY;
        private int maximumY;
        private int minimumZ;
        private int maximumZ;

        private CandidateSpanBuilder(RiverCourse course, HydraulicSegment representative) {
            this.course = course;
            this.representative = representative;
            this.actions = new LinkedHashMap<>();
            this.openings = new LinkedHashMap<>();
            this.coastalGrottoActions = new LinkedHashMap<>();
            this.inlandGrottoActions = new LinkedHashMap<>();
            this.surfaceOpenings = surfaceOpenings(course);
            this.minimumX = Integer.MAX_VALUE;
            this.maximumX = Integer.MIN_VALUE;
            this.minimumY = Integer.MAX_VALUE;
            this.maximumY = Integer.MIN_VALUE;
            this.minimumZ = Integer.MAX_VALUE;
            this.maximumZ = Integer.MIN_VALUE;
        }

        private RiverCourse course() {
            return course;
        }

        private HydraulicSegment representative() {
            return representative;
        }

        private boolean isEmpty() {
            return actions.isEmpty();
        }

        private boolean allowsIntentionalSurfaceExposure() {
            return course.surfaceSinkholeContinuation();
        }

        private void addAction(int x, int z, HydrologyColumnLayer layer) {
            int minimumActionY = layer.bedY() + 1;
            int maximumActionY = layer.ceilingY();
            if (minimumActionY > maximumActionY) {
                return;
            }
            addSpan(actions, x, z, minimumActionY, maximumActionY);
            if (layer.feature().type() == HydrologyFeatureType.COASTAL_GROTTO) {
                addSpan(coastalGrottoActions, x, z, minimumActionY, maximumActionY);
            } else if (layer.feature().type() == HydrologyFeatureType.INLAND_GROTTO) {
                addSpan(inlandGrottoActions, x, z, minimumActionY, maximumActionY);
            }
            include(x, minimumActionY, z);
            include(x, maximumActionY, z);
            for (SurfaceOpening opening : surfaceOpenings) {
                if (!opening.matchesColumn(layer, x, z)) {
                    continue;
                }
                int minimumOpeningY = Math.max(minimumActionY, opening.minimumY());
                if (minimumOpeningY > maximumActionY) {
                    continue;
                }
                if (!opening.includeNeighborhood()) {
                    addOpening(x, z, minimumOpeningY, maximumActionY);
                    continue;
                }
                addOpening(x, z, minimumOpeningY - 1, maximumActionY + 1);
                for (int[] offset : HORIZONTAL_NEIGHBORS) {
                    addOpening(x + offset[0], z + offset[1], minimumOpeningY, maximumActionY);
                }
            }
        }

        private void addOceanOpening(int x, int z, int minimumOpeningY, int maximumOpeningY) {
            if (minimumOpeningY <= maximumOpeningY) {
                addOpening(x, z, minimumOpeningY, maximumOpeningY);
            }
        }

        private void addOpeningNeighborhood(
                int x,
                int z,
                int minimumOpeningY,
                int maximumOpeningY
        ) {
            if (minimumOpeningY > maximumOpeningY) {
                return;
            }
            addOpening(x, z, minimumOpeningY - 1, maximumOpeningY + 1);
            for (int[] offset : HORIZONTAL_NEIGHBORS) {
                addOpening(x + offset[0], z + offset[1], minimumOpeningY, maximumOpeningY);
            }
        }

        private void addAdjacentSurfaceOpenings(
                HydrologyFootprintCompiler.ValidationRaster validation
        ) {
            for (Map.Entry<Long, SpanSet> entry : actions.entrySet()) {
                int x = RiverFootprint.unpackX(entry.getKey());
                int z = RiverFootprint.unpackZ(entry.getKey());
                for (int[] offset : HORIZONTAL_NEIGHBORS) {
                    int neighborX = x + offset[0];
                    int neighborZ = z + offset[1];
                    if (!validation.ownsSurfaceChannelAt(neighborX, neighborZ, course.id())) {
                        continue;
                    }
                    for (YSpan span : entry.getValue().spans()) {
                        addOpening(neighborX, neighborZ, span.minimumY(), span.maximumY());
                    }
                }
            }
        }

        private HydrologyFeatureType oversizedGrotto(Options options) {
            if (positionCount(coastalGrottoActions)
                    > options.maximumVolume(HydrologyFeatureType.COASTAL_GROTTO)) {
                return HydrologyFeatureType.COASTAL_GROTTO;
            }
            if (positionCount(inlandGrottoActions)
                    > options.maximumVolume(HydrologyFeatureType.INLAND_GROTTO)) {
                return HydrologyFeatureType.INLAND_GROTTO;
            }
            return null;
        }

        private long positionCount() {
            return positionCount(actions);
        }

        private boolean exposed(CaveVoxelView view) {
            CandidateBounds bounds = bounds();
            for (Map.Entry<Long, SpanSet> entry : actions.entrySet()) {
                int x = RiverFootprint.unpackX(entry.getKey());
                int z = RiverFootprint.unpackZ(entry.getKey());
                SpanSet actionSpans = entry.getValue();
                SpanSet openingSpans = openings.get(entry.getKey());
                if (exposedDifference(actionSpans, null, openingSpans, x, z, bounds, view)) {
                    return true;
                }
                for (YSpan span : actionSpans.spans()) {
                    int lowerBoundary = span.minimumY() - 1;
                    if (!actionSpans.contains(lowerBoundary)
                            && !contains(openingSpans, lowerBoundary)
                            && exposed(x, lowerBoundary, z, bounds, view)) {
                        return true;
                    }
                    int upperBoundary = span.maximumY() + 1;
                    if (!actionSpans.contains(upperBoundary)
                            && !contains(openingSpans, upperBoundary)
                            && exposed(x, upperBoundary, z, bounds, view)) {
                        return true;
                    }
                }
                for (int[] offset : HORIZONTAL_NEIGHBORS) {
                    int neighborX = x + offset[0];
                    int neighborZ = z + offset[1];
                    long neighborKey = RiverFootprint.pack(neighborX, neighborZ);
                    if (exposedDifference(
                            actionSpans,
                            actions.get(neighborKey),
                            openings.get(neighborKey),
                            neighborX,
                            neighborZ,
                            bounds,
                            view
                    )) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean exposedDifference(
                SpanSet source,
                SpanSet excludedActions,
                SpanSet excludedOpenings,
                int x,
                int z,
                CandidateBounds bounds,
                CaveVoxelView view
        ) {
            for (YSpan span : source.spans()) {
                if (exposedDifference(
                        span,
                        excludedActions,
                        excludedOpenings,
                        x,
                        z,
                        bounds,
                        view
                )) {
                    return true;
                }
            }
            return false;
        }

        private boolean exposedDifference(
                YSpan source,
                SpanSet excludedActions,
                SpanSet excludedOpenings,
                int x,
                int z,
                CandidateBounds bounds,
                CaveVoxelView view
        ) {
            List<YSpan> actionSpans = excludedActions == null ? List.of() : excludedActions.spans();
            List<YSpan> openingSpans = excludedOpenings == null ? List.of() : excludedOpenings.spans();
            int actionIndex = 0;
            int openingIndex = 0;
            long cursor = source.minimumY();
            while (cursor <= source.maximumY()) {
                while (actionIndex < actionSpans.size()
                        && actionSpans.get(actionIndex).maximumY() < cursor) {
                    actionIndex++;
                }
                while (openingIndex < openingSpans.size()
                        && openingSpans.get(openingIndex).maximumY() < cursor) {
                    openingIndex++;
                }
                YSpan action = actionIndex < actionSpans.size() ? actionSpans.get(actionIndex) : null;
                YSpan opening = openingIndex < openingSpans.size() ? openingSpans.get(openingIndex) : null;
                YSpan excluded = first(action, opening);
                if (excluded == null || excluded.minimumY() > source.maximumY()) {
                    return exposed(x, (int) cursor, source.maximumY(), z, bounds, view);
                }
                if (cursor < excluded.minimumY()
                        && exposed(x, (int) cursor, excluded.minimumY() - 1, z, bounds, view)) {
                    return true;
                }
                cursor = Math.max(cursor, (long) excluded.maximumY() + 1L);
                if (excluded == action) {
                    actionIndex++;
                } else {
                    openingIndex++;
                }
            }
            return false;
        }

        private YSpan first(YSpan first, YSpan second) {
            if (first == null) {
                return second;
            }
            if (second == null) {
                return first;
            }
            return first.minimumY() <= second.minimumY() ? first : second;
        }

        private boolean exposed(
                int x,
                int minimumY,
                int maximumY,
                int z,
                CandidateBounds bounds,
                CaveVoxelView view
        ) {
            if (!bounds.containsColumn(x, z)) {
                return false;
            }
            int boundedMinimumY = Math.max(minimumY, bounds.minimumY());
            int boundedMaximumY = Math.min(maximumY, bounds.maximumY());
            return boundedMinimumY <= boundedMaximumY
                    && view.hasAboveTerrainSurface(x, z, boundedMinimumY, boundedMaximumY);
        }

        private boolean exposed(
                int x,
                int y,
                int z,
                CandidateBounds bounds,
                CaveVoxelView view
        ) {
            CavePosition position = new CavePosition(x, y, z);
            return bounds.contains(position)
                    && view.isInWorld(position)
                    && view.isAboveTerrainSurface(position);
        }

        private CandidateBounds bounds() {
            HydrologyPoint start = representative.start();
            int waterHead = representative.upstreamHeadY();
            int entryY = Math.max(waterHead, maximumY);
            int horizontalRadius = horizontalRadius(
                    start.x(),
                    start.z(),
                    minimumX,
                    maximumX,
                    minimumZ,
                    maximumZ
            );
            int maximumDepth = Math.max(1, entryY - minimumY + 2);
            int dryHeadroom = Math.max(0, maximumY - waterHead);
            return new CandidateBounds(
                    start.x(),
                    entryY,
                    start.z(),
                    waterHead,
                    horizontalRadius,
                    maximumDepth,
                    dryHeadroom
            );
        }

        private void addOpening(int x, int z, int minimumOpeningY, int maximumOpeningY) {
            addSpan(openings, x, z, minimumOpeningY, maximumOpeningY);
            include(x, minimumOpeningY, z);
            include(x, maximumOpeningY, z);
        }

        private void include(int x, int y, int z) {
            minimumX = Math.min(minimumX, x);
            maximumX = Math.max(maximumX, x);
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
            minimumZ = Math.min(minimumZ, z);
            maximumZ = Math.max(maximumZ, z);
        }

        private static void addSpan(
                Map<Long, SpanSet> spans,
                int x,
                int z,
                int minimumY,
                int maximumY
        ) {
            spans.computeIfAbsent(RiverFootprint.pack(x, z), (Long ignored) -> new SpanSet())
                    .add(minimumY, maximumY);
        }

        private static boolean contains(SpanSet spans, int y) {
            return spans != null && spans.contains(y);
        }

        private static long positionCount(Map<Long, SpanSet> spans) {
            long count = 0L;
            for (SpanSet spanSet : spans.values()) {
                count += spanSet.positionCount();
            }
            return count;
        }
    }

    private static final class SpanSet {
        private int[] pending;
        private int size;
        private List<YSpan> normalized;

        private SpanSet() {
            this.pending = new int[4];
            this.normalized = null;
        }

        private void add(int minimumY, int maximumY) {
            if (minimumY > maximumY) {
                return;
            }
            int requiredLength = Math.multiplyExact(size + 1, 2);
            if (requiredLength > pending.length) {
                pending = Arrays.copyOf(pending, Math.multiplyExact(pending.length, 2));
            }
            pending[size * 2] = minimumY;
            pending[size * 2 + 1] = maximumY;
            size++;
            normalized = null;
        }

        private List<YSpan> spans() {
            if (normalized != null) {
                return normalized;
            }
            sortPending();
            ArrayList<YSpan> merged = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                YSpan span = new YSpan(pending[index * 2], pending[index * 2 + 1]);
                if (merged.isEmpty()) {
                    merged.add(span);
                    continue;
                }
                YSpan previous = merged.getLast();
                if ((long) span.minimumY() > (long) previous.maximumY() + 1L) {
                    merged.add(span);
                    continue;
                }
                merged.set(
                        merged.size() - 1,
                        new YSpan(previous.minimumY(), Math.max(previous.maximumY(), span.maximumY()))
                );
            }
            normalized = List.copyOf(merged);
            return normalized;
        }

        private void sortPending() {
            for (int index = 1; index < size; index++) {
                int minimumY = pending[index * 2];
                int maximumY = pending[index * 2 + 1];
                int insertionIndex = index;
                while (insertionIndex > 0) {
                    int previousMinimumY = pending[(insertionIndex - 1) * 2];
                    int previousMaximumY = pending[(insertionIndex - 1) * 2 + 1];
                    if (previousMinimumY < minimumY
                            || previousMinimumY == minimumY && previousMaximumY <= maximumY) {
                        break;
                    }
                    pending[insertionIndex * 2] = previousMinimumY;
                    pending[insertionIndex * 2 + 1] = previousMaximumY;
                    insertionIndex--;
                }
                pending[insertionIndex * 2] = minimumY;
                pending[insertionIndex * 2 + 1] = maximumY;
            }
        }

        private boolean contains(int y) {
            List<YSpan> spans = spans();
            int minimumIndex = 0;
            int maximumIndex = spans.size() - 1;
            while (minimumIndex <= maximumIndex) {
                int index = (minimumIndex + maximumIndex) >>> 1;
                YSpan span = spans.get(index);
                if (y < span.minimumY()) {
                    maximumIndex = index - 1;
                } else if (y > span.maximumY()) {
                    minimumIndex = index + 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        private long positionCount() {
            long count = 0L;
            for (YSpan span : spans()) {
                count += (long) span.maximumY() - span.minimumY() + 1L;
            }
            return count;
        }
    }

    private record YSpan(int minimumY, int maximumY) {
    }

    private record CandidateBounds(
            int entryX,
            int entryY,
            int entryZ,
            int waterHeadY,
            int horizontalRadius,
            int maximumDepth,
            int dryHeadroom
    ) {
        private boolean containsColumn(int x, int z) {
            long deltaX = (long) x - entryX;
            long deltaZ = (long) z - entryZ;
            long radiusSquared = (long) horizontalRadius * horizontalRadius;
            return deltaX * deltaX + deltaZ * deltaZ <= radiusSquared;
        }

        private int minimumY() {
            return Math.toIntExact((long) entryY - maximumDepth);
        }

        private int maximumY() {
            return Math.max(entryY, Math.addExact(waterHeadY, dryHeadroom + 1));
        }

        private boolean contains(CavePosition position) {
            if (!containsColumn(position.x(), position.z())) {
                return false;
            }
            return position.y() >= minimumY() && position.y() <= maximumY();
        }
    }

    private static int horizontalRadius(
            int entryX,
            int entryZ,
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ
    ) {
        long maximumDistanceSquared = 1L;
        long[][] corners = {
                {minimumX, minimumZ},
                {minimumX, maximumZ},
                {maximumX, minimumZ},
                {maximumX, maximumZ}
        };
        for (long[] corner : corners) {
            long deltaX = corner[0] - entryX;
            long deltaZ = corner[1] - entryZ;
            maximumDistanceSquared = Math.max(maximumDistanceSquared, deltaX * deltaX + deltaZ * deltaZ);
        }
        return Math.max(1, (int) StrictMath.ceil(StrictMath.sqrt(maximumDistanceSquared)) + 2);
    }

    private static List<SurfaceOpening> surfaceOpenings(RiverCourse course) {
        if (course.type() != RiverCourseType.SURFACE) {
            return List.of();
        }
        ArrayList<SurfaceOpening> openings = new ArrayList<>();
        List<HydraulicSegment> segments = course.segments();
        for (int index = 0; index < segments.size(); index++) {
            HydraulicSegment segment = segments.get(index);
            if (!segment.type().isUnderground()) {
                continue;
            }
            int radius = Math.max(1, segment.width() / 2) + 1;
            if (segment.type() == HydrologyFeatureType.SINKHOLE) {
                HydrologyPoint start = segment.start();
                double maximumDistance = 0D;
                for (HydrologyPoint point : segment.centerline()) {
                    maximumDistance = Math.max(
                            maximumDistance,
                            StrictMath.hypot(point.x() - start.x(), point.z() - start.z())
                    );
                }
                radius += (int) StrictMath.ceil(maximumDistance);
            }
            long radiusSquared = (long) radius * radius;
            int minimumY = segment.type() == HydrologyFeatureType.SINKHOLE
                    ? segment.downstreamHeadY() + 1
                    : Integer.MIN_VALUE;
            boolean includeNeighborhood = segment.type() != HydrologyFeatureType.SINKHOLE;
            if (index == 0
                    || !segments.get(index - 1).type().isUnderground()) {
                openings.add(SurfaceOpening.create(
                        segment,
                        true,
                        radiusSquared,
                        minimumY,
                        includeNeighborhood
                ));
            }
            boolean terminalCoastalOpening = index == segments.size() - 1
                    && segment.type() == HydrologyFeatureType.COASTAL_GROTTO;
            if (terminalCoastalOpening
                    || (index < segments.size() - 1
                    && !segments.get(index + 1).type().isUnderground())) {
                openings.add(SurfaceOpening.create(
                        segment,
                        false,
                        radiusSquared,
                        minimumY,
                        includeNeighborhood
                ));
            }
        }
        return List.copyOf(openings);
    }

    private static final class CandidateBuilder {
        private final RiverCourse course;
        private final HydraulicSegment representative;
        private final LinkedHashMap<CavePosition, HydrologyCaveAction> actions;
        private final LinkedHashSet<CavePosition> openings;
        private final CavePositionIndex openingIndex;
        private final List<SurfaceOpening> surfaceOpenings;
        private int minimumX;
        private int maximumX;
        private int minimumY;
        private int maximumY;
        private int minimumZ;
        private int maximumZ;

        private CandidateBuilder(RiverCourse course, HydraulicSegment representative) {
            this.course = course;
            this.representative = representative;
            this.actions = new LinkedHashMap<>();
            this.openings = new LinkedHashSet<>();
            this.openingIndex = new CavePositionIndex();
            this.surfaceOpenings = surfaceOpenings(course);
            this.minimumX = Integer.MAX_VALUE;
            this.maximumX = Integer.MIN_VALUE;
            this.minimumY = Integer.MAX_VALUE;
            this.maximumY = Integer.MIN_VALUE;
            this.minimumZ = Integer.MAX_VALUE;
            this.maximumZ = Integer.MIN_VALUE;
        }

        private RiverCourse course() {
            return course;
        }

        private HydraulicSegment representative() {
            return representative;
        }

        private boolean isEmpty() {
            return actions.isEmpty();
        }

        private void addAction(CavePosition position, HydrologyCaveAction action) {
            HydrologyCaveAction existing = actions.get(position);
            if (existing == null || actionPriority(action) < actionPriority(existing)) {
                actions.put(position, action);
            }
            include(position);
        }

        private void addOpening(CavePosition position) {
            if (openingIndex.add(position.x(), position.y(), position.z())) {
                openings.add(position);
            }
            include(position);
        }

        private void addOpening(int x, int y, int z) {
            if (openingIndex.add(x, y, z)) {
                openings.add(new CavePosition(x, y, z));
            }
            include(x, y, z);
        }

        private void addOpeningNeighborhood(CavePosition position) {
            addOpening(position);
            for (int[] offset : NEIGHBORS) {
                addOpening(
                        position.x() + offset[0],
                        position.y() + offset[1],
                        position.z() + offset[2]
                );
            }
        }

        private void addAdjacentSurfaceOpenings(
                HydrologyFootprintCompiler.ValidationRaster validation
        ) {
            ArrayList<CavePosition> actionPositions = new ArrayList<>(actions.keySet());
            for (CavePosition position : actionPositions) {
                for (int[] offset : HORIZONTAL_NEIGHBORS) {
                    int neighborX = position.x() + offset[0];
                    int neighborZ = position.z() + offset[1];
                    if (validation.ownsSurfaceChannelAt(neighborX, neighborZ, course.id())) {
                        addOpening(neighborX, position.y(), neighborZ);
                    }
                }
            }
        }

        private void addSurfaceOpening(HydrologyColumnLayer layer, CavePosition position) {
            for (SurfaceOpening opening : surfaceOpenings) {
                if (!opening.matches(layer, position)) {
                    continue;
                }
                if (opening.includeNeighborhood()) {
                    addOpeningNeighborhood(position);
                } else {
                    addOpening(position);
                }
            }
        }

        private void include(CavePosition position) {
            include(position.x(), position.y(), position.z());
        }

        private void include(int x, int y, int z) {
            minimumX = Math.min(minimumX, x);
            maximumX = Math.max(maximumX, x);
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
            minimumZ = Math.min(minimumZ, z);
            maximumZ = Math.max(maximumZ, z);
        }

        private HydrologyCaveCandidate build(Options options) {
            HydrologyPoint start = representative.start();
            int waterHead = representative.upstreamHeadY();
            int entryY = Math.max(waterHead, maximumY);
            CavePosition entry = new CavePosition(start.x(), entryY, start.z());
            CavePosition target = new CavePosition(start.x(), waterHead, start.z());
            int horizontalRadius = horizontalRadius(
                    entry.x(),
                    entry.z(),
                    minimumX,
                    maximumX,
                    minimumZ,
                    maximumZ
            );
            int maximumDepth = Math.max(1, entryY - minimumY + 2);
            int dryHeadroom = Math.max(0, maximumY - waterHead);
            int volume = Math.max(1, actions.size());
            HydrologyCavePlannerSettings settings = new HydrologyCavePlannerSettings(
                    horizontalRadius,
                    maximumDepth,
                    volume,
                    1,
                    1,
                    1,
                    1,
                    dryHeadroom,
                    HydrologyCaveFluidPolicy.REJECT_EXISTING,
                    HydrologyCaveGrottoShape.ELLIPSOID,
                    horizontalRadius,
                    maximumDepth
            );
            return new HydrologyCaveCandidate(
                    new HydrologyCaveSource(
                            course.id(),
                            entry,
                            target,
                            waterHead,
                            HydrologyCaveMode.GENERATED_GROTTO
                    ),
                    course.profileKey(),
                    settings,
                    options.connectToExistingCaves() && course.type() == RiverCourseType.UNDERGROUND,
                    actions,
                    openings
            );
        }

        private static int actionPriority(HydrologyCaveAction action) {
            return switch (action) {
                case WET_SOURCE -> 0;
                case FALLING_FLUID -> 1;
                case DRY_AIR -> 2;
                case SEAL_GUARD -> 3;
            };
        }

    }

    private record SurfaceOpening(
            long segmentId,
            HydrologyPoint point,
            double interiorX,
            double interiorZ,
            long radiusSquared,
            int minimumY,
            boolean includeNeighborhood
    ) {
        private static SurfaceOpening create(
                HydraulicSegment segment,
                boolean start,
                long radiusSquared,
                int minimumY,
                boolean includeNeighborhood
        ) {
            List<HydrologyPoint> centerline = segment.centerline();
            int boundaryIndex = start ? 0 : centerline.size() - 1;
            int interiorIndex = start
                    ? Math.min(1, centerline.size() - 1)
                    : Math.max(0, centerline.size() - 2);
            HydrologyPoint point = centerline.get(boundaryIndex);
            HydrologyPoint interior = centerline.get(interiorIndex);
            double deltaX = interior.x() - point.x();
            double deltaZ = interior.z() - point.z();
            double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
            double scale = 0D;
            if (includeNeighborhood) {
                scale = lengthSquared <= radiusSquared
                        ? 1D
                        : StrictMath.sqrt(radiusSquared / lengthSquared);
            }
            return new SurfaceOpening(
                    segment.id(),
                    point,
                    point.x() + deltaX * scale,
                    point.z() + deltaZ * scale,
                    radiusSquared,
                    minimumY,
                    includeNeighborhood
            );
        }

        private boolean matches(HydrologyColumnLayer layer, CavePosition position) {
            return matchesColumn(layer, position.x(), position.z())
                    && position.y() >= minimumY;
        }

        private boolean matchesColumn(HydrologyColumnLayer layer, int x, int z) {
            return (includeNeighborhood || layer.feature().segmentId() == segmentId)
                    && distanceSquared(x, z) <= radiusSquared;
        }

        private double distanceSquared(int x, int z) {
            double pathX = interiorX - point.x();
            double pathZ = interiorZ - point.z();
            double pathLengthSquared = pathX * pathX + pathZ * pathZ;
            double positionX = x - point.x();
            double positionZ = z - point.z();
            double progress = pathLengthSquared == 0D
                    ? 0D
                    : (positionX * pathX + positionZ * pathZ) / pathLengthSquared;
            double clampedProgress = Math.max(0D, Math.min(1D, progress));
            double deltaX = positionX - pathX * clampedProgress;
            double deltaZ = positionZ - pathZ * clampedProgress;
            return deltaX * deltaX + deltaZ * deltaZ;
        }
    }
}
