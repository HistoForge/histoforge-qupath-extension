package qupath.ext.histoforge.ui.project;

import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.histoforge.ui.HistoforgeStyles;

/** Enlarged project thumbnails with fit-to-window, wheel zoom and keyboard navigation. */
public final class ThumbnailViewer {
    private final List<ImageRowModel> rows;
    private final Stage window = new Stage();
    private final ImageView image = new ImageView();
    private final Label position = new Label();
    private final Button previous = new Button("◀"), next = new Button("▶");
    private final SimpleDoubleProperty zoom = new SimpleDoubleProperty();
    private int index;

    public static void show(Window owner, List<ImageRowModel> images, int startIndex) {
        if (!images.isEmpty())
            new ThumbnailViewer(owner, List.copyOf(images), startIndex).window.showAndWait();
    }

    private ThumbnailViewer(Window owner, List<ImageRowModel> rows, int start) {
        this.rows = rows;
        window.initOwner(owner);
        window.initModality(Modality.WINDOW_MODAL);
        image.setPreserveRatio(true);
        image.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");
        var canvas = new StackPane(image);
        canvas.setPadding(new Insets(12));
        canvas.setStyle("-fx-background-color: #2b2b2b;");
        var viewport = new ScrollPane(canvas);
        viewport.setFitToWidth(true);
        viewport.setFitToHeight(true);
        viewport.setPannable(true);
        viewport.setStyle("-fx-background: #2b2b2b; -fx-background-color: #2b2b2b;");
        var scale =
                Bindings.createDoubleBinding(
                        () -> {
                            var source = image.getImage();
                            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0)
                                return 1.0;
                            var bounds = viewport.getViewportBounds();
                            return zoom.get() > 0
                                    ? zoom.get()
                                    : Math.min(
                                            1,
                                            Math.min(
                                                    Math.max(1, bounds.getWidth() - 24)
                                                            / source.getWidth(),
                                                    Math.max(1, bounds.getHeight() - 24)
                                                            / source.getHeight()));
                        },
                        image.imageProperty(),
                        viewport.viewportBoundsProperty(),
                        zoom);
        image.fitWidthProperty()
                .bind(
                        Bindings.createDoubleBinding(
                                () ->
                                        image.getImage() == null
                                                ? 1
                                                : image.getImage().getWidth() * scale.get(),
                                image.imageProperty(),
                                scale));
        image.fitHeightProperty()
                .bind(
                        Bindings.createDoubleBinding(
                                () ->
                                        image.getImage() == null
                                                ? 1
                                                : image.getImage().getHeight() * scale.get(),
                                image.imageProperty(),
                                scale));
        canvas.setOnScroll(
                event -> {
                    if (image.getImage() == null || image.getImage().getWidth() <= 0) return;
                    zoom.set(wheelZoom(scale.get(), event.getDeltaY()));
                    event.consume();
                });
        image.setOnMouseClicked(
                event -> {
                    if (event.getClickCount() == 2) zoom.set(0);
                });
        previous.setOnAction(event -> display(index - 1));
        next.setOnAction(event -> display(index + 1));
        position.setStyle("-fx-font-size: 13; -fx-text-fill: white;");
        var navigation = new HBox(10, previous, position, next);
        navigation.setAlignment(Pos.CENTER);
        navigation.setPadding(new Insets(8));
        var layout = new BorderPane(viewport);
        layout.setBottom(navigation);
        layout.setPadding(new Insets(10));
        layout.setStyle("-fx-background-color: #2b2b2b;");
        var scene = new Scene(layout);
        HistoforgeStyles.apply(scene);
        scene.setOnKeyPressed(
                event -> {
                    switch (event.getCode()) {
                        case LEFT, UP -> previous.fire();
                        case RIGHT, DOWN -> next.fire();
                        case ESCAPE -> window.close();
                        default -> {}
                    }
                });
        window.setScene(scene);
        window.setWidth(1000);
        window.setHeight(900);
        display(start);
    }

    static double wheelZoom(double current, double delta) {
        return Math.clamp(current * (delta > 0 ? 1.1 : 1 / 1.1), 0.05, 16);
    }

    static String title(ImageRowModel row) {
        String group = row.groupProperty().get();
        return row.entry.getImageName()
                + (group == null || group.equals("—") ? "" : " · " + group)
                + " · "
                + row.roiCount
                + " ROIs";
    }

    private void display(int requested) {
        index = Math.clamp(requested, 0, rows.size() - 1);
        zoom.set(0);
        image.setImage(rows.get(index).wsiThumbnail);
        window.setTitle(title(rows.get(index)));
        position.setText((index + 1) + " / " + rows.size());
        previous.setDisable(index == 0);
        next.setDisable(index + 1 == rows.size());
    }
}
