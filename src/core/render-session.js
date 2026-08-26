import { randomUUID } from "node:crypto";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";

import { ValidationError } from "./errors.js";
import {
  addResourcePackCommand,
  configureInstanceCommand,
  reloadResourcesCommand,
  toFabricWorkerRequest
} from "./worker-protocol.js";

async function inspectInput(filePath, label, type) {
  const info = await stat(filePath).catch(() => null);
  if (!info || (type === "file" && !info.isFile()) || (type === "resource-pack" && !info.isFile() && !info.isDirectory())) {
    throw new ValidationError(`${label}不存在或类型不正确：${filePath}`);
  }
  return {
    path: path.resolve(filePath),
    type: info.isDirectory() ? "directory" : "file",
    size: info.size,
    modifiedAt: info.mtime.toISOString()
  };
}

function collectPacks(job) {
  const paths = [
    ...job.resources.selectedInstancePackPaths.map((entry) => entry.path),
    ...job.resources.transientPacks.map((entry) => entry.path)
  ];
  const uniquePaths = [...new Set(paths)];
  if (uniquePaths.length !== paths.length) throw new ValidationError("实例资源包和临时资源包不能重复。");
  return uniquePaths.map((packPath, priority) => ({ path: packPath, priority }));
}

function assertJobPlan(job, plan) {
  if (!job?.id || !job.source?.instancePath || !plan || plan.jobId !== job.id || plan.createdFor !== "fabric") {
    throw new ValidationError("无法为无效的 Fabric 渲染任务创建会话。");
  }
}

function sessionPaths(root, id) {
  if (typeof id !== "string" || !/^[A-Za-z0-9][A-Za-z0-9-]{10,180}$/.test(id)) {
    throw new ValidationError("渲染会话标识无效。");
  }
  const directory = path.resolve(root, id);
  const normalizedRoot = path.resolve(root);
  if (!directory.startsWith(`${normalizedRoot}${path.sep}`)) throw new ValidationError("渲染会话目录无效。");
  return {
    directory,
    jobPath: path.join(directory, "job.json"),
    planPath: path.join(directory, "plan.json"),
    manifestPath: path.join(directory, "manifest.json"),
    commandsPath: path.join(directory, "commands.jsonl"),
    eventsPath: path.join(directory, "events.jsonl")
  };
}

function getStatus(events, fallback) {
  const lastEvent = events.at(-1) ?? null;
  if (!lastEvent) return fallback;
  if (lastEvent.type === "failed") return "failed";
  if (lastEvent.type === "completed") return "completed";
  if (lastEvent.type === "progress") return "rendering";
  if (lastEvent.type === "ready") return "worker_ready";
  return fallback;
}

export async function createRenderSession({ job, plan, sessionDirectory }) {
  assertJobPlan(job, plan);
  if (typeof sessionDirectory !== "string" || !sessionDirectory.trim()) throw new ValidationError("渲染会话根目录无效。");

  const packs = collectPacks(job);
  const [litematic, ...resourcePacks] = await Promise.all([
    inspectInput(job.source.litematicPath, "Litematic 文件", "file"),
    ...packs.map((pack) => inspectInput(pack.path, "资源包", "resource-pack"))
  ]);
  const outputDirectory = path.resolve(job.output.directory);
  await Promise.all([mkdir(outputDirectory, { recursive: true }), mkdir(sessionDirectory, { recursive: true })]);

  const id = `${new Date().toISOString().replace(/[:.]/g, "-")}-${randomUUID()}`;
  const paths = sessionPaths(sessionDirectory, id);
  await mkdir(paths.directory, { recursive: false });

  const commands = [
    configureInstanceCommand({ instancePath: job.source.instancePath, minecraftVersion: job.source.minecraftVersion }),
    ...packs.map((pack) => addResourcePackCommand(pack)),
    reloadResourcesCommand(),
    toFabricWorkerRequest(job, plan)
  ];
  const manifest = {
    schemaVersion: 1,
    id,
    status: "waiting_for_fabric_worker",
    createdAt: new Date().toISOString(),
    outputDirectory,
    litematic,
    resourcePacks,
    expectedOutputs: plan.viewTasks.map((task) => path.join(outputDirectory, task.outputFileName))
  };

  await Promise.all([
    writeFile(paths.jobPath, `${JSON.stringify(job, null, 2)}\n`, { flag: "wx" }),
    writeFile(paths.planPath, `${JSON.stringify(plan, null, 2)}\n`, { flag: "wx" }),
    writeFile(paths.manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx" }),
    writeFile(paths.commandsPath, `${commands.map((command) => JSON.stringify(command)).join("\n")}\n`, { flag: "wx" }),
    writeFile(paths.eventsPath, "", { flag: "wx" })
  ]);

  return {
    id,
    status: manifest.status,
    directory: paths.directory,
    jobPath: paths.jobPath,
    planPath: paths.planPath,
    manifestPath: paths.manifestPath,
    commandsPath: paths.commandsPath,
    eventsPath: paths.eventsPath,
    outputDirectory,
    expectedOutputs: manifest.expectedOutputs
  };
}

export async function createFabricProbeSession({ instancePath, minecraftVersion, sessionDirectory }) {
  if (typeof instancePath !== "string" || !instancePath.trim()) {
    throw new ValidationError("Fabric 启动检测缺少 Minecraft 游戏文件夹。");
  }
  if (typeof minecraftVersion !== "string" || !minecraftVersion.trim()) {
    throw new ValidationError("Fabric 启动检测缺少目标 Minecraft 版本。");
  }
  if (typeof sessionDirectory !== "string" || !sessionDirectory.trim()) {
    throw new ValidationError("Fabric 启动检测会话目录无效。");
  }

  const root = path.resolve(sessionDirectory);
  await mkdir(root, { recursive: true });
  const id = `${new Date().toISOString().replace(/[:.]/g, "-")}-${randomUUID()}`;
  const paths = sessionPaths(root, id);
  await mkdir(paths.directory, { recursive: false });

  const normalizedInstancePath = path.resolve(instancePath);
  const normalizedVersion = minecraftVersion.trim();
  const commands = [
    configureInstanceCommand({ instancePath: normalizedInstancePath, minecraftVersion: normalizedVersion }),
    reloadResourcesCommand()
  ];
  const manifest = {
    schemaVersion: 1,
    id,
    kind: "fabric_bridge_probe",
    status: "waiting_for_fabric_worker",
    createdAt: new Date().toISOString(),
    instancePath: normalizedInstancePath,
    minecraftVersion: normalizedVersion,
    expectedOutputs: []
  };

  await Promise.all([
    writeFile(paths.manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx" }),
    writeFile(paths.commandsPath, `${commands.map((command) => JSON.stringify(command)).join("\n")}\n`, { flag: "wx" }),
    writeFile(paths.eventsPath, "", { flag: "wx" })
  ]);

  return {
    id,
    kind: manifest.kind,
    status: manifest.status,
    directory: paths.directory,
    manifestPath: paths.manifestPath,
    commandsPath: paths.commandsPath,
    eventsPath: paths.eventsPath,
    expectedOutputs: []
  };
}

export async function getRenderSessionStatus({ sessionDirectory, id }) {
  const paths = sessionPaths(sessionDirectory, id);
  let manifest;
  try {
    manifest = JSON.parse(await readFile(paths.manifestPath, "utf8"));
  } catch {
    throw new ValidationError("未找到渲染会话。");
  }
  const events = (await readFile(paths.eventsPath, "utf8").catch(() => ""))
    .split("\n")
    .filter(Boolean)
    .flatMap((line) => {
      try {
        return [JSON.parse(line)];
      } catch {
        return [];
      }
    });
  return {
    id,
    status: getStatus(events, manifest.status),
    eventCount: events.length,
    lastEvent: events.at(-1) ?? null,
    outputDirectory: manifest.outputDirectory,
    expectedOutputs: manifest.expectedOutputs
  };
}
