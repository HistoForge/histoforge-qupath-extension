package qupath.ext.histoforge.cloud;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.servers.ImageServer;

class CloudSlideDialogTest {

    @Test
    @SuppressWarnings("unchecked")
    void successfulLoginRepaintsOpenCloudViewsWithoutReplacingOrSavingTheirImageData() {
        var qupath = mock(QuPathGUI.class);
        var cloud = mock(QuPathViewer.class);
        var anotherCloud = mock(QuPathViewer.class);
        var local = mock(QuPathViewer.class);
        var empty = mock(QuPathViewer.class);
        when(qupath.getAllViewers())
                .thenReturn(FXCollections.observableArrayList(cloud, anotherCloud, local, empty));
        when(cloud.getServer()).thenReturn(mock(S3SlideImageServer.class));
        when(anotherCloud.getServer()).thenReturn(mock(S3SlideImageServer.class));
        when(local.getServer()).thenReturn(mock(ImageServer.class));

        CloudSlideDialog.repaintOpenCloudSlides(qupath);

        verify(qupath).getAllViewers();
        for (var viewer : List.of(cloud, anotherCloud, local, empty)) {
            verify(viewer).getServer();
        }
        verify(cloud).repaintEntireImage();
        verify(anotherCloud).repaintEntireImage();
        verifyNoMoreInteractions(qupath, cloud, anotherCloud, local, empty);
    }
}
