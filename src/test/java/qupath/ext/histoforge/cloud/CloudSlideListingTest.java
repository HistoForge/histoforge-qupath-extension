package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

class CloudSlideListingTest {
    @Test
    void paginatesS3BeforeFilteringAndKeepsExactKeys() throws Exception {
        var client = mock(S3Client.class);
        var config =
                new S3ConnectionConfig(
                        "https://example.test/start", "us-east-2", "slides", "tissue/");
        when(client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(
                        call -> {
                            ListObjectsV2Request request = call.getArgument(0);
                            assertEquals("slides", request.bucket());
                            assertEquals("tissue/", request.prefix());
                            return new ListObjectsV2Iterable(client, request);
                        });
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .isTruncated(true)
                                .nextContinuationToken("next")
                                .contents(object("tissue/a.TIFF", 1), object("tif", 5))
                                .build(),
                        ListObjectsV2Response.builder()
                                .isTruncated(false)
                                .contents(
                                        object("tissue/b #1.svs", 2), object("tissue/note.txt", 4))
                                .build());
        var slides = CloudSlideListing.load(client, config);
        assertEquals(
                List.of("tissue/b #1.svs", "tissue/a.TIFF"),
                slides.stream().map(CloudSlideListing.Slide::key).toList());
        var uri = slides.getFirst().uri("slides");
        assertEquals("/tissue/b #1.svs", uri.getPath());
        assertEquals("histoforge-cloud://slides/tissue/b%20%231.svs", uri.toString());
        verify(client, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
        assertFalse(CloudSlideListing.isSlide("tissue.tif/noextension"));
    }

    @Test
    void filtersSelectionAndMembershipWithoutChangingListingOrPageOrder() {
        var slides =
                IntStream.range(0, 23)
                        .mapToObj(
                                i ->
                                        new CloudSlideListing.Slide(
                                                "dir/Field " + i + ".tif", i, Instant.EPOCH, "e"))
                        .toList();
        var selected = Set.of(slides.get(1).key(), slides.get(11).key());
        var project = Set.of(slides.get(1).key());
        assertEquals(
                List.of(slides.get(1), slides.get(11)),
                CloudSlideListing.filter(slides, " FIELD 1 ", "Selected", selected, project));
        assertEquals(
                List.of(slides.get(1)),
                CloudSlideListing.filter(slides, "", "In Project", selected, project));
        assertEquals(
                22,
                CloudSlideListing.filter(slides, "", "Not in Project", selected, project).size());
        var last = CloudSlideListing.page(slides, 99);
        assertEquals(2, last.index());
        assertEquals(3, last.pages());
        assertEquals(slides.subList(20, 23), last.slides());
        assertEquals("Displaying 21–23 of 23", last.label());
        assertEquals(slides.subList(0, 10), CloudSlideListing.page(slides, -1).slides());
        assertEquals("0 slides", CloudSlideListing.page(List.of(), 3).label());
        assertEquals(23, slides.size());
    }

    @Test
    void identifiesNestedAuthenticationFailureOnly() {
        assertTrue(
                CloudSlideListing.authenticationFailure(
                        new RuntimeException(S3Exception.builder().statusCode(403).build())));
        assertFalse(
                CloudSlideListing.authenticationFailure(
                        S3Exception.builder().statusCode(500).build()));
        assertEquals("1 KB", CloudSlideListing.sizeLabel(1024));
    }

    private static S3Object object(String key, int seconds) {
        return S3Object.builder()
                .key(key)
                .size(100L)
                .lastModified(Instant.ofEpochSecond(seconds))
                .eTag("etag")
                .build();
    }
}
