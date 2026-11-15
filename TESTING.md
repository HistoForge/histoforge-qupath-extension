# Testing

Run `./gradlew build shadowJar` with JDK 21 for QuPath 0.6.0. With JDK 25, run
`./gradlew -PhistoforgeQuPathVersion=0.7.0 test` for QuPath 0.7.0.
Use `gradlew.bat` on Windows.

The included tests use synthetic contract data, temporary projects, and mocked
services. They cover capture, geometry, measurements, transport, local setup,
and UI logic. CI also runs tests against the packaged JAR and checks local
runtime setup on Linux, macOS, and Windows.

Windows CI uses Windows Server 2022. Windows 11 hardware has not been tested.

Private module recipes, tissue-derived geometry, and tests that require them are
not included. Passing this suite does not establish model accuracy, clinical
validity, or the complete desktop installation experience.

Native UI checks are separate Gradle tasks, such as `projectRefreshSmoke` and
`trainingCaptureSmoke`; they require a desktop session. Other opt-in probes may
use supplied slides or cloud credentials. Read each task before running it.
Never point a test at an irreplaceable project.

`trainingFormSmoke` also checks that expanded setup logs fill available height,
resize with the window, remain copyable, and scroll in short Settings windows.

`annotationScopeSmoke` checks Project controls at narrow and wide sizes, in
light and dark styles. It saves screenshots and checks labels, wrapping, long
model names, and scope selection. To test a built JAR, add
`-PhistoforgeDistributionArtifact=/absolute/path/to/extension.jar`. Windows CI
runs this check against the packaged JAR on both supported QuPath versions.

`catalogJobsRefreshSmoke` holds module removal pending to check progress, duplicate
clicks, confirmation and failure feedback. CI runs it against the packaged JAR on
macOS and Windows. It uses controlled responses, not a real module deletion.

`componentUiSmoke` checks cloud sign-in, save/open cancellation, import pagination, ROI drawing and protection,
Project selection and grouping, recycled table cells, and FX-thread ownership during interruption.
Drawing checks include all four directions, Shift, pixel snapping, focus, bounds and rejected
gestures. This opt-in task requires a desktop and is not part of `build` or ordinary CI. It uses
synthetic images and mocked services, isolates preferences, and saves screenshots.
The S3 reader tests compare decoded pixels and calibration from a generated tiled
TIFF; they do not require AWS credentials or real slides.

A change to the private `uv` pin also needs clean helper installation and restart
checks on macOS and Windows. Keep Python and the bundled dependency lock unchanged
when testing an installer-only fix.

Keep new fixtures synthetic or document permission to redistribute them. Never
commit credentials, model weights, or private project data.
