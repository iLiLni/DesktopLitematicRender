package studio.litematicrender.worker;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class NbtReader {
  private NbtReader() {
  }

  static Map<String, Object> read(Path path) throws Exception {
    Exception first = null;
    try {
      return readStream(new GZIPInputStream(Files.newInputStream(path)));
    } catch (Exception error) {
      first = error;
    }
    try {
      return readStream(new InflaterInputStream(Files.newInputStream(path)));
    } catch (Exception ignored) {
    }
    try {
      return readStream(Files.newInputStream(path));
    } catch (Exception error) {
      throw new IllegalArgumentException("无法读取 Litematic NBT：" + path, first == null ? error : first);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readStream(InputStream raw) throws Exception {
    DataInputStream input = new DataInputStream(new BufferedInputStream(raw));
    try {
      int rootType = input.readUnsignedByte();
      if (rootType != 10) throw new IllegalArgumentException("NBT 根标签不是 Compound。");
      input.readUTF();
      Object root = payload(input, rootType);
      if (!(root instanceof Map)) throw new IllegalArgumentException("NBT 根标签无效。");
      return (Map<String, Object>) root;
    } finally {
      input.close();
    }
  }

  private static Object payload(DataInputStream input, int type) throws Exception {
    switch (type) {
      case 1: return Byte.valueOf(input.readByte());
      case 2: return Short.valueOf(input.readShort());
      case 3: return Integer.valueOf(input.readInt());
      case 4: return Long.valueOf(input.readLong());
      case 5: return Float.valueOf(input.readFloat());
      case 6: return Double.valueOf(input.readDouble());
      case 7: {
        int length = input.readInt();
        byte[] values = new byte[length];
        input.readFully(values);
        return values;
      }
      case 8: return input.readUTF();
      case 9: {
        int itemType = input.readUnsignedByte();
        int length = input.readInt();
        ArrayList<Object> values = new ArrayList<Object>(Math.max(0, length));
        for (int index = 0; index < length; index++) values.add(payload(input, itemType));
        return values;
      }
      case 10: {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        while (true) {
          int itemType = input.readUnsignedByte();
          if (itemType == 0) return values;
          values.put(input.readUTF(), payload(input, itemType));
        }
      }
      case 11: {
        int length = input.readInt();
        int[] values = new int[length];
        for (int index = 0; index < length; index++) values[index] = input.readInt();
        return values;
      }
      case 12: {
        int length = input.readInt();
        long[] values = new long[length];
        for (int index = 0; index < length; index++) values[index] = input.readLong();
        return values;
      }
      default: throw new IllegalArgumentException("不支持的 NBT 标签类型：" + type);
    }
  }
}
