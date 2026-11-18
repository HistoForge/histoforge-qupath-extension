package qupath.ext.histoforge.ui.modules;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.lib.gui.QuPathGUI;

/** A submitting application-modal dialog cannot hide a pending request from Settings. */
public final class AdminSubmissionSmoke {
    private AdminSubmissionSmoke() {}

    public static void verify() throws Exception {
        var qupath = mock(QuPathGUI.class);
        var owner = new Stage();
        when(qupath.getStage()).thenReturn(owner);
        var client = mock(ModuleGatewayClient.class);
        var module = mock(InstalledModule.class);
        when(module.ref()).thenReturn("owner/module");
        when(module.recipe()).thenReturn(mock(ModuleRecipe.class));
        verifyDialog(
                InstallDialog.class,
                new Class<?>[] {
                    QuPathGUI.class, ModuleGatewayClient.class, String.class, Runnable.class
                },
                new Object[] {qupath, client, "owner/module", (Runnable) () -> {}});
        verifyDialog(
                PublishDialog.class,
                new Class<?>[] {
                    QuPathGUI.class,
                    ModuleGatewayClient.class,
                    InstalledModule.class,
                    Runnable.class
                },
                new Object[] {qupath, client, module, (Runnable) () -> {}});
        owner.close();
    }

    private static void verifyDialog(Class<?> type, Class<?>[] signature, Object[] args)
            throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(signature);
        constructor.setAccessible(true);
        Object dialog = constructor.newInstance(args);
        var stageField = type.getDeclaredField("stage");
        stageField.setAccessible(true);
        var stage = (Stage) stageField.get(dialog);
        var setter = type.getDeclaredMethod("setSubmitting", boolean.class);
        setter.setAccessible(true);
        try {
            setter.invoke(dialog, true);
            var cancel =
                    stage.getScene().getRoot().lookupAll(".button").stream()
                            .filter(
                                    node ->
                                            node instanceof Button button
                                                    && button.getText().equals("Cancel"))
                            .map(Button.class::cast)
                            .findFirst()
                            .orElseThrow();
            if (!cancel.isDisabled())
                throw new AssertionError(type + " allows cancelling a submitted request");
            Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            if (!stage.isShowing()) throw new AssertionError(type + " hid a pending submission");
            setter.invoke(dialog, false);
            Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            if (stage.isShowing())
                throw new AssertionError(type + " remains blocked after submission settled");
        } finally {
            setter.invoke(dialog, false);
            stage.close();
        }
    }
}
