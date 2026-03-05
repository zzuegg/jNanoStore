package io.github.zzuegg.jbinary.octree;

/**
 * Determines whether 8 sibling octree child nodes can be collapsed into their parent.
 *
 * <p>A {@code CollapsingFunction} is registered <em>per component type</em> when building
 * an {@link OctreeDataStore}.  After each write, the store checks whether all 8 sibling
 * nodes of the modified leaf satisfy every registered collapsing function; if they do,
 * the 8 children are removed and a single representative node is stored at the parent
 * level instead.  This process repeats up the tree.</p>
 *
 * <h2>Function parameters</h2>
 * <ul>
 *   <li>{@code componentBitOffset} — absolute bit offset of this component within a row</li>
 *   <li>{@code componentBits}      — total number of bits this component occupies</li>
 *   <li>{@code rowStrideLongs}     — number of {@code long} words per row</li>
 *   <li>{@code children}           — exactly 8 {@code long[]} row-data arrays, one per octant
 *       (0-7); a {@code null} entry means the node was never written and should be
 *       treated as all-zeros</li>
 * </ul>
 *
 * <h2>Factory methods</h2>
 * <ul>
 *   <li>{@link #equalBits()} — collapse only when all 8 children are bit-for-bit identical
 *       in this component's bit range (the default)</li>
 *   <li>{@link #never()} — never collapse</li>
 *   <li>{@link #always()} — always collapse (stores child[0]'s data at the parent)</li>
 * </ul>
 *
 * <p>When a collapse occurs the parent node receives the value of the first non-{@code null}
 * child as its representative.  For the common {@link #equalBits()} case all children are
 * equal so any one is a valid representative.</p>
 */
@FunctionalInterface
public interface CollapsingFunction {

    /**
     * Returns {@code true} if the 8 sibling child nodes may be collapsed into their parent.
     *
     * @param componentBitOffset  absolute bit offset of this component within a row
     * @param componentBits       total bits this component occupies in the row
     * @param rowStrideLongs      number of {@code long} words per row
     * @param children            array of exactly 8 {@code long[]} row-data arrays;
     *                            {@code null} entries represent never-written (all-zero) nodes
     * @return {@code true} to collapse; {@code false} to keep children separate
     */
    boolean canCollapse(int componentBitOffset, int componentBits,
                        int rowStrideLongs, long[][] children);

    // -----------------------------------------------------------------------
    // Factory methods

    /**
     * Returns a {@link CollapsingFunction} that collapses nodes only when all 8 children
     * are bit-for-bit identical in this component's bit range.
     * This is the default used when no function is explicitly provided.
     */
    static CollapsingFunction equalBits() {
        return (offset, bits, stride, children) -> {
            long[] ref = children[0];
            for (int i = 1; i < 8; i++) {
                if (!bitsEqual(ref, children[i], offset, bits)) return false;
            }
            return true;
        };
    }

    /** Returns a {@link CollapsingFunction} that never collapses (always keeps children). */
    static CollapsingFunction never() {
        return (offset, bits, stride, children) -> false;
    }

    /**
     * Returns a {@link CollapsingFunction} that always collapses, using the first non-null
     * child's data as the representative value at the parent.
     */
    static CollapsingFunction always() {
        return (offset, bits, stride, children) -> true;
    }

    // -----------------------------------------------------------------------
    // Package-private helper shared with OctreeDataStore

    /**
     * Compares the bits in the range {@code [bitOffset, bitOffset+bitWidth)} of two row
     * arrays.  Either array may be {@code null} (treated as all-zeros).
     */
    static boolean bitsEqual(long[] a, long[] b, int bitOffset, int bitWidth) {
        if (a == b) return true;          // same reference (includes null == null)
        if (bitWidth == 0) return true;
        int firstWord = bitOffset >>> 6;
        int lastWord  = (bitOffset + bitWidth - 1) >>> 6;
        for (int w = firstWord; w <= lastWord; w++) {
            long av = (a == null) ? 0L : a[w];
            long bv = (b == null) ? 0L : b[w];
            if (av == bv) continue;
            // Build a mask covering the relevant bits in this word
            int lo = Math.max(bitOffset - w * 64, 0);
            int hi = Math.min(bitOffset + bitWidth - w * 64, 64);
            int width = hi - lo;
            long mask = (width == 64) ? -1L : ((1L << width) - 1L) << lo;
            if ((av & mask) != (bv & mask)) return false;
        }
        return true;
    }
}
