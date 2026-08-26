package studio.litematicrender.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JavaRuntimeLocator {
  static final class RuntimeInfo {
    final Path home;
    final Path executable;
    final int major;
    final String version;

    RuntimeInfo(Path home, Path executable, int major, String version) {
      this.home = home;
      this.executable = executable;
      this.major = major;
      this.version = version;
    }

    public String toString() {
      return "Java " + version + " · " + executable;
    }
  }

  private JavaRuntimeLocator() {
  }

  static RuntimeInfo findBest(int minimumMajor, AppLog log) {
    List<RuntimeInfo> candidates = findAll(log);
    for (RuntimeInfo candidate : candidates) {
      if (candidate.major >= minimumMajor) return candidate;
    }
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  static List<RuntimeInfo> findAll(AppLog log) {
    Set<Path> homes = new LinkedHashSet<Path>();
    addHome(homes, System.getProperty("java.home"));
    addHome(homes, System.getenv("JAVA_HOME"));
    String pathValue = System.getenv("PATH");
    if (pathValue != null) {
      String[] entries = pathValue.split(Pattern.quote(File.pathSeparator));
      for (String entry : entries) {
        if (entry == null || entry.trim().isEmpty()) continue;
        Path candidate = Paths.get(entry.trim()).toAbsolutePath().normalize();
        if (candidate.getFileName() != null && candidate.getFileName().toString().equalsIgnoreCase("bin")) {
          homes.add(candidate.getParent());
        }
      }
    }
    addProgramRoots(homes, System.getenv("ProgramW6432"));
    addProgramRoots(homes, System.getenv("ProgramFiles"));
    addProgramRoots(homes, System.getenv("ProgramFiles(x86)"));
    String local = System.getenv("LOCALAPPDATA");
    if (local != null && !local.trim().isEmpty()) {
      scanVendorRoot(homes, Paths.get(local, "Programs", "Eclipse Adoptium"), 2);
    }

    ArrayList<RuntimeInfo> result = new ArrayList<RuntimeInfo>();
    for (Path home : homes) {
      RuntimeInfo info = inspect(home);
      if (info != null) result.add(info);
    }
    Collections.sort(result, new Comparator<RuntimeInfo>() {
      public int compare(RuntimeInfo left, RuntimeInfo right) {
        int byMajor = Integer.compare(right.major, left.major);
        return byMajor != 0 ? byMajor : right.version.compareTo(left.version);
      }
    });
    if (log != null) {
      for (RuntimeInfo info : result) log.info("发现 Java：" + info.toString());
    }
    return result;
  }

  private static void addProgramRoots(Set<Path> homes, String programFiles) {
    if (programFiles == null || programFiles.trim().isEmpty()) return;
    Path root = Paths.get(programFiles);
    scanVendorRoot(homes, root.resolve("Eclipse Adoptium"), 2);
    scanVendorRoot(homes, root.resolve("Java"), 2);
    scanVendorRoot(homes, root.resolve("Microsoft"), 3);
    scanVendorRoot(homes, root.resolve("Zulu"), 2);
  }

  private static void scanVendorRoot(Set<Path> homes, Path root, int depth) {
    if (root == null || depth < 0 || !Files.isDirectory(root)) return;
    if (Files.isRegularFile(javaExecutable(root))) homes.add(root.toAbsolutePath().normalize());
    if (depth == 0) return;
    try {
      DirectoryStream<Path> stream = Files.newDirectoryStream(root);
      try {
        for (Path child : stream) {
          if (Files.isDirectory(child)) scanVendorRoot(homes, child, depth - 1);
        }
      } finally {
        stream.close();
      }
    } catch (Exception ignored) {
    }
  }

  private static void addHome(Set<Path> homes, String value) {
    if (value == null || value.trim().isEmpty()) return;
    Path home = Paths.get(value.trim()).toAbsolutePath().normalize();
    if (home.getFileName() != null && home.getFileName().toString().equalsIgnoreCase("jre") && home.getParent() != null) {
      Path parent = home.getParent();
      if (Files.isRegularFile(javaExecutable(parent))) home = parent;
    }
    homes.add(home);
  }

  private static RuntimeInfo inspect(Path home) {
    try {
      Path executable = javaExecutable(home);
      if (!Files.isRegularFile(executable)) return null;
      Path release = home.resolve("release");
      String version = "";
      if (Files.isRegularFile(release)) {
        List<String> lines = Files.readAllLines(release, StandardCharsets.UTF_8);
        for (String line : lines) {
          if (line.startsWith("JAVA_VERSION=")) {
            version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
            break;
          }
        }
      }
      if (version.isEmpty() && home.equals(Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize())) {
        version = System.getProperty("java.version", "");
      }
      int major = major(version);
      if (major <= 0) return null;
      return new RuntimeInfo(home, executable, major, version);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Path javaExecutable(Path home) {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    Path javaw = home.resolve("bin").resolve(windows ? "javaw.exe" : "java");
    if (Files.isRegularFile(javaw)) return javaw;
    return home.resolve("bin").resolve(windows ? "java.exe" : "java");
  }

  private static int major(String version) {
    if (version == null) return -1;
    Matcher matcher = Pattern.compile("^(?:1\\.)?(\\d+)").matcher(version.trim());
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
  }
}
