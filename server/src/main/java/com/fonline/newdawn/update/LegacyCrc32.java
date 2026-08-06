package com.fonline.newdawn.update;

public final class LegacyCrc32 {
    private static final int CRC_POLY = 0xEDB88320;
    private static final int CRC_MASK = 0xD202EF8D;
    private static final int[] TABLE = createTable();

    private int value;

    public void update(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException("Invalid CRC input range.");
        }
        for (int index = offset; index < offset + length; index += 1) {
            value = TABLE[(value ^ bytes[index]) & 0xff] ^ (value >>> 8);
            value ^= CRC_MASK;
        }
    }

    public int value() {
        return value;
    }

    private static int[] createTable() {
        int[] table = new int[256];
        for (int index = 0; index < table.length; index += 1) {
            int result = index;
            for (int bit = 0; bit < 8; bit += 1) {
                result = (result & 1) != 0 ? (result >>> 1) ^ CRC_POLY : result >>> 1;
            }
            table[index] = result;
        }
        return table;
    }
}
