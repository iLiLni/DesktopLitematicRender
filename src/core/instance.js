import { readdir, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

async function getStat(targetPath) {
  try {
    return await stat(targetPath);
  } catch {
    return null;
  }
}

async function exists(targetPath) {
  return Boolean(await getStat(targetPath));
}

async function isFile(targetPath) {
  return Boolean((await getStat(targetPath))?.isFile());
}

async function listDirectory(directoryPath, accepts) {
  const info = await getStat(directoryPath);
  if (!info?.isDirectory()) return [];
  const entries = await readdir(directoryPath, { withFileTypes: true });
  return entries.filter(accepts).map((entry) => path.join(directoryPath, entry.name)).sort();
}

function makeEntry(filePath, scope) {
  return { path: filePath, scope, name: path.basename(filePath) };
}

function mergeEntries(...groups) {
  const unique = new Map();
  for (const entry of groups.flat()) unique.set(entry.path, entry);
  return [...unique.values()];
}

async function inspectInputs(scope, basePath) {
  const resourcepacksPath = path.join(basePath, "resourcepacks");
  const schematicsPath = path.join(basePath, "schematics");
  const [resourcePacks, litematics] = await Promise.all([
    listDirectory(resourcepacksPath, (entry) => entry.isDirectory() || entry.isFile() && entry.name.toLowerCase().endsWith(".zip")),
    listDirectory(schematicsPath, (entry) => entry.isFile() && entry.name.toLowerCase().endsWith(".litematic"))
  ]);
  return {
    scope,
    root: basePath,
    resourcepacksPath,
    schematicsPath,
    resourcePackEntries: resourcePacks.map((filePath) => makeEntry(filePath, scope)),
    litematicEntries: litematics.map((filePath) => makeEntry(filePath, scope))
  };
}

export function commonMinecraftDirectories({ platform = process.platform, homeDirectory = os.homedir(), environment = process.env } = {}) {
  if (platform === "win32") {
    const appData = environment.APPDATA ?? path.join(homeDirectory, "AppData", "Roaming");
    return [path.join(appData, ".minecraft")];
  }
  if (platform === "darwin") return [path.join(homeDirectory, "Library", "Application Support", "minecraft")];
  return [path.join(homeDirectory, ".minecraft")];
}

export function resolveMinecraftTarget(targetPath, { selectedVersion = undefined } = {}) {
  if (typeof targetPath !== "string" || targetPath.trim() === "") {
    throw new TypeError("Minecraft target path must be a non-empty string.");
  }
  const resolvedPath = path.resolve(targetPath.trim());
  if (path.basename(path.dirname(resolvedPath)) === "versions") {
    return {
      suppliedPath: targetPath,
      kind: "version_directory",
      instanceRoot: path.dirname(path.dirname(resolvedPath)),
      selectedVersion: typeof selectedVersion === "string" && selectedVersion.trim() ? selectedVersion.trim() : path.basename(resolvedPath)
    };
  }
  if (path.basename(resolvedPath) === "versions") {
    return {
      suppliedPath: targetPath,
      kind: "versions_directory",
      instanceRoot: path.dirname(resolvedPath),
      selectedVersion
    };
  }
  return { suppliedPath: targetPath, kind: "instance_root", instanceRoot: resolvedPath, selectedVersion };
}

export async function inspectMinecraftInstance(instancePath) {
  const root = path.resolve(instancePath);
  const versionsPath = path.join(root, "versions");
  const [versionDirectories, inputs] = await Promise.all([
    listDirectory(versionsPath, (entry) => entry.isDirectory()),
    inspectInputs("instance", root)
  ]);
  return {
    root,
    exists: await exists(root),
    paths: {
      versionsPath,
      resourcepacksPath: inputs.resourcepacksPath,
      schematicsPath: inputs.schematicsPath
    },
    versions: versionDirectories.map((directory) => path.basename(directory)),
    resourcePacks: inputs.resourcePackEntries.map((entry) => entry.path),
    litematics: inputs.litematicEntries.map((entry) => entry.path),
    resourcePackEntries: inputs.resourcePackEntries,
    litematicEntries: inputs.litematicEntries,
    inputLocations: [inputs]
  };
}

export async function inspectMinecraftTarget(targetPath, options = {}) {
  const target = resolveMinecraftTarget(targetPath, options);
  const instance = await inspectMinecraftInstance(target.instanceRoot);
  const selectedVersion = target.selectedVersion ?? null;
  const versionDirectory = selectedVersion ? path.join(instance.paths.versionsPath, selectedVersion) : null;
  const versionInputs = versionDirectory ? await inspectInputs("version", versionDirectory) : null;
  const resourcePackEntries = mergeEntries(versionInputs?.resourcePackEntries ?? [], instance.resourcePackEntries);
  const litematicEntries = mergeEntries(versionInputs?.litematicEntries ?? [], instance.litematicEntries);
  const versionJsonPath = selectedVersion ? path.join(versionDirectory, `${selectedVersion}.json`) : null;
  const versionJarPath = selectedVersion ? path.join(versionDirectory, `${selectedVersion}.jar`) : null;

  return {
    ...instance,
    resourcePacks: resourcePackEntries.map((entry) => entry.path),
    litematics: litematicEntries.map((entry) => entry.path),
    resourcePackEntries,
    litematicEntries,
    inputLocations: versionInputs ? [versionInputs, ...instance.inputLocations] : instance.inputLocations,
    target,
    selectedVersion,
    selectedVersionAvailable: selectedVersion ? instance.versions.includes(selectedVersion) : false,
    selectedVersionPaths: selectedVersion
      ? {
          directory: versionDirectory,
          versionJsonPath,
          versionJarPath,
          resourcepacksPath: versionInputs.resourcepacksPath,
          schematicsPath: versionInputs.schematicsPath,
          hasVersionJson: await isFile(versionJsonPath),
          hasVersionJar: await isFile(versionJarPath)
        }
      : null
  };
}
