package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class ExecutionScopeCodecTest {

    @Test
    void nativeQuPathRingOrderRoundTripsAsOneCanonicalTargetValue() {
        var geometry =
                ROIs.createRectangleROI(10, 20, 200, 200, ImagePlane.getDefaultPlane())
                        .getGeometry();
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Roi("33333333-3333-4333-8333-333333333333"),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));

        assertEquals(
                target, ExecutionScopeCodec.parseTargetRegion(ExecutionScopeCodec.toJson(target)));
    }
}
