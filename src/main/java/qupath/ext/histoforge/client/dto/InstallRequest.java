package qupath.ext.histoforge.client.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Body of {@code POST /v1/install}.
 *
 * <p>{@code moduleRef} is the qualified {@code <owner>/<name>} ref to install. {@code
 * huggingFaceRevision} pins a branch, tag, or commit; {@code null} resolves the repository default.
 * Registry credentials are owned by the gateway deployment and never enter this request.
 */
public record InstallRequest(
        @SerializedName("module_ref") String moduleRef,
        @SerializedName("hugging_face_revision") String huggingFaceRevision) {}
