package studio.litematicrender.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkerSession {
  final String id;
  final Path directory;
  final Path commands;
  final Path events;
  final Path manifest;
  final Path job;

  private WorkerSession(String id, Path directory) {
    this.id = id;
    this.directory = directory;
    this.commands = directory.resolve("commands.jsonl");
    this.events = directory.resolve("events.jsonl");
    this.manifest = directory.resolve("manifest.json");
    this.job = directory.resolve("job.json");
  }

  static WorkerSession createProbe(AppPaths paths, MinecraftInstance instance) throws Exception {
    String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS").format(new Date());
    String id = stamp + "-" + UUID.randomUUID().toString();
    Path directory = paths.sessions.resolve(id);
    Files.createDirectories(directory);
    WorkerSession session = new WorkerSession(id, directory);

    Map<String, Object> configure = new LinkedHashMap<String, Object>();
    configure.put("protocolVersion", Long.valueOf(1));
    configure.put("type", "configure_instance");
    configure.put("instancePath", instance.root.toString());
    configure.put("minecraftVersion", instance.selectedVersion);
    Map<String, Object> reload = new LinkedHashMap<String, Object>();
    reload.put("protocolVersion", Long.valueOf(1));
    reload.put("type", "reload_resources");

    String commandText = Json.stringify(configure) + "\n" + Json.stringify(reload) + "\n";
    Files.write(session.commands, commandText.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    Files.write(session.events, new byte[0], StandardOpenOption.CREATE_NEW);

    Map<String, Object> manifest = new LinkedHashMap<String, Object>();
    manifest.put("schemaVersion", Long.valueOf(1));
    manifest.put("id", id);
    manifest.put("kind", "fabric_bridge_probe");
    manifest.put("status", "waiting_for_fabric_worker");
    manifest.put("createdAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
    manifest.put("instancePath", instance.root.toString());
    manifest.put("minecraftVersion", instance.selectedVersion);
    manifest.put("expectedOutputs", new ArrayList<Object>());
    Json.write(session.manifest, manifest);
    return session;
  }

  static WorkerSession createRender(AppPaths paths, MinecraftInstance instance, Map<String, Object> job) throws Exception {
    String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS").format(new Date());
    String id = stamp + "-" + UUID.randomUUID().toString();
    Path directory = paths.sessions.resolve(id);
    Files.createDirectories(directory);
    WorkerSession session = new WorkerSession(id, directory);

    Map<String, Object> configure = new LinkedHashMap<String, Object>();
    configure.put("protocolVersion", Long.valueOf(1));
    configure.put("type", "configure_instance");
    configure.put("instancePath", instance.root.toString());
    configure.put("minecraftVersion", instance.selectedVersion);
    ArrayList<Map<String, Object>> commands = new ArrayList<Map<String, Object>>();
    commands.add(configure);
    Map<String, Object> resources = object(job.get("resources"));
    int priority = 100;
    for (Object rawPath : array(resources.get("selectedResourcePacks"))) {
      if (!(rawPath instanceof String) || ((String) rawPath).trim().isEmpty()) continue;
      Map<String, Object> add = new LinkedHashMap<String, Object>();
      add.put("protocolVersion", Long.valueOf(1));
      add.put("type", "add_resource_pack");
      add.put("path", ((String) rawPath).trim());
      add.put("priority", Long.valueOf(priority--));
      commands.add(add);
    }
    Map<String, Object> reload = new LinkedHashMap<String, Object>();
    reload.put("protocolVersion", Long.valueOf(1));
    reload.put("type", "reload_resources");
    commands.add(reload);
    Map<String, Object> submit = new LinkedHashMap<String, Object>();
    submit.put("protocolVersion", Long.valueOf(1));
    submit.put("type", "submit_render_job");
    submit.put("job", job);
    commands.add(submit);
    StringBuilder commandText = new StringBuilder();
    for (Map<String, Object> command : commands) commandText.append(Json.stringify(command)).append('\n');
    Files.write(session.commands, commandText.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    Files.write(session.events, new byte[0], StandardOpenOption.CREATE_NEW);
    Json.write(session.job, job);

    Map<String, Object> manifest = new LinkedHashMap<String, Object>();
    manifest.put("schemaVersion", Long.valueOf(1));
    manifest.put("id", id);
    manifest.put("kind", "fabric_png_render");
    manifest.put("status", "waiting_for_fabric_worker");
    manifest.put("createdAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
    manifest.put("instancePath", instance.root.toString());
    manifest.put("minecraftVersion", instance.selectedVersion);
    Map<String, Object> output = object(job.get("output"));
    manifest.put("outputDirectory", output.get("directory"));
    manifest.put("expectedOutputs", new ArrayList<Object>());
    Json.write(session.manifest, manifest);
    return session;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> lastEvent() {
    try {
      List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
      for (int index = lines.size() - 1; index >= 0; index--) {
        String line = lines.get(index).trim();
        if (line.isEmpty()) continue;
        Object value = Json.parse(line);
        if (value instanceof Map) return (Map<String, Object>) value;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  synchronized void appendFailure(String message, String stage) {
    Map<String, Object> last = lastEvent();
    Object lastType = last == null ? null : last.get("type");
    if ("completed".equals(lastType) || "failed".equals(lastType)) return;
    try {
      Map<String, Object> event = new LinkedHashMap<String, Object>();
      event.put("protocolVersion", Long.valueOf(1));
      event.put("type", "failed");
      event.put("stage", stage);
      event.put("message", message);
      Files.write(events, (Json.stringify(event) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    } catch (Exception ignored) {
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value) {
    return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
  }
}
