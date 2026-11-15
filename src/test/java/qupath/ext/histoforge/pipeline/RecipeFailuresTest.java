package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;

class RecipeFailuresTest {

    @Test
    void localCapacity_remainsDistinctAcrossRecipeFailureBoundaries() {
        LocalCapacityExceededException failure =
                new LocalCapacityExceededException("max_hierarchy_objects", 6, 5);

        assertEquals(RunResults.FailureType.LOCAL_CAPACITY, RecipeFailures.detail(failure).type());
        assertEquals(
                RunResults.FailureType.LOCAL_CAPACITY, RecipeFailures.localStaging(failure).type());
        assertEquals(
                RunResults.FailureType.LOCAL_CAPACITY, RecipeFailures.applyFailure(failure).type());
        assertEquals(
                "max_hierarchy_objects exceeded: captured 6, limit 5",
                RecipeFailures.applyFailure(failure).message());
    }

    @Test
    void ordinaryApplyFailure_remainsCorruptWorkingState() {
        RunResults.FailureDetail detail =
                RecipeFailures.applyFailure(new IllegalArgumentException("invalid geometry"));

        assertEquals(RunResults.FailureType.CORRUPT_IMAGE_STATE, detail.type());
        assertEquals("working hierarchy apply failed: invalid geometry", detail.message());
    }
}
