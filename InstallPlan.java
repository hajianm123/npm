/**
 * What an install would do, without having done it (see {@code install --dry-run}).
 *
 * <ul>
 *   <li>{@code addedPackages}: packages that would be newly installed, in install order.</li>
 *   <li>{@code referencedPackages}: packages that are already installed and would only get
 *       their {@code ref_count} increased (one entry per increase).</li>
 * </ul>
 */
public final class InstallPlan {

    private final String requestedPackage;
    private final String[] addedPackages;
    private final String[] referencedPackages;

    public InstallPlan(String requestedPackage, String[] addedPackages, String[] referencedPackages) {
        this.requestedPackage = requestedPackage;
        this.addedPackages = addedPackages.clone();
        this.referencedPackages = referencedPackages.clone();
    }

    public String getRequestedPackage() {
        return requestedPackage;
    }

    public String[] getAddedPackages() {
        return addedPackages.clone();
    }

    public String[] getReferencedPackages() {
        return referencedPackages.clone();
    }
}
