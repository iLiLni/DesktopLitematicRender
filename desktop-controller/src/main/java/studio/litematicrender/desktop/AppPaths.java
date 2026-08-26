package studio.litematicrender.desktop;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class AppPaths {
  final Path dataRoot;
  final Path logs;
  final Path runtime;
  final Path sessions;
  final Path drafts;
  final Path installRoot;

  private AppPaths(Path dataRoot, Path installRoot) throws Exception {
    this.dataRoot = dataRoot;
    this.logs = dataRoot.resolve("logs");
    this.runtime = dataRoot.resolve("runtime");
    this.sessions = runtime.resolve("sessions");
    this.drafts = runtime.resolve("job-drafts");
    this.installRoot = installRoot;
    Files.createDirectories(logs);
    Files.createDirectories(sessions);
    Files.createDirectories(drafts);
  }

  static AppPaths create() throws Exception {
    String override = System.getProperty("lrs.dataRoot");
    Path install = Paths.get(".").toAbsolutePath().normalize();
    try {
      URI uri = AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      Path source = Paths.get(uri).toAbsolutePath().normalize();
      install = Files.isRegularFile(source) ? source.getParent() : source;
      if (install.getFileName() != null && "worker".equalsIgnoreCase(install.getFileName().toString()) && install.getParent() != null) {
        install = install.getParent();
      }
    } catch (Exception ignored) {
    }
    Path base = override != null && !override.trim().isEmpty()
      ? Paths.get(override.trim())
      : install;
    return new AppPaths(base.toAbsolutePath().normalize(), install);
  }

  File workerResourceFallback() {
    Path compact = installRoot.resolve("worker").resolve("DsLRWorker.jar");
    if (Files.isRegularFile(compact)) return compact.toFile();
    compact = installRoot.resolve("DsLRWorker.jar");
    if (Files.isRegularFile(compact)) return compact.toFile();
    return installRoot.resolve("litematic-render-worker-1.0.0-unified-entity-frame.jar").toFile();
  }
}
