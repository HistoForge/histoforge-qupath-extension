package qupath.ext.histoforge.ui.training;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import qupath.ext.histoforge.contract.TrainingReportTypes.ModuleTrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingReportResponse;

/** Writes one report snapshot locally and hands its file URI to the system browser. */
public final class TrainingReportViewer {

    private TrainingReportViewer() {}

    /** Render and open one credential-free report snapshot. */
    public static void open(ModuleTrainingReportResponse report) throws IOException {
        browse(write(report));
    }

    /** Open a run-owned snapshot, including active, failed, and cancelled runs. */
    public static void open(TrainingReportResponse report) throws IOException {
        browse(write(report));
    }

    private static void browse(Path file) throws IOException {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("opening local reports is not supported on this computer");
        }
        Desktop.getDesktop().browse(file.toUri());
    }

    /** Write one UTF-8 report snapshot for browser handoff and deterministic tests. */
    static Path write(ModuleTrainingReportResponse report) throws IOException {
        return writeHtml(TrainingReportHtml.render(report));
    }

    static Path write(TrainingReportResponse report) throws IOException {
        return writeHtml(TrainingReportHtml.render(report));
    }

    private static Path writeHtml(String html) throws IOException {
        Path file = Files.createTempFile("histoforge-training-report-", ".html");
        boolean complete = false;
        try {
            Files.writeString(file, html, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            complete = true;
            return file;
        } finally {
            if (!complete) {
                Files.deleteIfExists(file);
            }
        }
    }
}
