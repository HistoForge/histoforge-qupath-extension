package qupath.ext.histoforge.ui.project;

import java.util.Collection;
import java.util.Optional;
import java.util.TreeSet;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;

/** Scope and annotation-class controls, without project IO or inference dispatch. */
final class InferenceScopeControls {
    private final ComboBox<Mode> mode;
    private final ComboBox<String> annotationClass;
    private boolean restoring;

    InferenceScopeControls(
            ComboBox<Mode> mode, ComboBox<String> annotationClass, Runnable onChanged) {
        this.mode = mode;
        this.annotationClass = annotationClass;
        mode.getItems().setAll(Mode.values());
        restore(ProjectInferenceScope.defaults());
        mode.valueProperty().addListener((ignored, previous, current) -> changed(onChanged));
        annotationClass
                .valueProperty()
                .addListener((ignored, previous, current) -> changed(onChanged));
    }

    ProjectInferenceScope selected() {
        return new ProjectInferenceScope(
                mode.getValue(), Optional.ofNullable(annotationClass.getValue()));
    }

    void restore(ProjectInferenceScope scope) {
        restoring = true;
        try {
            annotationClass.getItems().clear();
            mode.setValue(scope.mode());
            scope.annotationClass().ifPresent(annotationClass.getItems()::add);
            annotationClass.setValue(scope.annotationClass().orElse(null));
            updateVisibility();
        } finally {
            restoring = false;
        }
    }

    void refreshClasses(Collection<String> discoveredClasses) {
        restoring = true;
        try {
            String selected = annotationClass.getValue();
            var classes = new TreeSet<>(discoveredClasses);
            // A saved class with no current matches stays selected; never substitute another one.
            if (selected != null) classes.add(selected);
            // Replacing the list keeps JavaFX's popup and displayed label in sync with its value.
            // Mutating the existing list can display a different class without
            // changing the class captured for inference.
            annotationClass.setItems(FXCollections.observableArrayList(classes));
            annotationClass.setValue(selected);
        } finally {
            restoring = false;
        }
    }

    private void changed(Runnable onChanged) {
        if (restoring) return;
        updateVisibility();
        onChanged.run();
    }

    private void updateVisibility() {
        boolean visible = mode.getValue() == Mode.ANNOTATIONS_BY_CLASS;
        annotationClass.setVisible(visible);
        annotationClass.setManaged(visible);
    }
}
