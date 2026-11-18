package qupath.ext.histoforge.large;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;

/** Selects owned temporary files, retaining anything another unfinished target still needs. */
final class RecoveryFileCleanup {
    private RecoveryFileCleanup() {}

    static List<LocalFileRecord> completedImages(
            List<LargeActionHandle> handles, Set<String> stopped) {
        Predicate<LargeActionHandle> disposable =
                h ->
                        h.phase().hasCompletedSave()
                                || h.phase().terminal() && stopped.contains(h.handleGenerationId());
        Set<String> protectedPaths =
                handles.stream()
                        .filter(disposable.negate())
                        .flatMap(RecoveryFileCleanup::files)
                        .map(LocalFileRecord::projectRelativePosixPath)
                        .collect(Collectors.toSet());
        return handles.stream()
                .filter(disposable)
                .flatMap(RunImageSources::sources)
                .flatMap(s -> s.ownedUploadFile().stream())
                .filter(f -> !protectedPaths.contains(f.projectRelativePosixPath()))
                .distinct()
                .toList();
    }

    static List<LocalFileRecord> dismissedFiles(
            LargeActionHandle target, List<LargeActionHandle> all) {
        Set<String> protectedPaths =
                all.stream()
                        .filter(h -> !h.handleGenerationId().equals(target.handleGenerationId()))
                        .flatMap(RecoveryFileCleanup::files)
                        .map(LocalFileRecord::projectRelativePosixPath)
                        .collect(Collectors.toSet());
        return files(target)
                .filter(f -> !protectedPaths.contains(f.projectRelativePosixPath()))
                .distinct()
                .toList();
    }

    /** Accepted cloud contributions no longer need local pixels; unbound uploads still do. */
    static List<LocalFileRecord> boundImages(
            LargeActionHandle target, List<LargeActionHandle> all) {
        var uploads =
                target.steps().stream()
                        .flatMap(step -> step.runs().stream())
                        .flatMap(run -> run.imageSources().stream())
                        .flatMap(images -> images.uploads().stream())
                        .toList();
        var protectedPaths =
                all.stream()
                        .flatMap(
                                handle ->
                                        Stream.concat(
                                                handle.source().stream()
                                                        .filter(
                                                                source ->
                                                                        source.sealedArtifact()
                                                                                .isEmpty())
                                                        .flatMap(
                                                                source ->
                                                                        source
                                                                                .ownedUploadFile()
                                                                                .stream()),
                                                handle.steps().stream()
                                                        .flatMap(step -> step.runs().stream())
                                                        .flatMap(run -> run.imageSources().stream())
                                                        .flatMap(
                                                                images -> images.uploads().stream())
                                                        .filter(upload -> !upload.bindingAccepted())
                                                        .flatMap(
                                                                upload ->
                                                                        upload
                                                                                .source()
                                                                                .ownedUploadFile()
                                                                                .stream())))
                        .map(LocalFileRecord::projectRelativePosixPath)
                        .collect(Collectors.toSet());
        return uploads.stream()
                .filter(RunImageSources.Upload::bindingAccepted)
                .flatMap(upload -> upload.source().ownedUploadFile().stream())
                .filter(file -> !protectedPaths.contains(file.projectRelativePosixPath()))
                .distinct()
                .toList();
    }

    private static Stream<LocalFileRecord> files(LargeActionHandle handle) {
        return Stream.concat(
                RunImageSources.sources(handle)
                        .flatMap(
                                s ->
                                        Stream.concat(
                                                Stream.of(s.startRequestFile()),
                                                s.ownedUploadFile().stream())),
                handle.steps().stream()
                        .flatMap(s -> s.runs().stream())
                        .map(LargeActionTypes.ModelRunRef::requestFile));
    }
}
