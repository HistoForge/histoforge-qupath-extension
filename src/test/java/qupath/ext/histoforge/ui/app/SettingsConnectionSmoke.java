package qupath.ext.histoforge.ui.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.ext.histoforge.cli.CliException;
import qupath.ext.histoforge.cli.HistoforgeCli;
import qupath.ext.histoforge.client.ConnectionProbe;
import qupath.ext.histoforge.client.Deployment;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.platform.ExtensionRelease;
import qupath.ext.histoforge.ui.HistoforgePrefs;
import qupath.ext.histoforge.ui.training.TrainingFormSmoke;

/** Exercise the real Settings open/recheck path against an authenticated loopback gateway. */
final class SettingsConnectionSmoke {
    private SettingsConnectionSmoke() {}

    static void verify(Stage owner, Path output) throws Exception {
        var requests = new AtomicInteger();
        var response = new AtomicReference<>(capabilities("0.0.0"));
        var arrived = new AtomicReference<CompletableFuture<Void>>();
        var release = new AtomicReference<CompletableFuture<Void>>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/health",
                exchange -> {
                    requests.incrementAndGet();
                    byte[] bytes =
                            "{\"api_major\":1,\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(bytes);
                    }
                });
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    requests.incrementAndGet();
                    try {
                        require(
                                "Bearer fixture-token"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization")),
                                "Settings did not authenticate the capability request");
                        arrived.get().complete(null);
                        release.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
                        byte[] bytes = response.get().toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (var stream = exchange.getResponseBody()) {
                            stream.write(bytes);
                        }
                    } catch (Exception | AssertionError failure) {
                        arrived.get().completeExceptionally(failure);
                        exchange.close();
                    }
                });
        server.start();
        var target =
                new Deployment(
                        1,
                        "fixture",
                        "http://localhost:" + server.getAddress().getPort(),
                        "fixture",
                        "histoforge-gateway",
                        1,
                        "fixture-token",
                        Instant.EPOCH);
        var callbacks = new ArrayList<ConnectionProbe.Result>();
        var localStops = new AtomicInteger();
        var actionFinishes = new AtomicInteger();
        Stage stage = null;
        try (var stores =
                mockConstruction(
                        DeploymentStore.class,
                        (store, context) -> {
                            when(store.load()).thenReturn(Optional.of(target));
                            when(store.reload()).thenReturn(Optional.of(target));
                        })) {
            var settings =
                    new SettingsDialog(
                            owner,
                            () -> null,
                            callbacks::add,
                            id -> {
                                localStops.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            },
                            actionFinishes::incrementAndGet);
            stage = field(settings, "stage", Stage.class);
            Parent root = stage.getScene().getRoot();
            root.applyCss();
            var warning = (Label) root.lookup("#cloudCompatibilityMessage");
            var version = (Label) root.lookup("#cloudBackendVersion");
            warning.setText("Update cloud backend before starting new work.");
            version.setText("0.0.0");
            @SuppressWarnings("unchecked")
            var gpu = (ComboBox<String>) root.lookup("#defaultInferenceGpu");
            String savedGpu = HistoforgePrefs.defaultInferenceGpuProperty().get();

            arrived.set(new CompletableFuture<>());
            release.set(new CompletableFuture<>());
            settings.show();
            await(arrived.get()::isDone, "Settings did not start its connection probe");
            require(!arrived.get().isCompletedExceptionally(), "Capability authentication failed");
            require(
                    !warning.getText().isBlank() && version.getText().equals("0.0.0"),
                    "Opening Settings cleared the known compatibility warning");
            require(gpu.isDisabled(), "GPU selection must wait for verified capabilities");
            require(
                    !((Label) root.lookup("#inferenceGpuAvailability")).getText().isBlank(),
                    "Unavailable GPU choices have no explanation");
            TrainingFormSmoke.capture(root, output.resolve("settings-recheck-pending.png"));
            release.get().complete(null);
            await(() -> callbacks.size() == 1, "Settings connection check did not finish");
            require(
                    callbacks.getFirst().status() == ConnectionProbe.Status.INCOMPATIBLE,
                    "Old backend unexpectedly permits new work");
            require(gpu.isDisabled(), "An incompatible backend enabled GPU selection");
            require(requests.get() == 2, "Settings performed a duplicate capability probe");

            response.set(capabilities(ExtensionRelease.current().bundledBackendVersion()));
            arrived.set(new CompletableFuture<>());
            release.set(new CompletableFuture<>());
            String retained = warning.getText();
            ((Button) root.lookup("#cloudConnectionStatus").getParent().lookup(".button")).fire();
            await(arrived.get()::isDone, "Recheck did not reach the gateway");
            require(warning.getText().equals(retained), "Recheck made the warning flicker");
            release.get().complete(null);
            await(() -> callbacks.size() == 2, "Recheck did not apply its result");
            require(
                    callbacks.getLast().status() == ConnectionProbe.Status.CONNECTED,
                    "Compatible backend did not connect");
            require(!gpu.isDisabled(), "Verified GPU choices remain disabled");
            require(
                    gpu.getItems().containsAll(List.of("A10G", "L40S", "A100", "H200")),
                    "Verified GPU choices, including H200, are missing");
            require(
                    java.util.Objects.equals(
                            savedGpu, HistoforgePrefs.defaultInferenceGpuProperty().get()),
                    "Refreshing GPU choices changed the saved preference");
            require(requests.get() == 4, "GPU loading performed another network probe");
            var cloud = root.lookup("#cloudSettingsSection");
            require(
                    field(settings, "actionStatus", Label.class).getParent() == cloud,
                    "Cloud action feedback appears outside Cloud settings");
            require(
                    root.lookup("#setupTechnicalDetails").getParent().getParent() == cloud,
                    "Cloud diagnostics appear outside Cloud settings");
            TrainingFormSmoke.capture(root, output.resolve("settings-gpu-choices-loaded.png"));
            gpu.show();
            try {
                var popup =
                        javafx.stage.Window.getWindows().stream()
                                .filter(javafx.scene.control.PopupControl.class::isInstance)
                                .map(javafx.scene.control.PopupControl.class::cast)
                                .filter(
                                        window ->
                                                window.isShowing()
                                                        && window.getOwnerWindow()
                                                                == root.getScene().getWindow())
                                .findFirst()
                                .orElseThrow();
                Parent popupRoot = popup.getScene().getRoot();
                popupRoot.applyCss();
                popupRoot.layout();
                require(
                        popupRoot.lookupAll(".list-cell").stream()
                                .anyMatch(
                                        node ->
                                                node
                                                                instanceof
                                                                javafx.scene.control.ListCell<?>
                                                                        cell
                                                        && "H200".equals(cell.getText())),
                        "H200 did not render in the native GPU menu");
                TrainingFormSmoke.capture(popupRoot, output.resolve("settings-h200-menu.png"));
            } finally {
                gpu.hide();
            }
            verifyUpdatePreflight(settings, owner, target);
            await(() -> callbacks.size() == 3, "Failed update did not recheck its connection");
            require(localStops.get() == 0, "Failed preflight stopped active local work");
            require(actionFinishes.get() == 1, "Update cleanup did not run exactly once");
            require(requests.get() == 6, "Failure recovery performed duplicate connection checks");
            require(
                    field(settings, "actionStatus", Label.class).getText().contains("environment"),
                    "Wrong-target feedback gives no environment guidance");
            var locate = (Button) root.lookup("#locateSavedBackend");
            require(
                    locate.isVisible() && !locate.isDisabled(),
                    "V1 location recovery is unavailable");
            TrainingFormSmoke.capture(root, output.resolve("settings-cloud-action-failure.png"));
            invoke(settings, "beginAction");
            var report =
                    SettingsDialog.class.getDeclaredMethod(
                            "reportActionFailure", String.class, CliException.class);
            report.setAccessible(true);
            report.invoke(
                    settings,
                    "Reconnect",
                    new CliException(
                            CliException.Type.GATEWAY_TARGET_MISMATCH,
                            "The entered Modal environment does not exist. Your saved connection is unchanged."));
            await(() -> callbacks.size() == 4, "Failed Locate did not finish its connection check");
            require(
                    locate.isVisible() && !locate.isDisabled(),
                    "A mistyped environment hid recovery");
            require(requests.get() == 8, "Locate failure performed duplicate connection checks");
            require(localStops.get() == 0, "Location recovery stopped active local work");
            TrainingFormSmoke.capture(root, output.resolve("settings-location-retry.png"));
            var receipt = new JsonObject();
            receipt.addProperty("requests", requests.get());
            receipt.addProperty("callbacks", callbacks.size());
            receipt.addProperty("warning_retained_during_probe", true);
            receipt.addProperty("verified_gpu_choices_include_h200", true);
            receipt.addProperty("failed_preflight_preserves_local_work", true);
            receipt.addProperty("cloud_feedback_under_cloud", true);
            receipt.addProperty("v1_location_recovery_available_after_retry", true);
            Files.writeString(output.resolve("settings-connection-workflow.json"), receipt + "\n");
        } finally {
            if (release.get() != null) release.get().complete(null);
            if (stage != null) stage.close();
            server.stop(0);
        }
    }

    private static void verifyUpdatePreflight(
            SettingsDialog settings, Stage owner, Deployment target) throws Exception {
        var helper = mock(HistoforgeCli.class);
        var failure =
                new CliException(
                        CliException.Type.GATEWAY_TARGET_MISMATCH,
                        "This Modal profile or environment reaches a different backend. Your saved connection is unchanged.");
        doThrow(failure).when(helper).checkUpgrade(eq(target), any());
        invoke(settings, "blockSubmissionsForUpdate");
        invoke(settings, "beginAction");
        try {
            var update =
                    SettingsDialog.class.getDeclaredMethod(
                            "updateCloudBackend",
                            Deployment.class,
                            HistoforgeCli.class,
                            Consumer.class);
            update.setAccessible(true);
            try {
                update.invoke(settings, target, helper, (Consumer<String>) ignored -> {});
                throw new AssertionError("Wrong-target preflight was ignored");
            } catch (InvocationTargetException expected) {
                require(expected.getCause() == failure, "Preflight error was replaced");
            }
            org.mockito.Mockito.verify(helper).verifyRuntime();
            org.mockito.Mockito.verify(helper).checkUpgrade(eq(target), any());
            verifyNoMoreInteractions(helper);
            var report =
                    SettingsDialog.class.getDeclaredMethod(
                            "reportActionFailure", String.class, CliException.class);
            report.setAccessible(true);
            report.invoke(settings, "Update cloud backend", failure);
            require(
                    !owner.getScene().getRoot().isDisabled(),
                    "Failed preflight left the UI blocked");
        } finally {
            invoke(settings, "finishAction");
        }
    }

    private static JsonObject capabilities(String version) throws Exception {
        try (var input =
                SettingsConnectionSmoke.class.getResourceAsStream(
                        "/contracts/gateway-capabilities-v2.json")) {
            require(input != null, "Missing capabilities fixture");
            var value =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject()
                            .getAsJsonArray("capabilityCases")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("value")
                            .deepCopy();
            value.addProperty("deployment_id", "fixture");
            value.addProperty("auth_revision", 1);
            value.getAsJsonObject("gateway_build").addProperty("version", version);
            var gpus = new com.google.gson.JsonArray();
            List.of("A10G", "L40S", "A100", "H200").forEach(gpus::add);
            value.add("inference_gpus", gpus);
            return value;
        }
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static void invoke(SettingsDialog settings, String method) throws Exception {
        var action = SettingsDialog.class.getDeclaredMethod(method);
        action.setAccessible(true);
        action.invoke(settings);
    }

    private static void await(BooleanSupplier condition, String message) {
        if (condition.getAsBoolean()) return;
        Object key = new Object();
        var deadline = new PauseTransition(Duration.seconds(10));
        var pulse =
                new javafx.animation.AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (condition.getAsBoolean()) {
                            stop();
                            deadline.stop();
                            Platform.exitNestedEventLoop(key, Boolean.TRUE);
                        }
                    }
                };
        deadline.setOnFinished(
                event -> {
                    pulse.stop();
                    Platform.exitNestedEventLoop(key, Boolean.FALSE);
                });
        pulse.start();
        deadline.play();
        try {
            require(Boolean.TRUE.equals(Platform.enterNestedEventLoop(key)), message);
        } finally {
            pulse.stop();
            deadline.stop();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
