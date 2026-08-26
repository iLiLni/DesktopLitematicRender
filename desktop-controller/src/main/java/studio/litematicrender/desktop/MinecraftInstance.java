package studio.litematicrender.desktop;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MinecraftInstance {
  final Path suppliedPath;
  final Path root;
  final String selectedVersion;
  final Path versionDirectory;
  final Path versionJson;
  final List<String> versions;
  final List<Path> schematics;
  final List<Path> resourcePacks;

  private MinecraftInstance(
    Path suppliedPath,
    Path root,
    String selectedVersion,
    Path versionDirectory,
    Path versionJson,
    List<String> versions,
    List<Path> schematics,
    List<Path> resourcePacks
  ) {
    this.suppliedPath = suppliedPath;
    this.root = root;
    this.selectedVersion = selectedVersion;
    this.versionDirectory = versionDirectory;
    this.versionJson = versionJson;
    this.versions = versions;
    this.schematics = schematics;
    this.resourcePacks = resourcePacks;
  }

  static MinecraftInstance inspect(Path input, String preferredVersion) throws Exception {
    if (input == null) throw new IllegalArgumentException("Minecraft 游戏文件夹不能为空。");
    Path supplied = input.toAbsolutePath().normalize();
    Path root = supplied;
    String selected = trim(preferredVersion);
    Path parent = supplied.getParent();
    if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equalsIgnoreCase("versions")) {
      root = parent.getParent();
      if (selected.isEmpty()) selected = supplied.getFileName().toString();
    } else if (supplied.getFileName() != null && supplied.getFileName().toString().equalsIgnoreCase("versions")) {
      root = supplied.getParent();
    }
    if (root == null || !Files.isDirectory(root)) throw new IllegalArgumentException("Minecraft 游戏文件夹不存在：" + supplied);

    Path versionsRoot = root.resolve("versions");
    List<String> versions = listDirectories(versionsRoot);
    if (selected.isEmpty() && !versions.isEmpty()) selected = versions.get(0);
    Path versionDirectory = selected.isEmpty() ? null : versionsRoot.resolve(selected);
    Path versionJson = versionDirectory == null ? null : versionDirectory.resolve(selected + ".json");

    Set<Path> schematics = new LinkedHashSet<Path>();
    Set<Path> packs = new LinkedHashSet<Path>();
    if (versionDirectory != null) {
      schematics.addAll(listFiles(versionDirectory.resolve("schematics"), ".litematic", false));
      packs.addAll(listFiles(versionDirectory.resolve("resourcepacks"), ".zip", true));
    }
    schematics.addAll(listFiles(root.resolve("schematics"), ".litematic", false));
    packs.addAll(listFiles(root.resolve("resourcepacks"), ".zip", true));

    return new MinecraftInstance(
      supplied,
      root,
      selected,
      versionDirectory,
      versionJson,
      versions,
      sorted(schematics),
      sorted(packs)
    );
  }

  boolean hasSelectedVersionJson() {
    return versionJson != null && Files.isRegularFile(versionJson);
  }

  String summary() {
    return "已读取 " + versions.size() + " 个版本、" + schematics.size() + " 个投影文件、" + resourcePacks.size() + " 个资源包。";
  }

  private static List<String> listDirectories(Path directory) throws Exception {
    ArrayList<String> result = new ArrayList<String>();
    if (!Files.isDirectory(directory)) return result;
    DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
    try {
      for (Path child : stream) {
        if (Files.isDirectory(child)) result.add(child.getFileName().toString());
      }
    } finally {
      stream.close();
    }
    Collections.sort(result, Collections.reverseOrder(String.CASE_INSENSITIVE_ORDER));
    return result;
  }

  private static List<Path> listFiles(Path directory, String extension, boolean includeDirectories) throws Exception {
    ArrayList<Path> result = new ArrayList<Path>();
    if (!Files.isDirectory(directory)) return result;
    listFiles(directory, extension, includeDirectories, 0, result);
    return result;
  }

  private static void listFiles(Path directory, String extension, boolean includeDirectories, int depth, List<Path> result) throws Exception {
    if (depth > 8 || result.size() >= 5000) return;
    DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
    try {
      for (Path child : stream) {
        if (result.size() >= 5000) return;
        if (includeDirectories && Files.isDirectory(child)) result.add(child.toAbsolutePath().normalize());
        else if (Files.isRegularFile(child) && child.getFileName().toString().toLowerCase().endsWith(extension)) {
          result.add(child.toAbsolutePath().normalize());
        } else if (!includeDirectories && Files.isDirectory(child) && !Files.isSymbolicLink(child)) {
          listFiles(child, extension, false, depth + 1, result);
        }
      }
    } finally {
      stream.close();
    }
  }

  private static List<Path> sorted(Set<Path> paths) {
    ArrayList<Path> result = new ArrayList<Path>(paths);
    Collections.sort(result, new Comparator<Path>() {
      public int compare(Path left, Path right) {
        return left.toString().compareToIgnoreCase(right.toString());
      }
    });
    return result;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
