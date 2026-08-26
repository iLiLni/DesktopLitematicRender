package studio.litematicrender.worker;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerBridge {
  private WorkerBridge() {}

  static void start() {
    String rawSession = System.getProperty("lrs.session", "").trim();
    if (rawSession.isEmpty()) return;
    Path session = Path.of(rawSession).toAbsolutePath().normalize();
    Path commands = session.resolve("commands.jsonl");
    if (!Files.isRegularFile(commands)) {
      append(session, "failed", "未找到 Fabric Worker 命令流。", "bootstrap");
      return;
    }
    try {
      Map<String, Object> renderJob = renderCommand(commands);
      append(session, "ready", "Fabric PNG Renderer 已载入。", "bootstrap");
      if (renderJob == null) return;
      append(session, "resources_reloaded", "已确认默认 Minecraft Jar 与所选资源包路径；渲染时会逐方块面解析贴图。", "resources");
      startRender(session, renderJob);
    } catch (Exception error) {
      append(session, "failed", readable(error), "bootstrap");
      writeError(session, error);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> renderCommand(Path commands) throws Exception {
    List<String> lines = Files.readAllLines(commands, StandardCharsets.UTF_8);
    for (String raw : lines) {
      String line = raw.trim();
      if (line.isEmpty()) continue;
      Object value = WorkerJson.parse(line);
      if (!(value instanceof Map)) continue;
      Map<String, Object> command = (Map<String, Object>) value;
      if (!"submit_render_job".equals(WorkerJson.string(command.get("type")))) continue;
      Map<String, Object> job = WorkerJson.object(command.get("job"));
      if (job.isEmpty()) throw new IllegalArgumentException("submit_render_job 未携带 job。 ");
      return job;
    }
    return null;
  }

  private static void startRender(final Path session, final Map<String, Object> job) {
    Thread thread = new Thread(new Runnable() {
      public void run() {
        try {
          append(session, "progress", "正在初始化 PNG 渲染…", "prepare", 0.01d, null);
          String rawClientJar = System.getProperty("lrs.clientJar", "").trim();
          Path clientJar = rawClientJar.isEmpty() ? null : Path.of(rawClientJar).toAbsolutePath().normalize();
          List<Path> outputs = VoxelRenderer.render(job, clientJar, new VoxelRenderer.Progress() {
            public void report(String stage, String message, double fraction) {
              append(session, "progress", message, stage, fraction, null);
            }
          });
          ArrayList<Object> paths = new ArrayList<Object>();
          for (Path output : outputs) paths.add(output.toString());
          append(session, "completed", "PNG 渲染完成，共输出 " + outputs.size() + " 个文件。", "completed", 1d, paths);
          System.exit(0);
        } catch (Throwable error) {
          append(session, "failed", readable(error), "render", null, null);
          writeError(session, error);
          System.exit(1);
        }
      }
    }, "lrs-png-renderer");
    thread.setDaemon(true);
    thread.start();
  }

  private static synchronized void append(Path session, String type, String message, String stage) {
    append(session, type, message, stage, null, null);
  }

  private static synchronized void append(Path session, String type, String message, String stage, Double fraction, List<Object> outputs) {
    try {
      Map<String, Object> event = new LinkedHashMap<String, Object>();
      event.put("protocolVersion", Long.valueOf(1));
      event.put("type", type);
      event.put("message", message);
      event.put("stage", stage);
      if (fraction != null) event.put("fraction", fraction);
      if (outputs != null) event.put("outputs", outputs);
      Files.writeString(session.resolve("events.jsonl"), WorkerJson.stringify(event) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception ignored) {
    }
  }

  private static void writeError(Path session, Throwable error) {
    try {
      StringWriter text = new StringWriter();
      error.printStackTrace(new PrintWriter(text));
      Files.writeString(session.resolve("worker-render-error.log"), text.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (Exception ignored) {
    }
  }

  private static String readable(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) current = current.getCause();
    String message = current.getMessage();
    return message == null || message.trim().isEmpty() ? current.toString() : message;
  }
}
