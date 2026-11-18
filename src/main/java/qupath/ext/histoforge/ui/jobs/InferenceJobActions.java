package qupath.ext.histoforge.ui.jobs;

import javafx.beans.property.ReadOnlyStringProperty;
import javafx.collections.ObservableList;

/** Project-owned inference actions presented in Jobs, alongside server-owned training jobs. */
public interface InferenceJobActions {
    ObservableList<JobRow.Inference> inferenceRows();

    ReadOnlyStringProperty inferenceProblem();

    void refreshInference();

    void reconnect(JobRow.Inference row);

    void stop(JobRow.Inference row);

    void clearHistory(JobRow.Inference row);
}
