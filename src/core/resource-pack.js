import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import { InputFormatError } from "./errors.js";
import { readZipEntries, readZipEntry } from "./zip.js";

async function directoryExists(directoryPath) {
  try {
    return (await stat(directoryPath)).isDirectory();
  } catch {
    return false;
  }
}

function descriptionToText(description) {
  if (typeof description === "string") return description;
  if (Array.isArray(description)) return description.map(descriptionToText).join("");
  if (description && typeof description === "object") {
    if (typeof description.text === "string") return description.text;
    if (Array.isArray(description.extra)) return description.extra.map(descriptionToText).join("");
  }
  return "";
}

function parseManifest(raw, sourceLabel) {
  let parsed;
  try {
    parsed = JSON.parse(raw.toString("utf8"));
  } catch {
    throw new InputFormatError(`${sourceLabel} contains invalid pack.mcmeta JSON.`);
  }
  const pack = parsed.pack;
  if (!pack || typeof pack !== "object") {
    throw new InputFormatError(`${sourceLabel} has no pack section in pack.mcmeta.`);
  }
  return {
    packFormat: pack.pack_format ?? null,
    supportedFormats: pack.supported_formats ?? null,
    description: descriptionToText(pack.description),
    raw: parsed
  };
}

export async function inspectResourcePack(packPath) {
  const info = await stat(packPath);
  if (info.isDirectory()) {
    const manifestPath = path.join(packPath, "pack.mcmeta");
    const manifest = parseManifest(await readFile(manifestPath), packPath);
    return {
      type: "directory",
      path: packPath,
      hasMinecraftAssets: await directoryExists(path.join(packPath, "assets", "minecraft")),
      ...manifest
    };
  }

  if (!info.isFile() || path.extname(packPath).toLowerCase() !== ".zip") {
    throw new InputFormatError("A resource pack must be a directory or a .zip file.");
  }
  const zip = await readFile(packPath);
  const entries = readZipEntries(zip);
  const manifestEntry = readZipEntry(zip, "pack.mcmeta");
  if (!manifestEntry) {
    throw new InputFormatError("Resource-pack ZIP has no root pack.mcmeta file.");
  }
  const manifest = parseManifest(manifestEntry, packPath);
  return {
    type: "zip",
    path: packPath,
    hasMinecraftAssets: [...entries.keys()].some((name) => name.startsWith("assets/minecraft/")),
    ...manifest
  };
}
