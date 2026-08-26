package studio.litematicrender.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerJson {
  private WorkerJson() {
  }

  static Object parse(String source) {
    Parser parser = new Parser(source == null ? "" : source);
    Object value = parser.value();
    parser.space();
    if (!parser.end()) throw new IllegalArgumentException("JSON contains trailing data");
    return value;
  }

  static String stringify(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

  @SuppressWarnings("unchecked")
  static List<Object> array(Object value) {
    return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
  }

  static String string(Object value) {
    return value instanceof String ? (String) value : "";
  }

  static int integer(Object value, int fallback) {
    return value instanceof Number ? ((Number) value).intValue() : fallback;
  }

  static double decimal(Object value, double fallback) {
    return value instanceof Number ? ((Number) value).doubleValue() : fallback;
  }

  @SuppressWarnings("unchecked")
  private static void append(StringBuilder out, Object value) {
    if (value == null) out.append("null");
    else if (value instanceof String) quote(out, (String) value);
    else if (value instanceof Number || value instanceof Boolean) out.append(value);
    else if (value instanceof Map) {
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
    } else quote(out, String.valueOf(value));
  }

  private static void quote(StringBuilder out, String value) {
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (c == '"') out.append("\\\"");
      else if (c == '\\') out.append("\\\\");
      else if (c == '\n') out.append("\\n");
      else if (c == '\r') out.append("\\r");
      else if (c == '\t') out.append("\\t");
      else if (c < 32) out.append(String.format("\\u%04x", (int) c));
      else out.append(c);
    }
    out.append('"');
  }

  private static final class Parser {
    private final String source;
    private int index;

    Parser(String source) {
      this.source = source;
    }

    boolean end() {
      return index >= source.length();
    }

    void space() {
      while (!end() && Character.isWhitespace(source.charAt(index))) index++;
    }

    Object value() {
      space();
      if (end()) throw fail();
      char c = source.charAt(index);
      if (c == '{') return object();
      if (c == '[') return array();
      if (c == '"') return string();
      if (c == 't') return literal("true", Boolean.TRUE);
      if (c == 'f') return literal("false", Boolean.FALSE);
      if (c == 'n') return literal("null", null);
      return number();
    }

    private Map<String, Object> object() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
      index++;
      space();
      if (take('}')) return result;
      while (true) {
        space();
        if (end() || source.charAt(index) != '"') throw fail();
        String key = string();
        space();
        if (!take(':')) throw fail();
        result.put(key, value());
        space();
        if (take('}')) return result;
        if (!take(',')) throw fail();
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
        if (!take(',')) throw fail();
      }
    }

    private String string() {
      StringBuilder result = new StringBuilder();
      index++;
      while (!end()) {
        char c = source.charAt(index++);
        if (c == '"') return result.toString();
        if (c != '\\') {
          result.append(c);
          continue;
        }
        if (end()) throw fail();
        char escape = source.charAt(index++);
        if (escape == '"') result.append('"');
        else if (escape == '\\') result.append('\\');
        else if (escape == '/') result.append('/');
        else if (escape == 'b') result.append('\b');
        else if (escape == 'f') result.append('\f');
        else if (escape == 'n') result.append('\n');
        else if (escape == 'r') result.append('\r');
        else if (escape == 't') result.append('\t');
        else if (escape == 'u') {
          if (index + 4 > source.length()) throw fail();
          result.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
          index += 4;
        } else throw fail();
      }
      throw fail();
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
        throw fail();
      }
    }

    private Object literal(String expected, Object value) {
      if (!source.startsWith(expected, index)) throw fail();
      index += expected.length();
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

    private IllegalArgumentException fail() {
      return new IllegalArgumentException("Invalid JSON at " + index);
    }
  }
}
