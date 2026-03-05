package io.github.zzuegg.jbinary;

import io.github.zzuegg.jbinary.accessor.*;
import io.github.zzuegg.jbinary.annotation.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataStoreTest {

    // ------------------------------------------------------------------ records
    record Terrain(
            @BitField(min = 0, max = 255) int height,
            @DecimalField(min = -50.0, max = 50.0, precision = 2) double temperature,
            @BoolField boolean active
    ) {}

    enum BiomeType {
        PLAINS, FOREST, DESERT, OCEAN;
    }

    record BiomeData(
            @EnumField BiomeType biome,
            @BitField(min = 0, max = 100) int fertility
    ) {}

    enum Priority {
        LOW, MEDIUM, HIGH;
    }

    enum Priority2 {
        @EnumCode(1) LOW,
        @EnumCode(5) MEDIUM,
        @EnumCode(9) HIGH;
    }

    record TaskRecord(
            @EnumField(useExplicitCodes = true) Priority2 priority,
            @BitField(min = 0, max = 1000) int score
    ) {}

    record Water(
            @DecimalField(min = 0.0, max = 1.0, precision = 4) double salinity,
            @BoolField boolean frozen
    ) {}

    // ------------------------------------------------------------------ tests

    @Test
    void intFieldRoundTrip() {
        DataStore store = DataStore.of(10, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        height.set(store, 0, 0);
        assertEquals(0, height.get(store, 0));

        height.set(store, 0, 255);
        assertEquals(255, height.get(store, 0));

        height.set(store, 5, 128);
        assertEquals(128, height.get(store, 5));
        assertEquals(255, height.get(store, 0)); // other row unchanged
    }

    @Test
    void intFieldBoundary() {
        DataStore store = DataStore.of(5, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");
        height.set(store, 0, 0);
        height.set(store, 1, 255);
        assertEquals(0,   height.get(store, 0));
        assertEquals(255, height.get(store, 1));
    }

    @Test
    void decimalFieldRoundTrip() {
        DataStore store = DataStore.of(10, Terrain.class);
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");

        temp.set(store, 0, 0.0);
        assertEquals(0.0, temp.get(store, 0), 0.01);

        temp.set(store, 1, -50.0);
        assertEquals(-50.0, temp.get(store, 1), 0.01);

        temp.set(store, 2, 50.0);
        assertEquals(50.0, temp.get(store, 2), 0.01);

        temp.set(store, 3, 23.45);
        assertEquals(23.45, temp.get(store, 3), 0.01);

        temp.set(store, 4, -12.34);
        assertEquals(-12.34, temp.get(store, 4), 0.01);
    }

    @Test
    void boolFieldRoundTrip() {
        DataStore store = DataStore.of(10, Terrain.class);
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        active.set(store, 0, true);
        assertTrue(active.get(store, 0));

        active.set(store, 1, false);
        assertFalse(active.get(store, 1));

        active.set(store, 0, false);
        assertFalse(active.get(store, 0));
    }

    @Test
    void enumFieldOrdinal() {
        DataStore store = DataStore.of(10, BiomeData.class);
        EnumAccessor<BiomeType> biome = Accessors.enumFieldInStore(store, BiomeData.class, "biome");

        biome.set(store, 0, BiomeType.PLAINS);
        assertEquals(BiomeType.PLAINS, biome.get(store, 0));

        biome.set(store, 1, BiomeType.OCEAN);
        assertEquals(BiomeType.OCEAN, biome.get(store, 1));

        biome.set(store, 2, BiomeType.DESERT);
        assertEquals(BiomeType.DESERT, biome.get(store, 2));
    }

    @Test
    void enumFieldExplicitCodes() {
        DataStore store = DataStore.of(5, TaskRecord.class);
        EnumAccessor<Priority2> priority = Accessors.enumFieldInStore(store, TaskRecord.class, "priority");

        priority.set(store, 0, Priority2.LOW);
        assertEquals(Priority2.LOW, priority.get(store, 0));

        priority.set(store, 1, Priority2.HIGH);
        assertEquals(Priority2.HIGH, priority.get(store, 1));

        priority.set(store, 2, Priority2.MEDIUM);
        assertEquals(Priority2.MEDIUM, priority.get(store, 2));
    }

    @Test
    void multipleComponentTypesInOneStore() {
        DataStore store = DataStore.of(100, Terrain.class, Water.class);

        IntAccessor height  = Accessors.intFieldInStore(store, Terrain.class, "height");
        DoubleAccessor temp = Accessors.doubleFieldInStore(store, Terrain.class, "temperature");
        BoolAccessor active = Accessors.boolFieldInStore(store, Terrain.class, "active");

        DoubleAccessor salinity = Accessors.doubleFieldInStore(store, Water.class, "salinity");
        BoolAccessor frozen     = Accessors.boolFieldInStore(store, Water.class, "frozen");

        // Write Terrain fields to row 10
        height.set(store, 10, 100);
        temp.set(store, 10, 20.0);
        active.set(store, 10, true);

        // Write Water fields to row 10
        salinity.set(store, 10, 0.035);
        frozen.set(store, 10, false);

        // Verify Terrain
        assertEquals(100,   height.get(store, 10));
        assertEquals(20.0,  temp.get(store, 10), 0.01);
        assertTrue(active.get(store, 10));

        // Verify Water
        assertEquals(0.035, salinity.get(store, 10), 0.0001);
        assertFalse(frozen.get(store, 10));
    }

    @Test
    void rowIsolation() {
        DataStore store = DataStore.of(5, Terrain.class);
        IntAccessor height = Accessors.intFieldInStore(store, Terrain.class, "height");

        for (int i = 0; i < 5; i++) height.set(store, i, i * 50);
        for (int i = 0; i < 5; i++) assertEquals(i * 50, height.get(store, i));
    }

    @Test
    void bitsSpanWordBoundary() {
        // Force a field to straddle a 64-bit word boundary by creating a component
        // with a large first field and then checking the second field
        record BigFirst(
                @BitField(min = 0, max = (1 << 30) - 1) int big,
                @BitField(min = 0, max = 255) int small
        ) {}
        DataStore store = DataStore.of(5, BigFirst.class);
        IntAccessor big   = Accessors.intFieldInStore(store, BigFirst.class, "big");
        IntAccessor small = Accessors.intFieldInStore(store, BigFirst.class, "small");

        big.set(store, 0, (1 << 30) - 1);
        small.set(store, 0, 200);

        assertEquals((1 << 30) - 1, big.get(store, 0));
        assertEquals(200, small.get(store, 0));
    }

    @Test
    void layoutBuilderBitsRequired() {
        // 0..255 → 8 bits; 0..256 → 9 bits
        assertEquals(8, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(255));
        assertEquals(9, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(256));
        assertEquals(1, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(1));
        assertEquals(1, io.github.zzuegg.jbinary.schema.LayoutBuilder.bitsRequired(0));
    }

    // --------------------------------------------------------------- byte / short / char

    record ByteRecord(
            @BitField(min = -128, max = 127) byte value
    ) {}

    record ShortRecord(
            @BitField(min = -32768, max = 32767) short value
    ) {}

    record CharRecord(
            @BitField(min = 32, max = 126) char value
    ) {}

    @Test
    void byteAccessorRoundTrip() {
        DataStore<?> store = DataStore.packed(10, ByteRecord.class);
        ByteAccessor acc = Accessors.byteFieldInStore(store, ByteRecord.class, "value");

        acc.set(store, 0, (byte) 42);
        assertEquals((byte) 42, acc.get(store, 0));

        acc.set(store, 1, (byte) -1);
        assertEquals((byte) -1, acc.get(store, 1));

        acc.set(store, 2, (byte) -128);
        assertEquals((byte) -128, acc.get(store, 2));

        acc.set(store, 3, (byte) 127);
        assertEquals((byte) 127, acc.get(store, 3));
    }

    @Test
    void shortAccessorRoundTrip() {
        DataStore<?> store = DataStore.packed(10, ShortRecord.class);
        ShortAccessor acc = Accessors.shortFieldInStore(store, ShortRecord.class, "value");

        acc.set(store, 0, (short) 1000);
        assertEquals((short) 1000, acc.get(store, 0));

        acc.set(store, 1, (short) -32768);
        assertEquals((short) -32768, acc.get(store, 1));

        acc.set(store, 2, (short) 32767);
        assertEquals((short) 32767, acc.get(store, 2));
    }

    @Test
    void charAccessorRoundTrip() {
        DataStore<?> store = DataStore.packed(10, CharRecord.class);
        CharAccessor acc = Accessors.charFieldInStore(store, CharRecord.class, "value");

        acc.set(store, 0, 'A');
        assertEquals('A', acc.get(store, 0));

        acc.set(store, 1, 'z');
        assertEquals('z', acc.get(store, 1));

        acc.set(store, 2, ' ');
        assertEquals(' ', acc.get(store, 2));
    }

    // --------------------------------------------------------------- String

    record PlayerRecord(
            @BitField(min = 0, max = 255) int id,
            @StringField(maxLength = 16) String name
    ) {}

    @Test
    void stringAccessorRoundTrip() {
        DataStore<?> store = DataStore.packed(10, PlayerRecord.class);
        StringAccessor acc = Accessors.stringFieldInStore(store, PlayerRecord.class, "name");

        // basic round-trip
        acc.set(store, 0, "Alice");
        assertEquals("Alice", acc.get(store, 0));

        // overwrite with shorter string — old chars must be zeroed
        acc.set(store, 0, "Al");
        assertEquals("Al", acc.get(store, 0));

        // empty string
        acc.set(store, 1, "");
        assertEquals("", acc.get(store, 1));

        // null treated as empty
        acc.set(store, 2, null);
        assertEquals("", acc.get(store, 2));

        // exactly maxLength
        String maxStr = "A".repeat(16);
        acc.set(store, 3, maxStr);
        assertEquals(maxStr, acc.get(store, 3));

        // longer than maxLength — silently truncated
        acc.set(store, 4, "A".repeat(20));
        assertEquals("A".repeat(16), acc.get(store, 4));

        // Unicode BMP characters
        acc.set(store, 5, "\u00e9\u00e0\u00fc");
        assertEquals("\u00e9\u00e0\u00fc", acc.get(store, 5));

        // Supplementary characters (surrogate pairs): each emoji = 2 char slots
        // "\uD83D\uDE00" is U+1F600 (😀); maxLength=16, so 8 emojis fit
        String emoji4 = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"; // 4 emojis = 8 chars
        acc.set(store, 6, emoji4);
        assertEquals(emoji4, acc.get(store, 6));

        // Boundary: exactly 8 emojis = 16 chars (fills maxLength exactly)
        String emoji8 = "\uD83D\uDE00".repeat(8);
        acc.set(store, 7, emoji8);
        assertEquals(emoji8, acc.get(store, 7));

        // 9 emojis = 18 chars; truncated to first 16 chars = 8 emojis
        String emoji9 = "\uD83D\uDE00".repeat(9);
        acc.set(store, 8, emoji9);
        assertEquals(emoji8, acc.get(store, 8));
    }

    @Test
    void stringAccessorStoreUnaware() {
        // Store-unaware accessor (works when store has a single component)
        DataStore<?> store = DataStore.packed(5, PlayerRecord.class);
        StringAccessor acc = Accessors.stringField(PlayerRecord.class, "name");

        acc.set(store, 0, "Bob");
        assertEquals("Bob", acc.get(store, 0));
    }

    record SingleCharRecord(
            @StringField(maxLength = 1) String value
    ) {}

    @Test
    void stringAccessorMaxLengthOne() {
        DataStore<?> store = DataStore.packed(5, SingleCharRecord.class);
        StringAccessor acc = Accessors.stringFieldInStore(store, SingleCharRecord.class, "value");

        acc.set(store, 0, "A");
        assertEquals("A", acc.get(store, 0));

        // Two chars truncated to one
        acc.set(store, 0, "AB");
        assertEquals("A", acc.get(store, 0));

        // Empty string
        acc.set(store, 1, "");
        assertEquals("", acc.get(store, 1));
    }

    @Test
    void rowViewWithString() {
        DataStore<?> store = DataStore.packed(5, PlayerRecord.class);
        RowView<PlayerRecord> view = RowView.of(store, PlayerRecord.class);

        view.set(store, 0, new PlayerRecord(42, "Charlie"));
        PlayerRecord r = view.get(store, 0);
        assertEquals(42, r.id());
        assertEquals("Charlie", r.name());
    }
}
