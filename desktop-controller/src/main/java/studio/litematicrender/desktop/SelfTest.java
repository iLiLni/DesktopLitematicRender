package studio.litematicrender.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class SelfTest {
  private SelfTest() {
  }

  static int run() {
    Path temporary = null;
    try {
      Path base = Paths.get(System.getProperty("lrs.dataRoot", ".")).toAbsolutePath().normalize();
      Files.createDirectories(base);
      temporary = Files.createTempDirectory(base, "lrs-java-desktop-test-");
      Map<String, Object> sample = new LinkedHashMap<String, Object>();
      sample.put("name", "中文测试");
      sample.put("count", Long.valueOf(4));
      Object parsed = Json.parse(Json.stringify(sample));
      if (!(parsed instanceof Map) || !"中文测试".equals(((Map<?, ?>) parsed).get("name"))) throw new AssertionError("JSON roundtrip failed");

      Path minecraft = temporary.resolve(".minecraft");
      Path version = minecraft.resolve("versions").resolve("26.1.2-Fabric 0.19.3");
      Files.createDirectories(version.resolve("schematics"));
      Files.createDirectories(version.resolve("resourcepacks"));
      Files.write(version.resolve("26.1.2-Fabric 0.19.3.json"), "{}".getBytes(StandardCharsets.UTF_8));
      Files.write(version.resolve("machine.litematic"), new byte[] { 1 });
      Files.write(version.resolve("schematics").resolve("machine.litematic"), new byte[] { 1 });
      Files.createDirectories(version.resolve("schematics").resolve("nested"));
      Files.write(version.resolve("schematics").resolve("nested").resolve("nested-machine.litematic"), new byte[] { 1 });
      Files.write(version.resolve("resourcepacks").resolve("pack.zip"), new byte[] { 1 });
      MinecraftInstance instance = MinecraftInstance.inspect(version, "");
      if (!instance.hasSelectedVersionJson()) throw new AssertionError("version json not found");
      if (instance.schematics.size() != 2 || instance.resourcePacks.size() != 1) throw new AssertionError("instance scan failed");

      System.setProperty("lrs.dataRoot", temporary.resolve("appdata").toString());
      AppPaths appPaths = AppPaths.create();
      WorkerSession session = WorkerSession.createProbe(appPaths, instance);
      if (!Files.isRegularFile(session.commands) || !Files.isRegularFile(session.events)) throw new AssertionError("worker session failed");
      MinecraftLaunchService launcher = new MinecraftLaunchService(appPaths, new AppLog(appPaths.logs.resolve("self-test.log")));
      Path workerRoot = launcher.shortWorkerRoot(session);
      if (workerRoot.startsWith(session.directory) || workerRoot.resolve("natives").resolve("glfw.dll").toString().length() > 200) {
        throw new AssertionError("worker native path was not shortened");
      }
      JavaRuntimeLocator.RuntimeInfo current = JavaRuntimeLocator.findBest(8, null);
      if (current == null) throw new AssertionError("java runtime detection failed");
      System.out.println("SELF_TEST_OK");
      System.out.println("runtime=" + current);
      System.out.println("data=" + appPaths.dataRoot);
      return 0;
    } catch (Throwable error) {
      error.printStackTrace();
      return 1;
    } finally {
      if (temporary != null) deleteTree(temporary);
    }
  }

  private static void deleteTree(Path path) {
    try {
      if (Files.isDirectory(path)) {
        DirectoryStream<Path> stream = Files.newDirectoryStream(path);
        try {
          for (Path child : stream) deleteTree(child);
        } finally {
          stream.close();
        }
      }
      Files.deleteIfExists(path);
    } catch (Exception ignored) {
    }
  }
}
