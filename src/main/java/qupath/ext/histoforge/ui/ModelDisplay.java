package qupath.ext.histoforge.ui;

/** Read-only model labels shared by Train and Modules; never decides runner availability. */
public final class ModelDisplay {
    private ModelDisplay() {}

    public static String architecture(String arch) {
        return switch (arch) {
            case "dinov3-adapter-cellpose" -> "DINOv3 + Cellpose head";
            case "dinov3-adapter-hovernet" -> "DINOv3 + HoVer-Net head";
            case "dinov3-adapter-semantic" -> "DINOv3 + classification head";
            case "dinov3-adapter-point" -> "DINOv3 + point-detection head";
            default -> arch;
        };
    }

    public static String architectureHelp(String arch) {
        String details =
                switch (arch) {
                    case "dinov3-adapter-cellpose" ->
                            "Cellpose predicts directions toward object centres to group pixels into individual objects. When the task includes classification, it also assigns each object a class.";
                    case "dinov3-adapter-hovernet" ->
                            "HoVer-Net predicts each pixel's position relative to its object's centre to separate individual objects. When the task includes classification, it also assigns each object a class.";
                    case "dinov3-adapter-semantic" ->
                            "Assigns a class to each pixel, producing regions of each class. This task does not identify separate objects within those regions.";
                    case "dinov3-adapter-point" ->
                            "Finds object centres and assigns a class and detection score to each point. It does not provide object outlines or area measurements.";
                    default -> "";
                };
        return "Architecture describes the model's design. Check the module documentation for its intended tissues, stains and limitations."
                + (details.isEmpty() ? "" : "\n\n" + details)
                + "\n\nArchitecture ID: "
                + arch;
    }

    public static String task(String outputType) {
        return switch (outputType) {
            case "instance_segmentation" -> "Instance segmentation";
            case "instance_segmentation_and_classification" ->
                    "Instance segmentation + classification";
            case "dense_classification" -> "Pixel classification (semantic segmentation)";
            case "region_classification" -> "Region classification";
            case "point_detection" -> "Point detection";
            default -> outputType;
        };
    }

    /** The Train choice that trains the starting model's classes, or its single label, as is. */
    public static String keepClassesChoice(String sourceOutputType) {
        return "instance_segmentation".equals(sourceOutputType)
                ? "Outline objects only"
                : "Keep the model's classes";
    }

    /** The Train choice that builds a new class list. */
    public static String replaceClassesChoice(String sourceOutputType) {
        return "instance_segmentation".equals(sourceOutputType)
                ? "Outline and classify objects"
                : "Replace the model's classes";
    }

    /** The Train as choice that teaches a QuPath class as background. */
    public static String backgroundChoice(String sourceOutputType) {
        return "dense_classification".equals(sourceOutputType)
                        || "region_classification".equals(sourceOutputType)
                ? "Background (no class)"
                : "Background (not an object)";
    }
}
