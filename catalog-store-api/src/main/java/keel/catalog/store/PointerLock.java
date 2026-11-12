package keel.catalog.store;

/** A pointer a commit locks: one it swaps, or one it holds unchanged. */
public sealed interface PointerLock permits PointerSwap, PointerHold {

    PointerKey key();
}
