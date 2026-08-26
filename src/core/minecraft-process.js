import { spawn, spawnSync } from "node:child_process";
import { appendFileSync, closeSync, openSync, readFileSync } from "node:fs";

import { ValidationError } from "./errors.js";

const WINDOWS_PROCESS_QUERY = "Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') } | Select-Object ProcessId,Name,CommandLine | ConvertTo-Json -Compress";

function runText(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      ...options,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve(stdout);
      else reject(new ValidationError("无法读取 Java 进程：" + (stderr.trim() || "退出代码 " + code)));
    });
  });
}

function normalizeRows(raw) {
  if (!raw || !raw.trim()) return [];
  const value = JSON.parse(raw);
  const rows = Array.isArray(value) ? value : [value];
  return rows
    .map((row) => ({
      pid: Number(row.ProcessId),
      name: String(row.Name ?? ""),
      commandLine: String(row.CommandLine ?? "")
    }))
    .filter((row) => Number.isInteger(row.pid) && row.pid > 0 && row.commandLine);
}

export function isSameMinecraftVersionProcess(processInfo, minecraftVersion) {
  const version = String(minecraftVersion ?? "").trim();
  if (!version || !processInfo?.commandLine) return false;
  const commandLine = String(processInfo.commandLine).toLowerCase().replaceAll("/", "\\");
  const lowerVersion = version.toLowerCase();
  const versionDirectory = "\\versions\\" + lowerVersion + "\\";
  return commandLine.includes("--version " + lowerVersion)
    || commandLine.includes("--version \"" + lowerVersion + "\"")
    || commandLine.includes("--version=\"" + lowerVersion + "\"")
    || commandLine.includes("\"" + lowerVersion + ".json\"")
    || commandLine.includes(versionDirectory);
}

export function isReusableLrsWorkerProcess(processInfo) {
  const commandLine = String(processInfo?.commandLine ?? "").toLowerCase();
  return commandLine.includes("-dlrs.worker=true") || commandLine.includes("-dlrs.session=");
}

export async function findSameVersionMinecraftProcesses({
  minecraftVersion,
  platform = process.platform,
  run = runText
} = {}) {
  if (platform !== "win32") return [];
  if (!String(minecraftVersion ?? "").trim()) {
    throw new ValidationError("搜索 Minecraft 进程前必须提供目标版本。");
  }
  const raw = await run("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", WINDOWS_PROCESS_QUERY]);
  return normalizeRows(raw).filter((row) => isSameMinecraftVersionProcess(row, minecraftVersion));
}

export function launchHiddenProcess({ command, args = [], cwd, environment = process.env, logFile = "" } = {}) {
  if (typeof command !== "string" || !command.trim()) {
    throw new ValidationError("隐藏 Minecraft Worker 缺少启动命令。");
  }
  let logDescriptor;
  let child;
  try {
    if (logFile) logDescriptor = openSync(logFile, "a");
    child = spawn(command, args, {
      cwd,
      env: environment,
      windowsHide: true,
      detached: false,
      stdio: logDescriptor === undefined ? "ignore" : ["ignore", logDescriptor, logDescriptor]
    });
  } finally {
    if (logDescriptor !== undefined) closeSync(logDescriptor);
  }
  child.unref();
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("spawn", () => {
      child.off("error", reject);
      resolve(child);
    });
  });
}

function appendUnexpectedExit({ eventsPath, code, signal, logFile, minecraftLogPath }) {
  if (!eventsPath) return;
  try {
    const contents = readFileSync(eventsPath, "utf8");
    const lastLine = contents.trim().split("\n").at(-1);
    const lastEvent = lastLine ? JSON.parse(lastLine) : null;
    if (["completed", "failed"].includes(lastEvent?.type)) return;
    const exitDetail = signal ? `信号 ${signal}` : `退出代码 ${code ?? "未知"}`;
    const logs = [logFile, minecraftLogPath].filter(Boolean).join("；");
    const message = `隐藏 Fabric Worker 已退出（${exitDetail}）。请查看日志：${logs}`;
    const event = JSON.stringify({ protocolVersion: 1, type: "failed", stage: "process", message });
    appendFileSync(eventsPath, `${event}\n`, "utf8");
  } catch {
  }
}

export function terminateOwnedProcessSync({ pid, platform = process.platform } = {}) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  if (platform === "win32") {
    const result = spawnSync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore"
    });
    return result.status === 0;
  }
  try {
    process.kill(pid, "SIGTERM");
    return true;
  } catch {
    return false;
  }
}

export class MinecraftWorkerController {
  #owned = new Map();

  constructor({
    platform = process.platform,
    findExisting = findSameVersionMinecraftProcesses,
    launch = launchHiddenProcess,
    terminate = terminateOwnedProcessSync,
    canReuse = isReusableLrsWorkerProcess
  } = {}) {
    this.platform = platform;
    this.findExisting = findExisting;
    this.launch = launch;
    this.terminate = terminate;
    this.canReuse = canReuse;
  }

  async ensure({ sessionId, minecraftVersion, launchSpec }) {
    const normalizedSessionId = String(sessionId ?? "").trim();
    const normalizedVersion = String(minecraftVersion ?? "").trim();
    if (!normalizedSessionId) throw new ValidationError("启动 Worker 前必须创建渲染会话。");
    if (!normalizedVersion) throw new ValidationError("启动 Worker 前必须指定目标 Minecraft 版本。");

    const existing = await this.findExisting({
      minecraftVersion: normalizedVersion,
      platform: this.platform
    });
    const reusable = existing.filter((processInfo) => this.canReuse(processInfo));
    if (reusable.length) {
      return {
        mode: "reused",
        ownsProcess: false,
        minecraftVersion: normalizedVersion,
        process: reusable[0],
        matchingProcesses: existing
      };
    }

    const child = await this.launch(launchSpec);
    if (!Number.isInteger(child?.pid) || child.pid <= 0) {
      throw new ValidationError("隐藏 Minecraft Worker 未返回有效进程标识。");
    }
    const record = {
      sessionId: normalizedSessionId,
      minecraftVersion: normalizedVersion,
      pid: child.pid,
      startedAt: new Date().toISOString()
    };
    this.#owned.set(normalizedSessionId, record);
    child.once?.("exit", (code, signal) => {
      this.#owned.delete(normalizedSessionId);
      appendUnexpectedExit({
        eventsPath: launchSpec?.metadata?.eventsPath,
        code,
        signal,
        logFile: launchSpec?.metadata?.launchLogPath,
        minecraftLogPath: launchSpec?.metadata?.minecraftLogPath
      });
    });
    child.once?.("error", () => this.#owned.delete(normalizedSessionId));
    return {
      mode: "started",
      ownsProcess: true,
      minecraftVersion: normalizedVersion,
      process: { pid: child.pid, name: "DsLR Fabric Worker", commandLine: launchSpec?.command ?? "" },
      matchingProcesses: existing,
      record
    };
  }

  closeSession(sessionId) {
    const record = this.#owned.get(sessionId);
    if (!record) return false;
    this.#owned.delete(sessionId);
    return this.terminate({ pid: record.pid, platform: this.platform });
  }

  closeOwnedWorkers() {
    const records = [...this.#owned.values()];
    this.#owned.clear();
    return records.map((record) => ({
      ...record,
      stopped: this.terminate({ pid: record.pid, platform: this.platform })
    }));
  }

  getOwnedWorkers() {
    return [...this.#owned.values()];
  }
}
