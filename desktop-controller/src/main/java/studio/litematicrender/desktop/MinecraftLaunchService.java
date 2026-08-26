package studio.litematicrender.desktop;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MinecraftLaunchService implements AutoCloseable {
  private static final String VERSION = "1.0.0";
  private static final String WORKER_JAR = "litematic-render-worker-1.0.0-unified-entity-frame.jar";
  static final class LaunchResult {
    final Process process;
    final long pid;
    final Path workerRoot;
    final Path launchLog;
    final Path minecraftLog;
    final JavaRuntimeLocator.RuntimeInfo runtime;

    LaunchResult(Process process, long pid, Path workerRoot, Path launchLog, Path minecraftLog, JavaRuntimeLocator.RuntimeInfo runtime) {
      this.process = process;
      this.pid = pid;
      this.workerRoot = workerRoot;
      this.launchLog = launchLog;
      this.minecraftLog = minecraftLog;
      this.runtime = runtime;
    }
  }

  private static final class ResolvedVersion {
    final Map<String, Object> descriptor;
    final Path descriptorPath;
    final List<String> inherited;

    ResolvedVersion(Map<String, Object> descriptor, Path descriptorPath, List<String> inherited) {
      this.descriptor = descriptor;
      this.descriptorPath = descriptorPath;
      this.inherited = inherited;
    }
  }

  private final AppPaths paths;
  private final AppLog log;
  private final List<Process> owned = Collections.synchronizedList(new ArrayList<Process>());

  MinecraftLaunchService(AppPaths paths, AppLog log) {
    this.paths = paths;
    this.log = log;
  }

  LaunchResult startWorker(MinecraftInstance instance, WorkerSession session, JavaRuntimeLocator.RuntimeInfo runtime) throws Exception {
    if (runtime == null || runtime.major < 25) throw new IllegalArgumentException("未找到 Java 25 或更新版本，无法启动 Minecraft 26.1.2。");
    if (!instance.hasSelectedVersionJson()) throw new IllegalArgumentException("目标版本缺少同名 JSON：" + instance.versionJson);

    ResolvedVersion resolved = resolveVersion(instance.root, instance.selectedVersion, new HashSet<String>());
    Map<String, Object> descriptor = resolved.descriptor;
    String mainClass = string(descriptor.get("mainClass"));
    if (mainClass.isEmpty()) throw new IllegalArgumentException("版本 JSON 缺少 mainClass。");

    Path workerRoot = shortWorkerRoot(session);
    Path mods = workerRoot.resolve("mods");
    Path natives = workerRoot.resolve("natives");
    Path logs = workerRoot.resolve("logs");
    Files.createDirectories(mods);
    Files.createDirectories(natives);
    Files.createDirectories(logs);
    writeSilentOptions(workerRoot);
    Path workerJar = mods.resolve("litematic-render-worker.jar");
    copyRendererJar(workerJar);

    String clientJarVersion = string(descriptor.get("jar"));
    if (clientJarVersion.isEmpty()) clientJarVersion = instance.selectedVersion;
    Path clientJar = instance.root.resolve("versions").resolve(clientJarVersion).resolve(clientJarVersion + ".jar");

    List<Map<String, Object>> libraries = objectList(descriptor.get("libraries"));
    ArrayList<Path> artifacts = new ArrayList<Path>();
    ArrayList<Path> nativeJars = new ArrayList<Path>();
    for (Map<String, Object> library : libraries) {
      if (!rulesAllow(list(library.get("rules")))) continue;
      Path artifact = libraryPath(instance.root, library, "");
      if (artifact != null) artifacts.add(artifact);
      String classifier = nativeClassifier(library);
      if (!classifier.isEmpty()) {
        Path nativeJar = libraryPath(instance.root, library, classifier);
        if (nativeJar != null) nativeJars.add(nativeJar);
      }
    }
    ArrayList<Path> required = new ArrayList<Path>();
    required.add(clientJar);
    required.addAll(artifacts);
    required.addAll(nativeJars);
    requireFiles(required);
    extractNatives(nativeJars, natives);

    ArrayList<Path> classpathEntries = new ArrayList<Path>();
    classpathEntries.add(clientJar);
    classpathEntries.addAll(artifacts);
    String classpath = joinPaths(classpathEntries);

    String assetIndex = instance.selectedVersion;
    Map<String, Object> assetIndexObject = map(descriptor.get("assetIndex"));
    if (!assetIndexObject.isEmpty() && !string(assetIndexObject.get("id")).isEmpty()) assetIndex = string(assetIndexObject.get("id"));
    else if (!string(descriptor.get("assets")).isEmpty()) assetIndex = string(descriptor.get("assets"));

    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("natives_directory", natives.toString());
    values.put("launcher_name", "DesktopLitematicRender");
    values.put("launcher_version", VERSION);
    values.put("classpath", classpath);
    values.put("classpath_separator", File.pathSeparator);
    values.put("library_directory", instance.root.resolve("libraries").toString());
    values.put("version_name", instance.selectedVersion);
    values.put("version_type", string(descriptor.get("type")).isEmpty() ? "release" : string(descriptor.get("type")));
    values.put("assets_root", instance.root.resolve("assets").toString());
    values.put("assets_index_name", assetIndex);
    values.put("game_directory", workerRoot.toString());
    values.put("auth_player_name", "DsLRRenderer");
    values.put("auth_uuid", "00000000000000000000000000000000");
    values.put("auth_access_token", "0");
    values.put("auth_xuid", "0");
    values.put("clientid", "0");
    values.put("user_properties", "{}");
    values.put("user_type", "legacy");
    values.put("resolution_width", "16");
    values.put("resolution_height", "16");
    values.put("quickPlayPath", "");
    values.put("quickPlaySingleplayer", "");
    values.put("quickPlayMultiplayer", "");
    values.put("quickPlayRealms", "");

    Map<String, Object> arguments = map(descriptor.get("arguments"));
    List<String> jvmArguments = removeClasspathArguments(normalizeArguments(list(arguments.get("jvm")), values));
    boolean hasHeap = false;
    for (String argument : jvmArguments) if (argument.startsWith("-Xmx")) hasHeap = true;
    if (!hasHeap) jvmArguments.add("-Xmx2048M");
    addLoggingArgument(descriptor, instance.root, jvmArguments);
    List<String> gameArguments = normalizeArguments(list(arguments.get("game")), values);
    if (gameArguments.isEmpty()) gameArguments = splitLegacyArguments(substitute(string(descriptor.get("minecraftArguments")), values));
    addSilentGameArguments(gameArguments);

    ArrayList<String> command = new ArrayList<String>();
    command.add(runtime.executable.toString());
    command.add("-Dlrs.worker=true");
    command.add("-Dlrs.silent=true");
    command.add("-Dlrs.session=" + session.directory.toAbsolutePath().normalize());
    command.add("-Dlrs.clientJar=" + clientJar.toAbsolutePath().normalize());
    command.addAll(jvmArguments);
    command.add("-cp");
    command.add(classpath);
    command.add(mainClass);
    command.addAll(gameArguments);

    Path launchLog = session.directory.resolve("lrs-worker-launch.log");
    Path workerMinecraftLog = logs.resolve("latest.log");
    Path minecraftLog = session.directory.resolve("minecraft-latest.log");
    String header = "DsLR Java desktop launch\nRuntime: " + runtime.executable + "\nVersion JSON: " + resolved.descriptorPath + "\nWorker root: " + workerRoot + "\n\n";
    Files.write(launchLog, header.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    Files.write(session.directory.resolve("worker-runtime.txt"), (workerRoot.toString() + "\r\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workerRoot.toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog.toFile()));
    Process process = builder.start();
    owned.add(process);
    long pid = processId(process);
    hideWindowSoon(pid);
    log.info("已启动隔离 Fabric Worker，PID=" + pid + "，版本=" + instance.selectedVersion);
    watch(process, session, launchLog, workerMinecraftLog, minecraftLog);
    return new LaunchResult(process, pid, workerRoot, launchLog, minecraftLog, runtime);
  }

  Path shortWorkerRoot(WorkerSession session) throws Exception {
    String token = Integer.toHexString((session.id + "|" + session.directory).hashCode());
    ArrayList<Path> bases = new ArrayList<Path>();
    bases.add(Paths.get(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize().resolve("DsLRW"));
    bases.add(Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize().resolve("DsLRW"));
    bases.add(paths.runtime.resolve("DsLRW"));
    Exception last = null;
    for (Path base : bases) {
      try {
        Path root = base.resolve(token);
        Files.createDirectories(root);
        return root;
      } catch (Exception error) {
        last = error;
      }
    }
    throw last == null ? new IllegalArgumentException("无法创建临时 Minecraft Worker 目录。") : last;
  }

  private void watch(final Process process, final WorkerSession session, final Path launchLog, final Path workerMinecraftLog, final Path minecraftLog) {
    Thread watcher = new Thread(new Runnable() {
      public void run() {
        int code = -1;
        try {
          code = process.waitFor();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          owned.remove(process);
        }
        copyLog(workerMinecraftLog, minecraftLog);
        log.appendSource("Worker 启动与标准输出", launchLog);
        log.appendSource("Worker Minecraft latest.log", workerMinecraftLog);
        log.appendSource("Worker Minecraft 日志副本", minecraftLog);
        log.appendSource("实体坐标诊断", session.directory.resolve("entity-diagnostics.log"));
        Map<String, Object> last = session.lastEvent();
        Object type = last == null ? null : last.get("type");
        if (!"completed".equals(type) && !"failed".equals(type)) {
          session.appendFailure("隔离 Fabric Worker 已退出（代码 " + code + "）。请查看 logs 文件夹中的最新 .log。", "process");
        }
        log.info("隔离 Fabric Worker 已退出，代码=" + code);
      }
    }, "lrs-worker-watch");
    watcher.setDaemon(true);
    watcher.start();
  }

  private void copyLog(Path source, Path target) {
    try {
      if (Files.isRegularFile(source)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception ignored) {
    }
  }

  private void writeSilentOptions(Path workerRoot) {
    try {
      String options = "version:4180\nfullscreen:false\npauseOnLostFocus:false\nsoundCategory_master:0.0\nsoundCategory_music:0.0\nsoundCategory_records:0.0\nsoundCategory_weather:0.0\nsoundCategory_blocks:0.0\nsoundCategory_hostile:0.0\nsoundCategory_neutral:0.0\nsoundCategory_players:0.0\nsoundCategory_ambient:0.0\nsoundCategory_voice:0.0\n";
      Files.write(workerRoot.resolve("options.txt"), options.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (Exception error) {
      log.info("未能预置 Worker 静音选项：" + error.getMessage());
    }
  }

  private void addSilentGameArguments(List<String> arguments) {
    if (!arguments.contains("--width")) { arguments.add("--width"); arguments.add("16"); }
    if (!arguments.contains("--height")) { arguments.add("--height"); arguments.add("16"); }
  }

  private void hideWindowSoon(final long pid) {
    if (pid <= 0L || !"windows".equals(platformName())) return;
    Thread helper = new Thread(new Runnable() {
      public void run() {
        try {
          String script = "$code='using System; using System.Runtime.InteropServices; public static class LrsWindow { [DllImport(\"user32.dll\")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow); }'; Add-Type -TypeDefinition $code -ErrorAction SilentlyContinue; $end=(Get-Date).AddSeconds(12); while((Get-Date) -lt $end){ $p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; if($p -and $p.MainWindowHandle -ne 0){ [LrsWindow]::ShowWindowAsync($p.MainWindowHandle,0) | Out-Null; break }; Start-Sleep -Milliseconds 200 }";
          new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script).start();
        } catch (Exception ignored) {
        }
      }
    }, "lrs-hide-worker-window");
    helper.setDaemon(true);
    helper.start();
  }

  private ResolvedVersion resolveVersion(Path root, String id, Set<String> seen) throws Exception {
    if (!seen.add(id)) throw new IllegalArgumentException("Minecraft 版本继承出现循环：" + id);
    Path descriptorPath = root.resolve("versions").resolve(id).resolve(id + ".json");
    if (!Files.isRegularFile(descriptorPath)) throw new IllegalArgumentException("未找到 Minecraft 版本 JSON：" + descriptorPath);
    Map<String, Object> child = Json.readObject(descriptorPath);
    String parentId = string(child.get("inheritsFrom"));
    if (parentId.isEmpty()) return new ResolvedVersion(child, descriptorPath, new ArrayList<String>());
    ResolvedVersion parent = resolveVersion(root, parentId, seen);
    LinkedHashMap<String, Object> merged = new LinkedHashMap<String, Object>(parent.descriptor);
    merged.putAll(child);
    merged.put("libraries", mergeLibraries(list(parent.descriptor.get("libraries")), list(child.get("libraries"))));
    Map<String, Object> parentArguments = map(parent.descriptor.get("arguments"));
    Map<String, Object> childArguments = map(child.get("arguments"));
    LinkedHashMap<String, Object> arguments = new LinkedHashMap<String, Object>();
    arguments.put("jvm", concat(list(parentArguments.get("jvm")), list(childArguments.get("jvm"))));
    arguments.put("game", concat(list(parentArguments.get("game")), list(childArguments.get("game"))));
    merged.put("arguments", arguments);
    if (!child.containsKey("jar")) {
      String parentJar = string(parent.descriptor.get("jar"));
      merged.put("jar", parentJar.isEmpty() ? parentId : parentJar);
    }
    ArrayList<String> inherited = new ArrayList<String>(parent.inherited);
    inherited.add(parentId);
    return new ResolvedVersion(merged, descriptorPath, inherited);
  }

  private List<Object> mergeLibraries(List<Object> parent, List<Object> child) {
    LinkedHashMap<String, Object> merged = new LinkedHashMap<String, Object>();
    for (Object value : concat(parent, child)) {
      Map<String, Object> library = map(value);
      String name = string(library.get("name"));
      if (name.isEmpty()) name = string(map(map(library.get("downloads")).get("artifact")).get("path"));
      if (!name.isEmpty()) merged.put(name, value);
    }
    return new ArrayList<Object>(merged.values());
  }

  private Path libraryPath(Path root, Map<String, Object> library, String classifier) {
    Map<String, Object> downloads = map(library.get("downloads"));
    Map<String, Object> declared = classifier.isEmpty()
      ? map(downloads.get("artifact"))
      : map(map(downloads.get("classifiers")).get(classifier));
    String relative = string(declared.get("path"));
    if (relative.isEmpty()) relative = coordinatePath(string(library.get("name")), classifier);
    return relative.isEmpty() ? null : root.resolve("libraries").resolve(relative.replace('/', File.separatorChar));
  }

  private String coordinatePath(String coordinate, String classifier) {
    if (coordinate.isEmpty()) return "";
    String extension = "jar";
    int extensionAt = coordinate.indexOf('@');
    if (extensionAt >= 0) {
      extension = coordinate.substring(extensionAt + 1);
      coordinate = coordinate.substring(0, extensionAt);
    }
    String[] parts = coordinate.split(":");
    if (parts.length < 3) return "";
    String selectedClassifier = classifier;
    if (selectedClassifier.isEmpty() && parts.length > 3) selectedClassifier = parts[3];
    String suffix = selectedClassifier.isEmpty() ? "" : "-" + selectedClassifier;
    return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + suffix + "." + extension;
  }

  private String nativeClassifier(Map<String, Object> library) {
    Map<String, Object> natives = map(library.get("natives"));
    String value = string(natives.get(platformName()));
    if (value.isEmpty()) return "";
    String arch = System.getProperty("os.arch", "").contains("64") ? "64" : "32";
    return value.replace("${arch}", arch);
  }

  private boolean rulesAllow(List<Object> rules) {
    if (rules.isEmpty()) return true;
    boolean allowed = false;
    for (Object item : rules) {
      Map<String, Object> rule = map(item);
      if (!ruleApplies(rule)) continue;
      allowed = "allow".equals(string(rule.get("action")));
    }
    return allowed;
  }

  private boolean ruleApplies(Map<String, Object> rule) {
    Map<String, Object> features = map(rule.get("features"));
    if (!features.isEmpty()) return false;
    Map<String, Object> os = map(rule.get("os"));
    String name = string(os.get("name"));
    if (!name.isEmpty() && !name.equals(platformName())) return false;
    String arch = string(os.get("arch"));
    if (!arch.isEmpty() && !System.getProperty("os.arch", "").matches(arch)) return false;
    String version = string(os.get("version"));
    return version.isEmpty() || Pattern.compile(version).matcher(System.getProperty("os.version", "")).find();
  }

  private String platformName() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win")) return "windows";
    if (os.contains("mac")) return "osx";
    return "linux";
  }

  private List<String> normalizeArguments(List<Object> entries, Map<String, String> values) {
    ArrayList<String> result = new ArrayList<String>();
    for (Object entry : entries) {
      if (entry instanceof String) {
        result.add(substitute((String) entry, values));
      } else {
        Map<String, Object> object = map(entry);
        if (!rulesAllow(list(object.get("rules")))) continue;
        Object raw = object.get("value");
        if (raw instanceof List) {
          for (Object value : list(raw)) if (value instanceof String) result.add(substitute((String) value, values));
        } else if (raw instanceof String) {
          result.add(substitute((String) raw, values));
        }
      }
    }
    return result;
  }

  private String substitute(String value, Map<String, String> values) {
    String result = value == null ? "" : value;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      result = result.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }

  private List<String> removeClasspathArguments(List<String> entries) {
    ArrayList<String> result = new ArrayList<String>();
    for (int index = 0; index < entries.size(); index++) {
      String value = entries.get(index);
      if ("-cp".equals(value) || "-classpath".equals(value)) {
        index++;
        continue;
      }
      if (value.startsWith("-cp=") || value.startsWith("-classpath=")) continue;
      result.add(value);
    }
    return result;
  }

  private void addLoggingArgument(Map<String, Object> descriptor, Path root, List<String> arguments) {
    Map<String, Object> client = map(map(descriptor.get("logging")).get("client"));
    Map<String, Object> file = map(client.get("file"));
    String fileId = string(file.get("id"));
    String argument = string(client.get("argument"));
    if (fileId.isEmpty() || argument.isEmpty()) return;
    Path config = root.resolve("assets").resolve("log_configs").resolve(fileId);
    if (Files.isRegularFile(config)) arguments.add(argument.replace("${path}", config.toString()));
  }

  private List<String> splitLegacyArguments(String line) {
    ArrayList<String> result = new ArrayList<String>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') quoted = !quoted;
      else if (Character.isWhitespace(character) && !quoted) {
        if (current.length() > 0) {
          result.add(current.toString());
          current.setLength(0);
        }
      } else current.append(character);
    }
    if (current.length() > 0) result.add(current.toString());
    return result;
  }

  private void requireFiles(List<Path> files) {
    ArrayList<Path> missing = new ArrayList<Path>();
    for (Path file : files) if (file == null || !Files.isRegularFile(file)) missing.add(file);
    if (!missing.isEmpty()) {
      StringBuilder message = new StringBuilder("Minecraft 启动依赖缺失：");
      for (int index = 0; index < Math.min(6, missing.size()); index++) message.append("\n").append(missing.get(index));
      throw new IllegalArgumentException(message.toString());
    }
  }

  private void extractNatives(List<Path> nativeJars, Path destination) throws Exception {
    for (Path jar : nativeJars) {
      ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar)));
      try {
        ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) {
          if (entry.isDirectory()) continue;
          String name = entry.getName().replace('\\', '/');
          String base = name.substring(name.lastIndexOf('/') + 1);
          String lower = base.toLowerCase();
          if (!lower.endsWith(".dll") && !lower.endsWith(".so") && !lower.endsWith(".dylib")) continue;
          if (base.isEmpty() || base.contains("..")) continue;
          Files.copy(input, destination.resolve(base), StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        input.close();
      }
    }
  }

  private void copyRendererJar(Path destination) throws Exception {
    InputStream resource = MinecraftLaunchService.class.getResourceAsStream("/worker/" + WORKER_JAR);
    if (resource != null) {
      try {
        Files.copy(resource, destination, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        resource.close();
      }
      return;
    }
    File fallback = paths.workerResourceFallback();
    if (!fallback.isFile()) throw new IllegalArgumentException("控制器内未找到 Fabric PNG Renderer JAR。");
    Files.copy(fallback.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
  }

  private String joinPaths(List<Path> paths) {
    StringBuilder result = new StringBuilder();
    for (Path path : paths) {
      if (result.length() > 0) result.append(File.pathSeparator);
      result.append(path.toString());
    }
    return result.toString();
  }

  private long processId(Process process) {
    try {
      Method pid = process.getClass().getMethod("pid");
      Object value = pid.invoke(process);
      return value instanceof Number ? ((Number) value).longValue() : -1L;
    } catch (Exception ignored) {
      try {
        java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
        field.setAccessible(true);
        return ((Number) field.get(process)).longValue();
      } catch (Exception ignoredAgain) {
        return -1L;
      }
    }
  }

  public void close() {
    List<Process> snapshot;
    synchronized (owned) {
      snapshot = new ArrayList<Process>(owned);
      owned.clear();
    }
    for (Process process : snapshot) {
      try {
        if (process.isAlive()) {
          process.destroy();
          try {
            Thread.sleep(250L);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          if (process.isAlive()) process.destroyForcibly();
        }
      } catch (Exception ignored) {
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
  }

  private static List<Map<String, Object>> objectList(Object value) {
    ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Object item : list(value)) result.add(map(item));
    return result;
  }

  private static String string(Object value) {
    return value instanceof String ? ((String) value).trim() : "";
  }

  private static List<Object> concat(List<Object> left, List<Object> right) {
    ArrayList<Object> result = new ArrayList<Object>(left);
    result.addAll(right);
    return result;
  }
}
