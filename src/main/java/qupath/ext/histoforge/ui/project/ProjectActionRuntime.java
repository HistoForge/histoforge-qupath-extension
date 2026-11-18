package qupath.ext.histoforge.ui.project;

import java.io.IOException;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.pipeline.ImageActionCoordinator;

/** One captured gateway/coordinator pair shared by a bounded Project-tab action session. */
record ProjectActionRuntime(
        ModelGatewayExecutor gateway,
        ImageActionCoordinator coordinator,
        LargeActionHandleStore handles)
        implements AutoCloseable {

    ProjectActionRuntime {
        gateway = Objects.requireNonNull(gateway, "gateway");
        coordinator = Objects.requireNonNull(coordinator, "coordinator");
        handles = Objects.requireNonNull(handles, "handles");
    }

    @Override
    public void close() {
        gateway.close();
        try {
            handles.cleanupSavedFiles();
        } catch (IOException failure) {
            LoggerFactory.getLogger(ProjectActionRuntime.class)
                    .warn(
                            "Could not remove saved analysis's recovery files; history is retained for later cleanup",
                            failure);
        }
    }
}
