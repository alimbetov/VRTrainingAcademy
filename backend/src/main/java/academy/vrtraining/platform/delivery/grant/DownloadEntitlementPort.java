package academy.vrtraining.platform.delivery.grant;

/** Implement against trusted organization, user/device and entitlement data. */
@FunctionalInterface
public interface DownloadEntitlementPort {
    boolean canDownload(String subjectId, String tenantId, String contentId);
}
