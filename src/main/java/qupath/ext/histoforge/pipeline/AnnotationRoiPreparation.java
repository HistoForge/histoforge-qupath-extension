package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;

/** Saves native annotation locks before source preparation, then captures the saved image. */
final class AnnotationRoiPreparation {
    private AnnotationRoiPreparation() {}

    record Prepared(ImageLifecycle.WorkingImage working, ImageExecutionPlan plan)
            implements AutoCloseable {
        @Override
        public void close() {
            working.close();
        }
    }

    static Prepared open(
            ImageLifecycle lifecycle,
            ImageActionExecutor.Request request,
            BooleanSupplier shouldStop)
            throws IOException, ImageLifecycle.RevisionConflictException {
        ImageLifecycle.WorkingImage working =
                lifecycle.openWorkingImage(
                        request.entry(), request.entry().getID(), request.clientBuild().limits());
        try {
            var action = request.resolveAction(working.imageData().getHierarchy());
            ImageExecutionPlan plan = plan(request, action, working);
            if (request.annotationClass().isPresent()
                    && !(action instanceof ActionSelectionCodec.Metrics)) {
                var hierarchy = working.imageData().getHierarchy();
                RoiRegistry rois = RoiRegistry.forTargets(hierarchy, plan.selectionRegions());
                List<PathObject> unlocked =
                        plan.targetRegions().stream()
                                .map(target -> ((ExecutionScopeCodec.Roi) target.key()).roiId())
                                .map(id -> rois.findRoi(id).orElseThrow())
                                .filter(object -> !object.isLocked())
                                .toList();
                if (!unlocked.isEmpty()) {
                    requireRunning(shouldStop);
                    PathObjectTools.lockObjects(hierarchy, unlocked);
                    var saved = lifecycle.commit(working);
                    if (saved.viewerRefreshFailure().isPresent()) {
                        throw new IOException(
                                "Annotation boundaries were saved and locked, but QuPath could not refresh the image. Reopen the image before running inference.",
                                saved.viewerRefreshFailure().orElseThrow());
                    }
                    requireRunning(shouldStop);
                    working =
                            lifecycle.openWorkingImage(
                                    request.entry(),
                                    request.entry().getID(),
                                    request.clientBuild().limits());
                    if (!saved.savedRevision()
                            .equals(working.sourceRevision().imageDataRevision())) {
                        throw new IllegalArgumentException(
                                "The image changed after saving the annotation locks. Refresh before trying again.");
                    }
                    var current = plan(request, action, working);
                    if (!plan.equals(current)) {
                        throw new IllegalArgumentException(
                                "Selected annotation boundaries changed after their preparation save.");
                    }
                    plan = current;
                }
            }
            requireRunning(shouldStop);
            return new Prepared(working, plan);
        } catch (IOException
                | ImageLifecycle.RevisionConflictException
                | RuntimeException
                | Error failure) {
            working.close();
            throw failure;
        }
    }

    private static ImageExecutionPlan plan(
            ImageActionExecutor.Request request,
            ActionSelectionCodec.ImageAction action,
            ImageLifecycle.WorkingImage working) {
        var image = working.imageData();
        return ImageActionPlanner.plan(
                request.actionId(),
                request.clientBuild(),
                request.entry().getID(),
                request.module(),
                action,
                image.getHierarchy(),
                image.getServer().getWidth(),
                image.getServer().getHeight());
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean())
            throw new CancellationException("Annotation preparation cancelled");
    }
}
