package qupath.ext.histoforge.client.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Body of {@code POST /v1/publish} — the uniform vendor-transform publish (any module).
 *
 * <p>{@code sourceModule} is the current gateway module to snapshot; {@code targetRepository} is
 * the HF {@code <owner>/<name>} that receives the commit. {@code visibility} applies only when
 * creating that repository. {@code cardMarkdown} is a fallback used only when neither source nor
 * target has a README and the target has no recipe. It assigns no license. {@code syncLocally}
 * optionally installs the exact published bytes back into the gateway. Registry credentials remain
 * server-side.
 */
public record PublishRequest(
        @SerializedName("source_module") String sourceModule,
        @SerializedName("target_repository") String targetRepository,
        HuggingFaceVisibility visibility,
        @SerializedName("card_markdown") String cardMarkdown,
        @SerializedName("sync_locally") boolean syncLocally) {}
