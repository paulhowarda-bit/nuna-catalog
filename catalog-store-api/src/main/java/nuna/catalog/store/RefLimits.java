package nuna.catalog.store;

/**
 * P11's limits on a catalog's refs (D33): how many open branches and how many tags it may hold. A branch is open until
 * its expiry; a tag never expires, so every tag counts. The server reads them from its settings (item 106); the store
 * checks them in the create's transaction, under a lock on the catalog's refs, so two creates cannot pass a limit
 * together.
 */
public record RefLimits(int maxBranches, int maxTags) {

    /** P11's defaults: 50 open branches and 1,000 tags per catalog. */
    public static final RefLimits P11 = new RefLimits(50, 1_000);

    public RefLimits {
        if (maxBranches < 0 || maxTags < 0) {
            throw new IllegalArgumentException("a limit is 0 or more, got " + maxBranches + " branches and "
                    + maxTags + " tags");
        }
    }

    /** The limit on refs of {@code kind}. */
    public int of(Ref.Kind kind) {
        return kind == Ref.Kind.BRANCH ? maxBranches : maxTags;
    }

    /** Whether a catalog that holds {@code held} open refs of {@code kind} may make one more. */
    public boolean admitsAnother(Ref.Kind kind, long held) {
        return held < of(kind);
    }
}
