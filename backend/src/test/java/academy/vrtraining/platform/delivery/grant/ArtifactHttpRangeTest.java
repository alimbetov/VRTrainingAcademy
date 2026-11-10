package academy.vrtraining.platform.delivery.grant;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ArtifactHttpRangeTest {
    private static final String SHA = "a".repeat(64);

    @Test void completeArtifactReturnsStrongStableShaBasedEtag() {
        var x = ArtifactHttpRange.select(1000, SHA, null, null);
        assertFalse(x.partial());
        assertEquals(1000, x.length());
        assertEquals("\"" + SHA + "\"", x.etag());
        assertNull(x.contentRange());
    }

    @Test void rangeAndMatchingIfRangeAreResumable() {
        var x = ArtifactHttpRange.select(1000, SHA, "bytes=300-", "\"" + SHA + "\"");
        assertTrue(x.partial());
        assertEquals(700, x.length());
        assertEquals("bytes 300-999/1000", x.contentRange());
        assertEquals("bytes=300-999", x.s3Range());
    }

    @Test void staleOrWeakIfRangeMustReDownloadWholeArtifact() {
        for (String tag : new String[]{"\"other\"", "W/\"" + SHA + "\"", "yesterday"}) {
            var x = ArtifactHttpRange.select(10, SHA, "bytes=5-", tag);
            assertFalse(x.partial());
            assertEquals(10, x.length());
        }
    }

    @Test void malformedMultiPartOrBeyondEndAreRejected() {
        for (String range : new String[]{"bytes=100-", "bytes=10-20", "bytes=0-1,3-4",
                "bytes=-3", "bytes=banana", "bytes=4-2", "bytes=9999999999999999999999-"}) {
            assertThrows(ArtifactHttpRange.InvalidRangeException.class,
                    () -> ArtifactHttpRange.select(10, SHA, range, null), range);
        }
    }
}
