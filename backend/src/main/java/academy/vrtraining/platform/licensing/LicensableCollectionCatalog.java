package academy.vrtraining.platform.licensing;

import java.util.Set;

/**
 * Publication system must confirm that every requested commercial collection
 * exists, is published and can legally be licensed. Never use public R0 demo
 * records or arbitrary user-provided strings as a publication authority.
 */
@FunctionalInterface
public interface LicensableCollectionCatalog {
    boolean allLicensable(Set<String> collectionIds);
}
