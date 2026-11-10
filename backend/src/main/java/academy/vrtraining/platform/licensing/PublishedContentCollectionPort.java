package academy.vrtraining.platform.licensing;

import java.util.Optional;

/**
 * Trusted Content Domain mapping from published contentId to collectionId.
 * A caller-provided collection string is not a publication authority.
 */
@FunctionalInterface
public interface PublishedContentCollectionPort {
    Optional<String> publishedCollectionOf(String contentId);
}
