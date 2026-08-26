package studio.litematicrender.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
  private Json() {
  }

  static Object parse(String source) {
    Parser parser = new Parser(source);
    Object value = parser.value();
    parser.space();
    if (!parser.end()) throw new IllegalArgumentException("JSON 尾部存在多余内容。");
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readObject(Path path) throws Exception {
    Object parsed = parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    if (!(parsed instanceof Map)) throw new IllegalArgumentException("JSON 根节点不是对象：" + path);
    return (Map<String, Object>) parsed;
  }

  static void write(Path path, Object value) throws Exception {
    Files.createDirectories(path.getParent());
    Files.write(path, (stringify(value) + "\n").getBytes(StandardCharsets.UTF_8));
  }

  static String stringify(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static void append(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      quote(out, (String) value);
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value.toString());
    } else if (value instanceof Map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
        if (!first) out.append(',');
        first = false;
        quote(out, entry.getKey());
        out.append(':');
        append(out, entry.getValue());
      }
      out.append('}');
    } else if (value instanceof Iterable) {
      out.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>) value) {
        if (!first) out.append(',');
        first = false;
        append(out, item);
      }
      out.append(']');
    } else {
      quote(out, value.toString());
    }
  }

  private static void quote(StringBuilder out, String value) {
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"': out.append("\\\""); break;
        case '\\': out.append("\\\\"); break;
        case '\b': out.append("\\b"); break;
        case '\f': out.append("\\f"); break;
        case '\n': out.append("\\n"); break;
        case '\r': out.append("\\r"); break;
        case '\t': out.append("\\t"); break;
        default:
          if (character < 32) out.append(String.format("\\u%04x", (int) character));
          else out.append(character);
      }
    }
    out.append('"');
  }

  private static final class Parser {
    private final String source;
    private int index;

    Parser(String source) {
      this.source = source == null ? "" : source;
    }

    boolean end() {
      return index >= source.length();
    }

    void space() {
      while (!end() && Character.isWhitespace(source.charAt(index))) index++;
    }

    Object value() {
      space();
      if (end()) throw fail("JSON 提前结束。");
      char current = source.charAt(index);
      if (current == '{') return object();
      if (current == '[') return array();
      if (current == '"') return string();
      if (current == 't') return literal("true", Boolean.TRUE);
      if (current == 'f') return literal("false", Boolean.FALSE);
      if (current == 'n') return literal("null", null);
      return number();
    }

    private Map<String, Object> object() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
      index++;
      space();
      if (take('}')) return result;
      while (true) {
        space();
        if (end() || source.charAt(index) != '"') throw fail("JSON 对象键必须是字符串。");
        String key = string();
        space();
        if (!take(':')) throw fail("JSON 对象缺少冒号。");
        result.put(key, value());
        space();
        if (take('}')) return result;
        if (!take(',')) throw fail("JSON 对象缺少逗号。");
      }
    }

    private List<Object> array() {
      ArrayList<Object> result = new ArrayList<Object>();
      index++;
      space();
      if (take(']')) return result;
      while (true) {
        result.add(value());
        space();
        if (take(']')) return result;
        if (!take(',')) throw fail("JSON 数组缺少逗号。");
      }
    }

    private String string() {
      StringBuilder result = new StringBuilder();
      index++;
      while (!end()) {
        char current = source.charAt(index++);
        if (current == '"') return result.toString();
        if (current != '\\') {
          result.append(current);
          continue;
        }
        if (end()) throw fail("JSON 转义序列不完整。");
        char escaped = source.charAt(index++);
        switch (escaped) {
          case '"': result.append('"'); break;
          case '\\': result.append('\\'); break;
          case '/': result.append('/'); break;
          case 'b': result.append('\b'); break;
          case 'f': result.append('\f'); break;
          case 'n': result.append('\n'); break;
          case 'r': result.append('\r'); break;
          case 't': result.append('\t'); break;
          case 'u':
            if (index + 4 > source.length()) throw fail("JSON Unicode 转义不完整。");
            result.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
            index += 4;
            break;
          default: throw fail("JSON 转义字符无效。");
        }
      }
      throw fail("JSON 字符串未结束。");
    }

    private Object number() {
      int start = index;
      if (peek('-')) index++;
      while (!end() && Character.isDigit(source.charAt(index))) index++;
      boolean decimal = false;
      if (peek('.')) {
        decimal = true;
        index++;
        while (!end() && Character.isDigit(source.charAt(index))) index++;
      }
      if (peek('e') || peek('E')) {
        decimal = true;
        index++;
        if (peek('+') || peek('-')) index++;
        while (!end() && Character.isDigit(source.charAt(index))) index++;
      }
      String raw = source.substring(start, index);
      try {
        return decimal ? Double.valueOf(raw) : Long.valueOf(raw);
      } catch (NumberFormatException error) {
        throw fail("JSON 数字无效。");
      }
    }

    private Object literal(String literal, Object value) {
      if (!source.startsWith(literal, index)) throw fail("JSON 字面量无效。");
      index += literal.length();
      return value;
    }

    private boolean take(char expected) {
      if (!end() && source.charAt(index) == expected) {
        index++;
        return true;
      }
      return false;
    }

    private boolean peek(char expected) {
      return !end() && source.charAt(index) == expected;
    }

    private IllegalArgumentException fail(String message) {
      return new IllegalArgumentException(message + " 位置：" + index);
    }
  }
}
