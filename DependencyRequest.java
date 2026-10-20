public final class DependencyRequest {

    private final String packageName;
    private final DependencyRequest requestedBy;

    public DependencyRequest(String packageName, DependencyRequest requestedBy) {
        this.packageName = packageName;
        this.requestedBy = requestedBy;
    }

    public String getPackageName() {
        return packageName;
    }

    public boolean isPartOfCycle() {
        for (DependencyRequest ancestor = requestedBy; ancestor != null; ancestor = ancestor.requestedBy) {
            if (ancestor.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    public String describeChain() {
        LinkedStack<String> names = new LinkedStack<>();
        for (DependencyRequest request = this; request != null; request = request.requestedBy) {
            names.push(request.packageName);
        }
        StringBuilder text = new StringBuilder();
        while (!names.isEmpty()) {
            if (text.length() > 0) {
                text.append(" -> ");
            }
            text.append(names.pop());
        }
        return text.toString();
    }
}
