package qupath.ext.histoforge.ui.project;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.lib.projects.ProjectImageEntry;

/** Native table and dialog check with synthetic results; no project or backend changes. */
public final class ProjectRunDetailsSmoke {
    private ProjectRunDetailsSmoke() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Platform.startup(
                () -> {
                    var clipboard = Clipboard.getSystemClipboard();
                    var savedClipboard = new HashMap<DataFormat, Object>();
                    clipboard
                            .getContentTypes()
                            .forEach(type -> savedClipboard.put(type, clipboard.getContent(type)));
                    try {
                        verify(output);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        clipboard.setContent(savedClipboard);
                        List.copyOf(Window.getWindows()).forEach(Window::hide);
                        done.countDown();
                    }
                });
        if (!done.await(45, TimeUnit.SECONDS))
            throw new AssertionError("Run details UI check timed out");
        Platform.exit();
        if (failure.get() != null)
            throw new AssertionError("Run details UI check failed", failure.get());
    }

    private static void verify(Path output) throws Exception {
        String failureMessage =
                "derived source artifact exceeds the byte ceiling and no eligible original is available";
        var result =
                new RunResults.ImageRunResult(
                        "image",
                        RunResults.ImageOutcomeType.FAILED,
                        false,
                        List.of(),
                        List.of(),
                        List.of(
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.INVALID_REQUEST, failureMessage)),
                        Optional.empty());
        String message = ProjectRowStatus.latest(Optional.of(result));
        require(
                message.equals("Failed: INVALID_REQUEST: " + failureMessage),
                "The complete failure did not reach the table");
        @SuppressWarnings("unchecked")
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getImageName()).thenReturn("Large ROI example");
        var row =
                new ImageRowModel(
                        entry,
                        "—",
                        "Example module",
                        "owner/example",
                        Set.of(),
                        "—",
                        message,
                        null,
                        null);
        var table = new TableView<ImageRowModel>();
        ImageTableColumns.install(table, ignored -> {});
        table.getItems().add(row);
        var stage = new Stage();
        stage.setTitle("HistoForge Last Run — local UI verification");
        stage.setScene(new Scene(table, 1000, 250));
        HistoforgeStyles.apply(stage.getScene());
        stage.show();
        table.applyCss();
        table.layout();
        var cell = lastRunCell(table);
        require(cell.getTooltip().getText().contains(message), "Tooltip lost the error ending");
        require(cell.getTooltip().isWrapText(), "Tooltip does not wrap");
        require(cell.getOnMouseClicked() != null, "Failure details cannot be opened");
        click(cell);
        Window details =
                Window.getWindows().stream()
                        .filter(window -> window.getScene().getRoot() instanceof DialogPane)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No run details dialog opened"));
        var pane = (DialogPane) details.getScene().getRoot();
        var text = (TextArea) pane.getContent();
        require(message.equals(text.getText()), "Dialog changed or shortened the message");
        require(!text.isEditable() && text.isWrapText(), "Details must be read-only and wrapped");
        require(pane.getHeaderText().equals("Large ROI example"), "Wrong image in details");
        pane.applyCss();
        pane.layout();
        ImageIO.write(
                SwingFXUtils.fromFXImage(pane.snapshot(null, null), null),
                "PNG",
                output.resolve("run-details.png").toFile());
        var copy =
                pane.getButtonTypes().stream()
                        .filter(button -> button.getText().equals("Copy"))
                        .findFirst()
                        .orElseThrow();
        ((Button) pane.lookupButton(copy)).fire();
        require(
                message.equals(Clipboard.getSystemClipboard().getString()),
                "Copy lost part of the message");
        require(details.isShowing(), "Copy closed the dialog");
        ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
        require(!details.isShowing(), "Close did not dismiss the details");

        row.lastBatchStatus.set(StatusStrings.SAVED);
        table.layout();
        require(cell.getOnMouseClicked() == null, "Saved status retained the failure click action");
        require(
                !cell.getStyleClass().contains(HistoforgeStyles.CLICKABLE)
                        && !cell.getStyleClass().contains(HistoforgeStyles.ERROR),
                "Saved status retained failure styling");
        require(cell.getTooltip().getText().equals(StatusStrings.SAVED), "Stale failure tooltip");
        row.lastBatchStatus.set(message);
        table.layout();
        table.getItems().clear();
        table.layout();
        require(cell.isEmpty(), "Cleared table cell is not empty");
        require(
                cell.getTooltip() == null && cell.getOnMouseClicked() == null,
                "Empty cell retained error details");
        System.out.println(
                "PASS: full message, wrapped tooltip, click, Copy, Close, recycled cells.");
    }

    private static TableCell<?, ?> lastRunCell(TableView<ImageRowModel> table) {
        return table.lookupAll(".table-cell").stream()
                .filter(node -> node instanceof TableCell<?, ?>)
                .map(node -> (TableCell<?, ?>) node)
                .filter(
                        cell ->
                                !cell.isEmpty()
                                        && cell.getTableColumn().getText().equals("Last Run"))
                .findFirst()
                .orElseThrow();
    }

    private static void click(TableCell<?, ?> cell) {
        cell.fireEvent(
                new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        0,
                        0,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        null));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
