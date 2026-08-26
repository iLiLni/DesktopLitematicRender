import { copyFile, mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { ValidationError } from "./errors.js";
import { readZipEntries, readZipEntry } from "./zip.js";

function platformName(platform) {
  if (platform === "win32") return "windows";
  if (platform === "darwin") return "osx";
  return "linux";
}

function isDirectory(info) {
  return Boolean(info?.isDirectory());
}

async function existsAsFile(filePath) {
  return Boolean((await stat(filePath).catch(() => null))?.isFile());
}

function ruleApplies(rule, platform) {
  if (!rule || typeof rule !== "object") return false;
  if (rule.os?.name && rule.os.name !== platformName(platform)) return false;
  if (rule.features && Object.keys(rule.features).length) return false;
  return true;
}

export function libraryAllowed(library, { platform = process.platform } = {}) {
  const rules = library?.rules;
  if (!Array.isArray(rules) || rules.length === 0) return true;
  let allowed = false;
  for (const rule of rules) {
    if (ruleApplies(rule, platform)) allowed = rule.action === "allow";
  }
  return allowed;
}

function libraryKey(library) {
  return String(library?.name ?? library?.downloads?.artifact?.path ?? "");
}

function mergeLibraries(parentLibraries = [], childLibraries = []) {
  const merged = new Map();
  for (const library of [...parentLibraries, ...childLibraries]) {
    const key = libraryKey(library);
    if (!key) continue;
    merged.set(key, library);
  }
  return [...merged.values()];
}

function normalizeArgumentEntries(entries, values, platform) {
  const output = [];
  for (const entry of entries ?? []) {
    if (typeof entry === "string") {
      output.push(substituteArgument(entry, values));
      continue;
    }
    if (!entry || typeof entry !== "object" || !libraryAllowed(entry, { platform })) continue;
    const rawValues = Array.isArray(entry.value) ? entry.value : [entry.value];
    for (const value of rawValues) {
      if (typeof value === "string") output.push(substituteArgument(value, values));
    }
  }
  return output;
}

function substituteArgument(value, values) {
  return value.replace(/\$\{([^}]+)\}/g, (whole, key) => values[key] === undefined ? whole : String(values[key]));
}

function coordinatePath(coordinate, classifier = "") {
  const parts = String(coordinate ?? "").split(":");
  if (parts.length < 3) throw new ValidationError("Minecraft 库坐标无效：" + coordinate);
  const group = parts[0].replaceAll(".", "/");
  const artifact = parts[1];
  const version = parts[2];
  const extension = parts[3] || "jar";
  const suffix = classifier ? "-" + classifier : "";
  return path.join(group, artifact, version, artifact + "-" + version + suffix + "." + extension);
}

function nativeClassifier(library, platform) {
  const nativeValue = library?.natives?.[platformName(platform)];
  if (!nativeValue) return "";
  const arch = os.arch() === "ia32" ? "32" : "64";
  return String(nativeValue).replaceAll("$" + "{arch}", arch);
}

export function resolveLibraryPath(instanceRoot, library, { classifier = "" } = {}) {
  const downloads = library?.downloads ?? {};
  const declared = classifier ? downloads.classifiers?.[classifier] : downloads.artifact;
  const relativePath = declared?.path ?? coordinatePath(library?.name, classifier);
  return path.join(instanceRoot, "libraries", relativePath);
}

async function readDescriptor(instanceRoot, versionId) {
  const descriptorPath = path.join(instanceRoot, "versions", versionId, versionId + ".json");
  let descriptor;
  try {
    descriptor = JSON.parse(await readFile(descriptorPath, "utf8"));
  } catch {
    throw new ValidationError("未找到或无法读取 Minecraft 版本 JSON：" + descriptorPath);
  }
  return { descriptorPath, descriptor };
}

async function resolveDescriptorChain(instanceRoot, versionId, seen = new Set()) {
  if (seen.has(versionId)) throw new ValidationError("Minecraft 版本继承出现循环：" + versionId);
  seen.add(versionId);
  const current = await readDescriptor(instanceRoot, versionId);
  const parentId = typeof current.descriptor.inheritsFrom === "string" ? current.descriptor.inheritsFrom : "";
  if (!parentId) {
    return {
      versionId,
      descriptorPath: current.descriptorPath,
      inheritedIds: [],
      descriptor: {
        ...current.descriptor,
        libraries: current.descriptor.libraries ?? [],
        arguments: current.descriptor.arguments ?? { jvm: [], game: [] }
      }
    };
  }
  const parent = await resolveDescriptorChain(instanceRoot, parentId, seen);
  const parentArguments = parent.descriptor.arguments ?? { jvm: [], game: [] };
  const childArguments = current.descriptor.arguments ?? { jvm: [], game: [] };
  return {
    versionId,
    descriptorPath: current.descriptorPath,
    inheritedIds: [...parent.inheritedIds, parentId],
    descriptor: {
      ...parent.descriptor,
      ...current.descriptor,
      libraries: mergeLibraries(parent.descriptor.libraries, current.descriptor.libraries),
      arguments: {
        jvm: [...(parentArguments.jvm ?? []), ...(childArguments.jvm ?? [])],
        game: [...(parentArguments.game ?? []), ...(childArguments.game ?? [])]
      },
      assetIndex: current.descriptor.assetIndex ?? parent.descriptor.assetIndex,
      assets: current.descriptor.assets ?? parent.descriptor.assets,
      mainClass: current.descriptor.mainClass ?? parent.descriptor.mainClass,
      jar: current.descriptor.jar ?? parent.descriptor.jar ?? parentId
    }
  };
}

export async function readMinecraftLaunchDescriptor({ instanceRoot, minecraftVersion }) {
  if (typeof instanceRoot !== "string" || !instanceRoot.trim()) throw new ValidationError("Minecraft 游戏文件夹不能为空。");
  if (typeof minecraftVersion !== "string" || !minecraftVersion.trim()) throw new ValidationError("目标 Minecraft 版本不能为空。");
  return resolveDescriptorChain(path.resolve(instanceRoot), minecraftVersion.trim());
}

function removeClasspathArguments(argumentsList) {
  const output = [];
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index];
    if (argument === "-cp" || argument === "-classpath") {
      index += 1;
      continue;
    }
    if (argument.startsWith("-cp=") || argument.startsWith("-classpath=")) continue;
    output.push(argument);
  }
  return output;
}

async function extractNativeLibraries({ nativeJarPaths, nativesDirectory }) {
  await mkdir(nativesDirectory, { recursive: true });
  for (const nativeJarPath of nativeJarPaths) {
    const archive = await readFile(nativeJarPath);
    const entries = readZipEntries(archive);
    for (const entryName of entries.keys()) {
      if (!entryName.toLowerCase().endsWith(".dll")) continue;
      const baseName = path.basename(entryName);
      if (!baseName || baseName !== entryName.split("/").at(-1)) continue;
      const content = readZipEntry(archive, entryName);
      if (content) await writeFile(path.join(nativesDirectory, baseName), content);
    }
  }
}

async function requireFiles(paths, label) {
  const missing = [];
  for (const filePath of paths) {
    if (!await existsAsFile(filePath)) missing.push(filePath);
  }
  if (missing.length) throw new ValidationError(label + "缺失：" + missing.slice(0, 4).join("；"));
}

export async function prepareHiddenMinecraftWorker({
  instanceRoot,
  minecraftVersion,
  sessionDirectory,
  workerJarPath,
  javaExecutable,
  platform = process.platform,
  launchRoot,
  displayWidth = 854,
  displayHeight = 480
}) {
  if (platform !== "win32") throw new ValidationError("隐藏 Minecraft Worker 当前只提供 Windows 启动准备。");
  if (typeof sessionDirectory !== "string" || !sessionDirectory.trim()) throw new ValidationError("渲染会话目录不能为空。");
  if (!await existsAsFile(workerJarPath)) throw new ValidationError("未找到 DsLR Fabric Worker JAR：" + workerJarPath);

  const root = path.resolve(instanceRoot);
  const resolved = await readMinecraftLaunchDescriptor({ instanceRoot: root, minecraftVersion });
  const descriptor = resolved.descriptor;
  if (typeof descriptor.mainClass !== "string" || !descriptor.mainClass) {
    throw new ValidationError("版本 JSON 缺少 Minecraft 启动主类。");
  }

  const clientJarVersion = descriptor.jar || minecraftVersion;
  const clientJar = path.join(root, "versions", clientJarVersion, clientJarVersion + ".jar");
  const allowedLibraries = (descriptor.libraries ?? []).filter((library) => libraryAllowed(library, { platform }));
  const artifactPaths = allowedLibraries.map((library) => resolveLibraryPath(root, library));
  const nativePaths = allowedLibraries
    .map((library) => {
      const classifier = nativeClassifier(library, platform);
      return classifier ? resolveLibraryPath(root, library, { classifier }) : "";
    })
    .filter(Boolean);
  await requireFiles([clientJar, ...artifactPaths, ...nativePaths], "Minecraft 启动依赖");

  const workerRoot = path.resolve(launchRoot ?? path.join(sessionDirectory, "minecraft-worker"));
  const modsDirectory = path.join(workerRoot, "mods");
  const nativesDirectory = path.join(workerRoot, "natives");
  const logsDirectory = path.join(workerRoot, "logs");
  const launchLogPath = path.join(workerRoot, "lrs-worker-launch.log");
  await Promise.all([
    mkdir(modsDirectory, { recursive: true }),
    mkdir(logsDirectory, { recursive: true })
  ]);
  await copyFile(workerJarPath, path.join(modsDirectory, "litematic-render-worker.jar"));
  await extractNativeLibraries({ nativeJarPaths: nativePaths, nativesDirectory });

  const classpath = [clientJar, ...artifactPaths].join(path.delimiter);
  const assetIndexName = descriptor.assetIndex?.id ?? descriptor.assets ?? minecraftVersion;
  const values = {
    natives_directory: nativesDirectory,
    launcher_name: "DesktopLitematicRender",
    launcher_version: "1.0.0",
    classpath,
    classpath_separator: path.delimiter,
    library_directory: path.join(root, "libraries"),
    version_name: minecraftVersion,
    version_type: descriptor.type ?? "release",
    assets_root: path.join(root, "assets"),
    assets_index_name: assetIndexName,
    game_directory: workerRoot,
    auth_player_name: "DsLRRenderer",
    auth_uuid: "00000000000000000000000000000000",
    auth_access_token: "0",
    clientid: "",
    user_properties: "{}",
    user_type: "legacy",
    resolution_width: displayWidth,
    resolution_height: displayHeight
  };

  const jvmArguments = removeClasspathArguments(normalizeArgumentEntries(descriptor.arguments?.jvm, values, platform));
  const gameArguments = normalizeArgumentEntries(descriptor.arguments?.game, values, platform);
  const legacyGameArguments = !gameArguments.length && typeof descriptor.minecraftArguments === "string"
    ? descriptor.minecraftArguments.split(" ").map((value) => substituteArgument(value, values))
    : [];
  const command = javaExecutable || "javaw.exe";
  return {
    command,
    cwd: workerRoot,
    logFile: launchLogPath,
    args: [
      "-Dlrs.worker=true",
      "-Dlrs.session=" + path.resolve(sessionDirectory),
      ...jvmArguments,
      "-cp",
      classpath,
      descriptor.mainClass,
      ...(gameArguments.length ? gameArguments : legacyGameArguments)
    ],
    metadata: {
      minecraftVersion,
      clientJar,
      descriptorPath: resolved.descriptorPath,
      inheritedIds: resolved.inheritedIds,
      workerRoot,
      modsDirectory,
      nativesDirectory,
      launchLogPath,
      minecraftLogPath: path.join(logsDirectory, "latest.log"),
      eventsPath: path.join(path.resolve(sessionDirectory), "events.jsonl"),
      workerJarPath: path.resolve(workerJarPath)
    }
  };
}

export async function findBuiltWorkerJar(projectRoot) {
  const directory = path.join(projectRoot, "fabric-worker", "build", "libs");
  const info = await stat(directory).catch(() => null);
  if (!isDirectory(info)) return "";
  const entries = await readdir(directory, { withFileTypes: true });
  const candidates = entries
    .filter((entry) => entry.isFile() && /^litematic-render-worker-[^/]+\.jar$/i.test(entry.name) && !entry.name.includes("-sources") && !entry.name.includes("-bridge"))
    .map((entry) => path.join(directory, entry.name))
    .sort()
    .reverse();
  for (const candidate of candidates) {
    try {
      const capability = readZipEntry(await readFile(candidate), "lrs-renderer-capabilities.json");
      if (capability && JSON.parse(capability.toString("utf8")).png === true) return candidate;
    } catch {
    }
  }
  return "";
}

export async function findBridgeWorkerJar(projectRoot) {
  const directory = path.join(projectRoot, "fabric-worker", "build", "libs");
  const info = await stat(directory).catch(() => null);
  if (!isDirectory(info)) return "";
  const entries = await readdir(directory, { withFileTypes: true });
  const candidates = entries
    .filter((entry) => entry.isFile() && /^litematic-render-worker-[^/]+-bridge\.jar$/i.test(entry.name))
    .map((entry) => path.join(directory, entry.name))
    .sort()
    .reverse();
  for (const candidate of candidates) {
    try {
      const capability = readZipEntry(await readFile(candidate), "lrs-renderer-capabilities.json");
      const parsed = capability ? JSON.parse(capability.toString("utf8")) : null;
      if (parsed?.mode === "bridge" && parsed?.png !== true) return candidate;
    } catch {
    }
  }
  return "";
}
