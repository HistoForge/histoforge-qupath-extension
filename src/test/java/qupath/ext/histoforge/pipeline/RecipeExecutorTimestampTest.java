package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.HierarchyDelta;

class RecipeExecutorTimestampTest {

    @Test
    void persistenceTimestampIsCanonicalizedAtTheSharedStagingBoundary() {
        ImageExecutionPlan plan = mock(ImageExecutionPlan.class);
        ActionSelectionCodec.Redo action = mock(ActionSelectionCodec.Redo.class);
        BuildIdentityCodec.ClientBuildIdentity clientBuild =
                mock(BuildIdentityCodec.ClientBuildIdentity.class);
        ModuleSnapshot module = mock(ModuleSnapshot.class);
        RecipeStaging.Target staged = mock(RecipeStaging.Target.class);
        ModelOutputCommitter committer = mock(ModelOutputCommitter.class);
        when(plan.actionId()).thenReturn("60000000-0000-4000-8000-000000000001");
        when(plan.action()).thenReturn(action);
        when(plan.clientBuild()).thenReturn(clientBuild);
        when(plan.module()).thenReturn(module);
        when(module.deploymentId()).thenReturn("fixture-deployment");
        when(staged.replacementClosure()).thenReturn(Set.of());
        when(staged.generations()).thenReturn(List.of());
        when(staged.classChanges()).thenReturn(List.of());
        when(committer.stage(any())).thenReturn(mock(HierarchyDelta.class));

        RecipeExecutor.stageDelta(
                plan, committer, staged, Instant.parse("2026-08-22T22:13:53.633456789Z"));

        ArgumentCaptor<ModelOutputCommitter.StagingRequest> request =
                ArgumentCaptor.forClass(ModelOutputCommitter.StagingRequest.class);
        verify(committer).stage(request.capture());
        assertEquals(
                Instant.parse("2026-08-22T22:13:53.633Z"),
                request.getValue().prospectiveCommittedAt());
    }
}
