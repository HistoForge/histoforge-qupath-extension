package qupath.ext.histoforge.pipeline;

import groovy.lang.Binding;
import java.util.List;
import java.util.Map;
import qupath.lib.objects.PathObject;

/**
 * Defines the Groovy binding names shared by ordinary recipe scripts and detached metric scripts.
 */
public final class ScriptBindings {

    private ScriptBindings() {}

    public static final String CHILDREN = "children";
    public static final String ROI_AREA_PX = "roiAreaPx";
    public static final String PIXEL_WIDTH_MICRONS = "pixelWidthMicrons";
    public static final String PIXEL_HEIGHT_MICRONS = "pixelHeightMicrons";
    public static final String CONFIG = "config";
    public static final String UTILS = "m";
    public static final String ROI = "roi";

    /** Bind the variables every script gets: selected objects, region area, config, and helpers. */
    public static void applyCommon(
            Binding binding,
            List<PathObject> children,
            double roiAreaPx,
            double pixelWidthMicrons,
            double pixelHeightMicrons) {
        binding.setVariable(CHILDREN, children);
        binding.setVariable(ROI_AREA_PX, roiAreaPx);
        binding.setVariable(PIXEL_WIDTH_MICRONS, pixelWidthMicrons);
        binding.setVariable(PIXEL_HEIGHT_MICRONS, pixelHeightMicrons);
        binding.setVariable(CONFIG, Map.of());
        binding.setVariable(UTILS, MetricUtils.INSTANCE);
    }
}
