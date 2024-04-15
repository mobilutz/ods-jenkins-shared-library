package org.ods.util.cache

/**
 * Marks a cache implementation as supporting a bounded number of entries.
 *
 * The effective bound may be lower than specified by the user, if the underlying implementation does not support
 * those many entries.
 *
 * For some concurrent implementations, when their {@code Cache.size()} method returns approximate values,
 * the bound may not be strictly respected, but on a best-effort basis.
 */
interface Bounded {

    /**
     * Returns the maximum allowed number of entries, or zero, if unbounded.
     *
     * The effective bound may be lower, if the implementation does not support those many entries.
     *
     * @return the maximum size, or zero, if unbounded.
     */
    long maxSize()

}
