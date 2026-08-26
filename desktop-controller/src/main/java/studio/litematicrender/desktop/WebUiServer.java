package studio.litematicrender.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WebUiServer implements AutoCloseable {
  private static final class Session {
    final WorkerSession worker;
    final MinecraftLaunchService.LaunchResult launch;
    boolean diagnosticWritten;
    String lastLoggedEvent;

    Session(WorkerSession worker, MinecraftLaunchService.LaunchResult launch) {
      this.worker = worker;
      this.launch = launch;
    }
  }

  private final AppPaths paths;
  private final AppLog log;
  private final MinecraftLaunchService launcher;
  private final JavaRuntimeLocator.RuntimeInfo runtime;
  private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private HttpServer server;
  private URI uri;

  WebUiServer(AppPaths paths, AppLog log, MinecraftLaunchService launcher, JavaRuntimeLocator.RuntimeInfo runtime) {
    this.paths = paths;
    this.log = log;
    this.launcher = launcher;
    this.runtime = runtime;
  }

  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.setExecutor(executor);
    server.createContext("/", new StaticHandler("/web/index.html", "text/html; charset=utf-8"));
    server.createContext("/styles.css", new StaticHandler("/web/styles.css", "text/css; charset=utf-8"));
    server.createContext("/app.js", new StaticHandler("/web/app.js", "application/javascript; charset=utf-8"));
    server.createContext("/api/bootstrap", new HttpHandler() {
      public void handle(HttpExchange exchange) {
        try {
          if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) throw new IllegalArgumentException("仅支持 GET。 ");
          respondJson(exchange, 200, bootstrap());
        } catch (Exception error) {
          fail(exchange, error);
        }
      }
    });
    server.createContext("/api/inspect", new ApiHandler() {
      protected Object call(Map<String, Object> body) throws Exception { return inspect(body); }
    });
    server.createContext("/api/render", new ApiHandler() {
      protected Object call(Map<String, Object> body) throws Exception { return render(body); }
    });
    server.createContext("/api/schematics", new ApiHandler() {
      protected Object call(Map<String, Object> body) throws Exception { return schematics(body); }
    });
    server.createContext("/api/session", new HttpHandler() {
      public void handle(HttpExchange exchange) {
        try {
          if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) throw new IllegalArgumentException("仅支持 GET。 ");
          String id = query(exchange.getRequestURI().getRawQuery(), "id");
          if (id.isEmpty()) throw new IllegalArgumentException("缺少会话 ID。 ");
          respondJson(exchange, 200, session(id));
        } catch (Exception error) {
          fail(exchange, error);
        }
      }
    });
    server.createContext("/api/stop", new ApiHandler() {
      protected Object call(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("stopping", Boolean.TRUE);
        new Thread(new Runnable() {
          public void run() {
            try {
              Thread.sleep(180L);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
            WebUiServer.this.close();
          }
        }, "lrs-web-stop").start();
        return result;
      }
    });
    server.start();
    uri = new URI("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    Files.write(paths.logs.resolve("web-ui-url.txt"), (uri.toString() + "\r\n").getBytes(StandardCharsets.UTF_8));
    log.info("DsLR 本地网页界面已启动：" + uri);
    System.out.println("DsLR Web UI: " + uri);
    openBrowser();
  }

  void await() throws InterruptedException {
    stopped.await();
  }

  public void close() {
    HttpServer running = server;
    server = null;
    if (running != null) running.stop(0);
    executor.shutdownNow();
    launcher.close();
    stopped.countDown();
  }

  private Object inspect(Map<String, Object> body) throws Exception {
    String rawPath = string(body.get("instancePath"));
    String selected = string(body.get("minecraftVersion"));
    if (rawPath.isEmpty()) throw new IllegalArgumentException("请填写 Minecraft 游戏文件夹。 ");
    MinecraftInstance instance = MinecraftInstance.inspect(Paths.get(rawPath), selected);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("root", instance.root.toString());
    result.put("selectedVersion", instance.selectedVersion);
    result.put("versionJson", instance.versionJson == null ? "" : instance.versionJson.toString());
    result.put("hasVersionJson", Boolean.valueOf(instance.hasSelectedVersionJson()));
    result.put("summary", instance.summary());
    result.put("versions", new ArrayList<Object>(instance.versions));
    Path schematicDirectory = defaultSchematicDirectory(instance);
    List<Path> visibleSchematics = Files.isDirectory(schematicDirectory) ? scanSchematics(schematicDirectory) : instance.schematics;
    result.put("schematics", paths(visibleSchematics));
    result.put("schematicEntries", pathEntries(visibleSchematics));
    result.put("defaultSchematicDirectory", schematicDirectory.toString());
    result.put("resourcePacks", paths(instance.resourcePacks));
    result.put("defaultOutputDirectory", defaultOutputDirectory().toString());
    result.put("runtime", runtime == null ? "未找到 Java 25" : runtime.toString());
    log.info("网页读取 Minecraft 实例：" + instance.root + "，版本=" + instance.selectedVersion);
    return result;
  }

  private Object bootstrap() {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("version", "1.0.0");
    result.put("runtime", runtime == null ? "未找到 Java 25" : runtime.toString());
    result.put("defaultOutputDirectory", defaultOutputDirectory().toString());
    result.put("coordinateSystem", "Minecraft：X/Z 为水平轴，Y 为上下轴");
    return result;
  }

  private Object schematics(Map<String, Object> body) throws Exception {
    String raw = string(body.get("directory"));
    if (raw.isEmpty()) throw new IllegalArgumentException("请填写投影文件夹。 ");
    Path directory = Paths.get(raw).toAbsolutePath().normalize();
    if (!Files.isDirectory(directory)) throw new IllegalArgumentException("投影文件夹不存在：" + directory);
    List<Path> files = scanSchematics(directory);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("directory", directory.toString());
    result.put("schematics", pathEntries(files));
    result.put("count", Integer.valueOf(files.size()));
    return result;
  }

  private Object render(Map<String, Object> body) throws Exception {
    if (runtime == null || runtime.major < 25) throw new IllegalArgumentException("未找到 Java 25 或更新版本。请在电脑中安装或配置 Java 25 后重试。 ");
    Map<String, Object> job = object(body.get("job"));
    Map<String, Object> source = object(job.get("source"));
    String rawRoot = string(source.get("instancePath"));
    String selected = string(source.get("minecraftVersion"));
    if (rawRoot.isEmpty()) throw new IllegalArgumentException("渲染任务缺少 Minecraft 游戏文件夹。 ");
    MinecraftInstance instance = MinecraftInstance.inspect(Paths.get(rawRoot), selected);
    if (!instance.hasSelectedVersionJson()) throw new IllegalArgumentException("目标版本缺少同名版本 JSON：" + instance.versionJson);
    String litematic = string(source.get("litematicPath"));
    if (litematic.isEmpty() || !Files.isRegularFile(Paths.get(litematic))) throw new IllegalArgumentException("请选择存在的 .litematic 文件。 ");
    Map<String, Object> output = object(job.get("output"));
    if (string(output.get("directory")).isEmpty()) throw new IllegalArgumentException("请选择 PNG 输出文件夹。 ");
    WorkerSession worker = WorkerSession.createRender(paths, instance, job);
    MinecraftLaunchService.LaunchResult launch = launcher.startWorker(instance, worker, runtime);
    String token = UUID.randomUUID().toString();
    sessions.put(token, new Session(worker, launch));
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("sessionId", token);
    result.put("message", "正在启动 PNG 渲染…");
    return result;
  }

  private Object session(String id) {
    Session value = sessions.get(id);
    if (value == null) throw new IllegalArgumentException("找不到网页渲染会话。 ");
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("running", Boolean.valueOf(value.launch.process.isAlive()));
    Map<String, Object> event = value.worker.lastEvent();
    if (event != null) {
      String serialized = Json.stringify(event);
      synchronized (value) {
        if (!serialized.equals(value.lastLoggedEvent)) {
          value.lastLoggedEvent = serialized;
          log.event("网页 Worker 事件：会话 " + id, serialized);
        }
      }
    }
    if (event != null && "failed".equals(String.valueOf(event.get("type")))) writeSessionDiagnostic(value, String.valueOf(event.get("message")));
    result.put("event", event == null ? Collections.<String, Object>emptyMap() : event);
    return result;
  }

  private void writeSessionDiagnostic(Session session, String message) {
    synchronized (session) {
      if (session.diagnosticWritten) return;
      session.diagnosticWritten = true;
    }
    log.renderFailure("Fabric PNG 渲染失败：" + message,
      session.worker.events,
      session.worker.directory.resolve("worker-render-error.log"),
      session.launch.launchLog,
      session.launch.minecraftLog,
      session.launch.workerRoot.resolve("logs").resolve("latest.log"),
      session.worker.directory.resolve("entity-diagnostics.log"));
  }

  private void openBrowser() {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(uri);
    } catch (Exception error) {
      log.info("未能自动打开浏览器，请手动访问：" + uri);
    }
  }

  private abstract class ApiHandler implements HttpHandler {
    public void handle(HttpExchange exchange) {
      try {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) throw new IllegalArgumentException("仅支持 POST。 ");
        Object parsed = Json.parse(read(exchange.getRequestBody()));
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("请求 JSON 必须是对象。 ");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed;
        respondJson(exchange, 200, call(body));
      } catch (Exception error) {
        fail(exchange, error);
      }
    }

    protected abstract Object call(Map<String, Object> body) throws Exception;
  }

  private final class StaticHandler implements HttpHandler {
    private final String resource;
    private final String type;

    StaticHandler(String resource, String type) {
      this.resource = resource;
      this.type = type;
    }

    public void handle(HttpExchange exchange) {
      try {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) throw new IllegalArgumentException("仅支持 GET。 ");
        byte[] content = resource(resource);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
      } catch (Exception error) {
        fail(exchange, error);
      } finally {
        exchange.close();
      }
    }
  }

  private void respondJson(HttpExchange exchange, int code, Object body) throws Exception {
    byte[] content = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(code, content.length);
    exchange.getResponseBody().write(content);
    exchange.close();
  }

  private void fail(HttpExchange exchange, Exception error) {
    String message = readable(error);
    log.error("网页 API 失败：" + message, error);
    try {
      Map<String, Object> body = new LinkedHashMap<String, Object>();
      boolean render = "/api/render".equals(exchange.getRequestURI().getPath());
      body.put("error", render ? "PNG 渲染启动失败。请查看程序目录 logs 文件夹中的最新 .log。" : message);
      respondJson(exchange, 400, body);
    } catch (Exception ignored) {
      exchange.close();
    }
  }

  private static byte[] resource(String resource) throws Exception {
    InputStream stream = WebUiServer.class.getResourceAsStream(resource);
    if (stream == null) throw new IllegalArgumentException("程序缺少网页资源：" + resource);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = stream.read(buffer)) >= 0) out.write(buffer, 0, count);
      return out.toByteArray();
    } finally {
      stream.close();
    }
  }

  private static String read(InputStream stream) throws Exception {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = stream.read(buffer)) >= 0) out.write(buffer, 0, count);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      stream.close();
    }
  }

  private static List<Object> paths(List<Path> source) {
    ArrayList<Object> result = new ArrayList<Object>();
    for (Path path : source) result.add(path.toString());
    return result;
  }

  private static List<Object> pathEntries(List<Path> source) {
    ArrayList<Object> result = new ArrayList<Object>();
    for (Path path : source) {
      Map<String, Object> entry = new LinkedHashMap<String, Object>();
      entry.put("name", path.getFileName() == null ? path.toString() : path.getFileName().toString());
      entry.put("path", path.toString());
      Path parent = path.getParent();
      entry.put("folder", parent == null ? "" : parent.toString());
      result.add(entry);
    }
    return result;
  }

  private static Path defaultOutputDirectory() {
    return Paths.get(System.getProperty("user.home"), "Pictures", "LitematicRenders").toAbsolutePath().normalize();
  }

  private static Path defaultSchematicDirectory(MinecraftInstance instance) {
    if (instance.versionDirectory != null) return instance.versionDirectory.resolve("schematics").toAbsolutePath().normalize();
    return instance.root.resolve("schematics").toAbsolutePath().normalize();
  }

  private static List<Path> scanSchematics(Path directory) throws Exception {
    ArrayList<Path> result = new ArrayList<Path>();
    scanSchematics(directory, 0, result);
    Collections.sort(result, new java.util.Comparator<Path>() {
      public int compare(Path left, Path right) { return left.toString().compareToIgnoreCase(right.toString()); }
    });
    return result;
  }

  private static void scanSchematics(Path directory, int depth, List<Path> result) throws Exception {
    if (depth > 8 || result.size() >= 5000) return;
    java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
    try {
      for (Path child : stream) {
        if (result.size() >= 5000) return;
        if (Files.isRegularFile(child) && child.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic")) {
          result.add(child.toAbsolutePath().normalize());
        } else if (Files.isDirectory(child) && !Files.isSymbolicLink(child)) {
          scanSchematics(child, depth + 1, result);
        }
      }
    } finally {
      stream.close();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String readable(Exception error) {
    Throwable current = error;
    while (current.getCause() != null) current = current.getCause();
    String message = current.getMessage();
    return message == null || message.trim().isEmpty() ? current.toString() : message;
  }

  private static String query(String raw, String target) {
    if (raw == null || raw.isEmpty()) return "";
    for (String item : raw.split("&")) {
      int split = item.indexOf('=');
      String key = split < 0 ? item : item.substring(0, split);
      if (!target.equals(key)) continue;
      String value = split < 0 ? "" : item.substring(split + 1);
      try {
        return java.net.URLDecoder.decode(value, "UTF-8");
      } catch (Exception ignored) {
        return "";
      }
    }
    return "";
  }
}
