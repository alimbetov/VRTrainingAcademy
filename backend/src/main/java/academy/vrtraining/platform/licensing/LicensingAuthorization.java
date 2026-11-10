package academy.vrtraining.platform.licensing;

/**
 * Real platform administrator status MUST come from trusted IAM/membership,
 * not a role string or a client-submitted JSON field.
 */
@FunctionalInterface
public interface LicensingAuthorization {
    boolean isPlatformAdministrator(String authenticatedSubject);
}
