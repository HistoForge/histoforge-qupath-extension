package qupath.ext.histoforge.pipeline;

import java.util.Objects;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.SourceInputPlan;

/** Process-local proof of one sealed unchanged original shared by one image action. */
record SealedOriginalSource(
        String actionId,
        String imageId,
        BuildIdentityCodec.ClientBuildIdentity clientBuild,
        String deploymentId,
        InferenceIdentityCodec.ModuleContentIdentity module,
        SourceIdentityCodec.ImageSourceIdentity pixelSource,
        SourceArtifactRef artifact,
        SourceArtifactCodec.SourceArtifactStartRequest startRequest) {

    SealedOriginalSource {
        actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
        imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
        clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
        deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
        module = Objects.requireNonNull(module, "module");
        pixelSource = Objects.requireNonNull(pixelSource, "pixelSource");
        artifact = Objects.requireNonNull(artifact, "artifact");
        startRequest = Objects.requireNonNull(startRequest, "startRequest");
        if (!(startRequest.origin() instanceof SourceArtifactCodec.OriginalWsi original)
                || !actionId.equals(startRequest.creationActionId())
                || !artifact.artifactId().equals(startRequest.artifactId())
                || !artifact.creationActionId().equals(actionId)
                || !artifact.startRequestDigest()
                        .equals(SourceArtifactCodec.startRequestDigest(startRequest))
                || artifact.container() != startRequest.container()
                || !original.source().equals(pixelSource)) {
            throw new IllegalArgumentException(
                    "shared original identity does not match its sealed source request");
        }
    }

    /** Require the action subset and current source to belong to this captured whole action. */
    void requireAction(
            ImageExecutionPlan plan, RasterInputCodec.ActionSourceRevision sourceRevision) {
        ImageExecutionPlan action = Objects.requireNonNull(plan, "plan");
        RasterInputCodec.ActionSourceRevision revision =
                Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (!actionId.equals(action.actionId())
                || !imageId.equals(action.imageId())
                || !clientBuild.equals(action.clientBuild())
                || !deploymentId.equals(action.module().deploymentId())
                || !module.equals(action.module().module())
                || !pixelSource.equals(revision.pixelSource())
                || !imageId.equals(revision.imageDataRevision().imageId())) {
            throw new IllegalArgumentException(
                    "shared original belongs to another image action or source revision");
        }
    }

    /** Require one target's captured original plan to be covered by this action-wide artifact. */
    void requireTargetPlan(SourceInputPlan.TargetSourcePlan sourcePlan) {
        SourceInputPlan.TargetSourcePlan source = Objects.requireNonNull(sourcePlan, "sourcePlan");
        if (!(source.artifact() instanceof SourceInputPlan.OriginalArtifactPlan original)) {
            throw new IllegalArgumentException(
                    "shared original cannot satisfy a derived source plan");
        }
        var captured = (SourceArtifactCodec.OriginalWsi) startRequest.origin();
        if (!captured.source().equals(source.sourceRevision().pixelSource())
                || captured.pyramidSeriesIndex() != original.pyramidSeriesIndex()
                || !captured.levelZeroPixelsToSlide().equals(original.levelZeroPixelsToSlide())
                || !captured.entryPath().equals(original.entryPath())
                || !captured.readerSettings().equals(original.readerSettings())
                || startRequest.container() != original.container()
                || startRequest.totalBytes() != original.totalBytes()
                || !startRequest.requiredLevels().containsAll(original.requiredLevels())) {
            throw new IllegalArgumentException(
                    "shared original does not cover the captured target source plan");
        }
    }
}
