package qupath.ext.histoforge.ui.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ModuleReadmeLinksTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String ROOT = "https://huggingface.co/owner/module/blob/" + COMMIT + "/";

    @Test
    void repositoryLinksStayInsideExactCommitAndPreserveEscapes() {
        assertEquals(ROOT + "methods.md", resolve("./methods.md").toASCIIString());
        assertEquals(ROOT + "plot.png", resolve("images/../plot.png").toASCIIString());
        assertEquals(ROOT + "plot.png", resolve("images/%2e%2e/plot.png").toASCIIString());
        assertEquals(
                ROOT + "images/field%201.png", resolve("images/field%201.png").toASCIIString());
        assertEquals(ROOT + "README.md#use", resolve("#use").toASCIIString());
        assertEquals(
                ROOT + "results.csv?label=A%26B&download=true#A%23B",
                resolve("results.csv?label=A%26B&download=true#A%23B").toASCIIString());
    }

    @Test
    void unsafeDestinationsAreNotActions() {
        for (String invalid :
                new String[] {
                    "../plot.png",
                    "%2e%2e/plot.png",
                    "//example.org/x",
                    "https://user:secret@example.org",
                    "https://example.org/%0a",
                    "https://example.org/%5c",
                    "file:///tmp/x",
                    "data:text/html,x",
                    "javascript:alert(1)",
                    "https://bad host/x",
                    "x%"
                }) {
            assertNull(resolve(invalid), invalid);
        }
        assertEquals(
                URI.create("https://example.org/paper?q=x%26y"),
                resolve("https://example.org/paper?q=x%26y"));
    }

    private static URI resolve(String value) {
        return ModuleReadmeLinks.destination(value, "owner/module", COMMIT);
    }
}
