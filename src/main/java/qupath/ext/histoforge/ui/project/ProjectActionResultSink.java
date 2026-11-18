package qupath.ext.histoforge.ui.project;

import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.pipeline.RunResults;

/** Records terminal image truth only while its captured project and module remain current. */
@FunctionalInterface
interface ProjectActionResultSink {

    void accept(
            Object projectIdentity,
            String imageId,
            ModuleContentIdentity module,
            RunResults.ImageRunResult result);
}
