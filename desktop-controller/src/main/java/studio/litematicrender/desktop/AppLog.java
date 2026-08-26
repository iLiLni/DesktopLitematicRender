package studio.litematicrender.desktop;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AppLog {
  private final Path file;
  private final boolean fullLogs = Boolean.parseBoolean(System.getProperty("lrs.fullLogs", "false"));
  private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

  static Path createSessionFile(Path directory) throws Exception {
    Files.createDirectories(directory);
    String prefix = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
    int sequence = 1;
    Path candidate;
    do {
      candidate = directory.resolve(prefix + String.format("%02d", sequence) + ".log");
      sequence++;
    } while (Files.exists(candidate));
    return candidate;
  }

  AppLog(Path file) {
    this.file = file;
    try {
      Path parent = file.getParent();
      if (parent != null) Files.createDirectories(parent);
      write("INFO", "===== DsLR 日志会话开始 =====", null);
    } catch (Exception ignored) {
    }
  }

  Path file() {
    return file;
  }

  synchronized void info(String message) {
    write("INFO", message, null);
  }

  synchronized void error(String message, Throwable error) {
    write("ERROR", message, error);
  }

  synchronized void event(String source, String event) {
    write("EVENT", source + "\n" + event, null);
  }

  synchronized void appendSource(String label, Path source) {
    if (source == null) return;
    StringBuilder body = new StringBuilder();
    body.append("----- ").append(label).append(" : ").append(source).append(" -----\n");
    try {
      if (Files.isRegularFile(source)) {
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        body.append(fullLogs ? text : compact(text));
        if (body.length() == 0 || body.charAt(body.length() - 1) != '\n') body.append('\n');
      } else {
        body.append("（文件尚未生成）\n");
      }
    } catch (Exception error) {
      body.append("（读取失败：").append(error.getMessage()).append("）\n");
    }
    write("SOURCE", body.toString(), null);
  }

  synchronized void renderFailure(String message, Path... sources) {
    StringBuilder body = new StringBuilder();
    body.append("[渲染诊断] ").append(message).append('\n');
    for (Path source : sources) {
      if (source == null) continue;
      body.append("\n----- ").append(source.getFileName()).append(" -----\n");
      try {
        byte[] bytes = Files.readAllBytes(source);
        String text = new String(bytes, StandardCharsets.UTF_8);
        body.append(fullLogs ? text : compact(text));
        if (body.length() == 0 || body.charAt(body.length() - 1) != '\n') body.append('\n');
      } catch (Exception error) {
        body.append("（未生成或暂时无法读取）\n");
      }
    }
    write("ERROR", body.toString(), null);
  }

  private String compact(String text) {
    if (text == null || text.length() <= 24_000) return text == null ? "" : text;
    StringBuilder selected = new StringBuilder();
    String[] lines = text.split("\\R");
    for (String line : lines) {
      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.contains("error") || lower.contains("exception") || lower.contains("stackoverflow")
        || lower.contains("failed") || lower.contains("crash") || lower.contains("退出")
        || lower.contains("失败") || lower.contains("异常")) {
        selected.append(line).append('\n');
      }
    }
    int tailStart = Math.max(0, text.length() - 6_000);
    selected.append("[其余日志已省略；完整日志请使用 Debug 包。原始长度=")
      .append(text.length()).append(" 字符]\n")
      .append(text.substring(tailStart));
    return selected.toString();
  }

  private void write(String level, String message, Throwable error) {
    try {
      StringBuilder line = new StringBuilder();
      line.append('[').append(format.format(new Date())).append("] [").append(level).append("] ").append(message).append('\n');
      if (error != null) {
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        line.append(stack.toString());
      }
      Files.write(file, line.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception failure) {
      try { System.err.println("DsLR 日志写入失败：" + failure.getMessage()); } catch (Exception ignored) { }
    }
  }
}
