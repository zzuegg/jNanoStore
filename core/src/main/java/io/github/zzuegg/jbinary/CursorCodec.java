package io.github.zzuegg.jbinary;

/**
 * Compiled load/flush strategy for a {@link DataCursor}.
 *
 * <p>The default implementation wraps the original {@link java.lang.invoke.VarHandle}-based
 * binding array. When {@link CursorCodecGenerator} successfully generates a bytecode-compiled
 * class, that class takes over and uses direct {@code PUTFIELD}/{@code GETFIELD} instructions
 * instead — eliminating VarHandle dispatch overhead.
 */
interface CursorCodec {
    /** Reads all mapped fields from the store row into the cursor instance. */
    void load(Object inst, DataStore<?> store, int row);

    /** Writes all mapped fields from the cursor instance back into the store row. */
    void flush(Object inst, DataStore<?> store, int row);
}
