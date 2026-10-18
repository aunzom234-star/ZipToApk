package com.zip2apk.builder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Replaces whole strings inside the string pool of a compiled (binary) AndroidManifest.xml.
 * Strings keep their index, so every reference (attribute values, resource map) stays valid.
 */
public final class AxmlPatcher {
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int UTF8_FLAG = 0x100;
    private static final int SORTED_FLAG = 0x1;

    private AxmlPatcher() { }

    public static byte[] replaceStrings(byte[] src, Map<String, String> repl) {
        ByteBuffer b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xFFFF) != RES_XML_TYPE) {
            throw new IllegalArgumentException("Not a binary XML file");
        }
        int pool = b.getShort(2) & 0xFFFF;                       // file header size (8)
        if ((b.getShort(pool) & 0xFFFF) != RES_STRING_POOL_TYPE) {
            throw new IllegalArgumentException("String pool not found");
        }
        int headerSize = b.getShort(pool + 2) & 0xFFFF;          // normally 28
        int poolSize = b.getInt(pool + 4);
        int stringCount = b.getInt(pool + 8);
        int styleCount = b.getInt(pool + 12);
        int flags = b.getInt(pool + 16);
        int stringsStart = b.getInt(pool + 20);
        int stylesStart = b.getInt(pool + 24);
        boolean utf8 = (flags & UTF8_FLAG) != 0;

        int offsetsPos = pool + headerSize;
        String[] strs = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int off = b.getInt(offsetsPos + 4 * i);
            strs[i] = readString(b, pool + stringsStart + off, utf8);
        }

        boolean changed = false;
        for (int i = 0; i < stringCount; i++) {
            String r = repl.get(strs[i]);
            if (r != null) {
                strs[i] = r;
                changed = true;
            }
        }
        if (!changed) {
            throw new IllegalStateException("Placeholder strings not found in manifest");
        }

        byte[] styleOffsets = new byte[4 * styleCount];
        System.arraycopy(src, offsetsPos + 4 * stringCount, styleOffsets, 0, styleOffsets.length);
        byte[] styleData = new byte[0];
        if (styleCount > 0) {
            styleData = new byte[poolSize - stylesStart];
            System.arraycopy(src, pool + stylesStart, styleData, 0, styleData.length);
        }

        ByteArrayOutputStream sd = new ByteArrayOutputStream();
        int[] newOffsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            newOffsets[i] = sd.size();
            writeString(sd, strs[i], utf8);
        }
        while (sd.size() % 4 != 0) sd.write(0);

        int newStringsStart = headerSize + 4 * stringCount + 4 * styleCount;
        int newStylesStart = styleCount > 0 ? newStringsStart + sd.size() : 0;
        int newPoolSize = newStringsStart + sd.size() + styleData.length;

        ByteBuffer out = ByteBuffer.allocate(src.length - poolSize + newPoolSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(src, 0, pool);                                   // file header
        out.putShort((short) RES_STRING_POOL_TYPE);
        out.putShort((short) headerSize);
        out.putInt(newPoolSize);
        out.putInt(stringCount);
        out.putInt(styleCount);
        out.putInt(flags & ~SORTED_FLAG);
        out.putInt(newStringsStart);
        out.putInt(newStylesStart);
        if (headerSize > 28) out.put(src, pool + 28, headerSize - 28);
        for (int o : newOffsets) out.putInt(o);
        out.put(styleOffsets);
        out.put(sd.toByteArray());
        out.put(styleData);
        out.put(src, pool + poolSize, src.length - pool - poolSize);  // rest of the file
        out.putInt(4, out.capacity());                           // new total file size
        return out.array();
    }

    // ---------------------------------------------------------------- reading
    private static String readString(ByteBuffer b, int pos, boolean utf8) {
        byte[] raw = b.array();
        if (utf8) {
            int v = b.get(pos) & 0xFF;                           // length in UTF-16 units (skipped)
            pos += (v & 0x80) != 0 ? 2 : 1;
            int n = b.get(pos) & 0xFF;                           // length in bytes
            if ((n & 0x80) != 0) {
                n = ((n & 0x7F) << 8) | (b.get(pos + 1) & 0xFF);
                pos += 2;
            } else {
                pos += 1;
            }
            return new String(raw, pos, n, StandardCharsets.UTF_8);
        }
        int len = b.getShort(pos) & 0xFFFF;
        pos += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | (b.getShort(pos) & 0xFFFF);
            pos += 2;
        }
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = b.getChar(pos + 2 * i);
        return new String(c);
    }

    // ---------------------------------------------------------------- writing
    private static void writeString(ByteArrayOutputStream o, String s, boolean utf8) {
        if (utf8) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeLen8(o, s.length());
            writeLen8(o, bytes.length);
            o.write(bytes, 0, bytes.length);
            o.write(0);
        } else {
            int len = s.length();
            if (len > 0x7FFF) {
                writeU16(o, (len >> 16) | 0x8000);
                writeU16(o, len & 0xFFFF);
            } else {
                writeU16(o, len);
            }
            for (int i = 0; i < len; i++) writeU16(o, s.charAt(i));
            writeU16(o, 0);
        }
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7F) {
            o.write((len >> 8) | 0x80);
            o.write(len & 0xFF);
        } else {
            o.write(len);
        }
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }
}
