import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawn } from "node:child_process";

import { inspectMinecraftTarget } from "../core/instance.js";
import { readLitematicMetadata } from "../core/nbt.js";
import { createRenderJob } from "../core/render-job.js";
import { createRenderPlan } from "../core/render-plan.js";
import { createFabricProbeSession, createRenderSession, getRenderSessionStatus } from "../core/render-session.js";
import { inspectResourcePack } from "../core/resource-pack.js";
import { findBridgeWorkerJar, findBuiltWorkerJar, prepareHiddenMinecraftWorker } from "../core/minecraft-launcher.js";
import { MinecraftWorkerController } from "../core/minecraft-process.js";

const MODULE_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIRECTORY = path.join(MODULE_DIRECTORY, "public");
const PROJECT_DIRECTORY = path.resolve(MODULE_DIRECTORY, "../..");
const DEFAULT_SESSION_DIRECTORY = path.join(process.cwd(), "runtime", "session-inputs");
const DEFAULT_RENDER_SESSION_DIRECTORY = path.join(process.cwd(), "runtime", "render-sessions");
const DEFAULT_PORT = 42817;
const MAX_JSON_BYTES = 1 * 1024 * 1024;
const MAX_UPLOAD_BYTES = 512 * 1024 * 1024;

const DEFAULT_PCL_TARGET = "";

const MIME_TYPES = Object.freeze({
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml"
});

class HttpError extends Error {
  constructor(statusCode, message) {
    super(message);
    this.name = "HttpError";
    this.statusCode = statusCode;
  }
}

function asNonEmptyPath(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new HttpError(400, `${label}不能为空。`);
  }
  return value.trim();
}

function sendJson(response, statusCode, payload) {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  response.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store"
  });
  response.end(body);
}

function sendText(response, statusCode, message) {
  const body = Buffer.from(message, "utf8");
  response.writeHead(statusCode, {
    "Content-Type": "text/plain; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store"
  });
  response.end(body);
}

async function readBody(request, maximumBytes) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maximumBytes) {
      throw new HttpError(413, `请求内容超过 ${Math.floor(maximumBytes / 1024 / 1024)} MB 限制。`);
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJson(request) {
  const body = await readBody(request, MAX_JSON_BYTES);
  try {
    return JSON.parse(body.toString("utf8"));
  } catch {
    throw new HttpError(400, "请求不是有效的 JSON。");
  }
}

function safeUploadName(value) {
  const baseName = path.basename(String(value ?? "").replaceAll("\\", "/"));
  const safeName = baseName.replace(/[^a-zA-Z0-9._()\-\u4e00-\u9fff]+/g, "_");
  if (!safeName || safeName === "." || safeName === "..") {
    throw new HttpError(400, "上传文件名无效。");
  }
  return safeName.slice(0, 160);
}

function validateUploadKind(kind, fileName) {
  const extension = path.extname(fileName).toLowerCase();
  if (kind === "litematic" && extension !== ".litematic") {
    throw new HttpError(400, "投影文件拖入区只接受 .litematic 文件。");
  }
  if (kind === "resource-pack" && extension !== ".zip") {
    throw new HttpError(400, "资源包文件拖入区只接受 .zip；文件夹请直接拖入或使用“选择资源包文件夹”。");
  }
  if (kind !== "litematic" && kind !== "resource-pack") {
    throw new HttpError(400, "未知的上传类型。");
  }
}

function folderSessionPath(sessionDirectory, id) {
  if (typeof id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
    throw new HttpError(400, "资源包文件夹会话无效。");
  }
  return path.join(sessionDirectory, "folders", id);
}

function safeRelativePath(value) {
  const normalized = String(value ?? "").replaceAll("\\", "/");
  const segments = normalized.split("/").filter(Boolean);
  if (!segments.length || normalized.startsWith("/") || segments.some((segment) => segment === "." || segment === "..")) {
    throw new HttpError(400, "资源包文件夹内的路径无效。");
  }
  return segments.map(safeUploadName);
}

async function assertFolderSession(sessionDirectory, id) {
  const folderPath = folderSessionPath(sessionDirectory, id);
  const info = await stat(folderPath).catch(() => null);
  if (!info?.isDirectory()) throw new HttpError(404, "资源包文件夹会话不存在或已失效。");
  return folderPath;
}

function errorResponse(response, error) {
  const statusCode = error instanceof HttpError ? error.statusCode : 400;
  sendJson(response, statusCode, {
    ok: false,
    error: error?.message ?? "发生未知错误。"
  });
}

function staticFilePath(pathname) {
  const requestedPath = pathname === "/" ? "/index.html" : pathname;
  const candidate = path.resolve(PUBLIC_DIRECTORY, `.${requestedPath}`);
  if (candidate !== PUBLIC_DIRECTORY && !candidate.startsWith(`${PUBLIC_DIRECTORY}${path.sep}`)) {
    throw new HttpError(403, "不允许访问该路径。");
  }
  return candidate;
}

async function serveStatic(response, pathname) {
  const filePath = staticFilePath(pathname);
  let info;
  try {
    info = await stat(filePath);
  } catch {
    sendText(response, 404, "未找到页面资源。");
    return;
  }
  if (!info.isFile()) {
    sendText(response, 404, "未找到页面资源。");
    return;
  }

  const body = await readFile(filePath);
  response.writeHead(200, {
    "Content-Type": MIME_TYPES[path.extname(filePath).toLowerCase()] ?? "application/octet-stream",
    "Content-Length": body.length,
    "Cache-Control": "no-store"
  });
  response.end(body);
}

async function handleApi(request, response, url, { sessionDirectory, renderSessionDirectory, defaultTargetPath, projectDirectory, workerController }) {
  if (request.method === "GET" && url.pathname === "/api/defaults") {
    sendJson(response, 200, {
      ok: true,
      defaultTargetPath,
      defaultOutputFolderName: "litematic-renders",
      maximumUploadMiB: Math.floor(MAX_UPLOAD_BYTES / 1024 / 1024)
    });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/instance/inspect") {
    const input = await readJson(request);
    const target = await inspectMinecraftTarget(asNonEmptyPath(input.targetPath, "Minecraft 游戏文件夹路径"), {
      selectedVersion: typeof input.selectedVersion === "string" ? input.selectedVersion : undefined
    });
    sendJson(response, 200, { ok: true, target });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/litematic/inspect") {
    const input = await readJson(request);
    const filePath = asNonEmptyPath(input.path, "Litematic 路径");
    if (path.extname(filePath).toLowerCase() !== ".litematic") {
      throw new HttpError(400, "请选择 .litematic 文件。");
    }
    const metadata = await readLitematicMetadata(filePath);
    sendJson(response, 200, { ok: true, metadata });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/resource-pack/inspect") {
    const input = await readJson(request);
    const pack = await inspectResourcePack(asNonEmptyPath(input.path, "资源包路径"));
    sendJson(response, 200, { ok: true, pack });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/render-plan") {
    const input = await readJson(request);
    const job = createRenderJob(input);
    const plan = createRenderPlan(job);
    sendJson(response, 200, { ok: true, job, plan });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/render-session") {
    const input = await readJson(request);
    const job = createRenderJob(input);
    const plan = createRenderPlan(job);
    const session = await createRenderSession({ job, plan, sessionDirectory: renderSessionDirectory });
    sendJson(response, 201, { ok: true, job, plan, session });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/render-start") {
    const input = await readJson(request);
    const job = createRenderJob(input.job ?? input);
    const plan = createRenderPlan(job);
    const workerJarPath = await findBuiltWorkerJar(projectDirectory);
    if (!workerJarPath) {
      throw new HttpError(409, "未找到已构建且支持 PNG 输出的 DsLR Fabric Renderer JAR。当前仅有启动桥接时，程序不会错误地启动它。");
    }
    const session = await createRenderSession({ job, plan, sessionDirectory: renderSessionDirectory });
    const launchSpec = await prepareHiddenMinecraftWorker({
      instanceRoot: job.source.instancePath,
      minecraftVersion: job.source.minecraftVersion,
      sessionDirectory: session.directory,
      workerJarPath,
      javaExecutable: typeof input.launcher?.javaExecutable === "string" && input.launcher.javaExecutable.trim()
        ? input.launcher.javaExecutable.trim()
        : undefined
    });
    const worker = await workerController.ensure({
      sessionId: session.id,
      minecraftVersion: job.source.minecraftVersion,
      launchSpec
    });
    sendJson(response, 201, { ok: true, job, plan, session, worker });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/fabric-bridge-probe") {
    const input = await readJson(request);
    const target = await inspectMinecraftTarget(asNonEmptyPath(input.targetPath, "Minecraft 游戏文件夹路径"), {
      selectedVersion: typeof input.minecraftVersion === "string" ? input.minecraftVersion : undefined
    });
    const minecraftVersion = target.selectedVersion;
    if (!minecraftVersion || !target.selectedVersionAvailable || !target.selectedVersionPaths?.hasVersionJson) {
      throw new HttpError(400, "目标 Minecraft 版本或同名版本 JSON 不可用。请先读取游戏文件夹并确认版本名。");
    }
    if (workerController.getOwnedWorkers().length) {
      throw new HttpError(409, "已有一个由 DsLR 启动的隐藏 Worker 正在运行。请先关闭 DsLR，或等待该 Worker 退出后再做启动检测。");
    }
    const workerJarPath = await findBridgeWorkerJar(projectDirectory);
    if (!workerJarPath) {
      throw new HttpError(409, "未找到可用于启动检测的 DsLR Fabric Bridge JAR。");
    }
    const session = await createFabricProbeSession({
      instancePath: target.root,
      minecraftVersion,
      sessionDirectory: renderSessionDirectory
    });
    const launchSpec = await prepareHiddenMinecraftWorker({
      instanceRoot: target.root,
      minecraftVersion,
      sessionDirectory: session.directory,
      workerJarPath,
      javaExecutable: typeof input.launcher?.javaExecutable === "string" && input.launcher.javaExecutable.trim()
        ? input.launcher.javaExecutable.trim()
        : undefined
    });
    const worker = await workerController.ensure({
      sessionId: session.id,
      minecraftVersion,
      launchSpec
    });
    sendJson(response, 201, {
      ok: true,
      session,
      worker,
      bridge: {
        jarPath: workerJarPath,
        launchLogPath: launchSpec.metadata.launchLogPath,
        minecraftLogPath: launchSpec.metadata.minecraftLogPath,
        workerRoot: launchSpec.metadata.workerRoot
      }
    });
    return true;
  }

  if (request.method === "GET" && url.pathname === "/api/render-session/status") {
    const status = await getRenderSessionStatus({
      sessionDirectory: renderSessionDirectory,
      id: asNonEmptyPath(url.searchParams.get("id"), "渲染会话标识")
    });
    sendJson(response, 200, { ok: true, status });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/session-input") {
    const kind = request.headers["x-lrs-input-kind"];
    const fileName = safeUploadName(request.headers["x-lrs-file-name"]);
    validateUploadKind(kind, fileName);
    const body = await readBody(request, MAX_UPLOAD_BYTES);
    if (body.length === 0) throw new HttpError(400, "上传文件为空。");

    await mkdir(sessionDirectory, { recursive: true });
    const storedPath = path.join(sessionDirectory, `${randomUUID()}-${fileName}`);
    await writeFile(storedPath, body, { flag: "wx" });
    sendJson(response, 201, {
      ok: true,
      path: storedPath,
      fileName,
      kind,
      size: body.length
    });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/session-folder/start") {
    const input = await readJson(request);
    if (input.kind !== "resource-pack") throw new HttpError(400, "该会话只支持资源包文件夹。");
    const id = randomUUID();
    const folderPath = folderSessionPath(sessionDirectory, id);
    await mkdir(folderPath, { recursive: true });
    sendJson(response, 201, {
      ok: true,
      id,
      path: folderPath,
      name: safeUploadName(input.folderName || "resource-pack")
    });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/session-folder/file") {
    const id = request.headers["x-lrs-folder-id"];
    const folderPath = await assertFolderSession(sessionDirectory, id);
    const segments = safeRelativePath(request.headers["x-lrs-relative-path"]);
    const outputPath = path.resolve(folderPath, ...segments);
    if (!outputPath.startsWith(`${folderPath}${path.sep}`)) throw new HttpError(403, "不允许写入资源包文件夹外。");
    const body = await readBody(request, MAX_UPLOAD_BYTES);
    if (body.length === 0) throw new HttpError(400, "资源包文件为空。");
    await mkdir(path.dirname(outputPath), { recursive: true });
    await writeFile(outputPath, body, { flag: "wx" });
    sendJson(response, 201, { ok: true, relativePath: segments.join("/"), size: body.length });
    return true;
  }

  if (request.method === "POST" && url.pathname === "/api/session-folder/complete") {
    const input = await readJson(request);
    const folderPath = await assertFolderSession(sessionDirectory, input.id);
    const pack = await inspectResourcePack(folderPath);
    sendJson(response, 200, { ok: true, pack });
    return true;
  }

  return false;
}

export function createShellServer({
  sessionDirectory = DEFAULT_SESSION_DIRECTORY,
  renderSessionDirectory = DEFAULT_RENDER_SESSION_DIRECTORY,
  defaultTargetPath = DEFAULT_PCL_TARGET,
  projectDirectory = PROJECT_DIRECTORY,
  workerController = new MinecraftWorkerController({ canReuse: () => false })
} = {}) {
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://127.0.0.1");
      if (url.pathname.startsWith("/api/")) {
        const handled = await handleApi(request, response, url, {
          sessionDirectory,
          renderSessionDirectory,
          defaultTargetPath,
          projectDirectory,
          workerController
        });
        if (!handled) sendJson(response, 404, { ok: false, error: "未找到此本地接口。" });
        return;
      }
      if (request.method !== "GET" && request.method !== "HEAD") {
        sendText(response, 405, "只支持 GET 页面请求。");
        return;
      }
      if (request.method === "HEAD") {
        response.writeHead(204, { "Cache-Control": "no-store" });
        response.end();
        return;
      }
      await serveStatic(response, url.pathname);
    } catch (error) {
      errorResponse(response, error);
    }
  });
  server.lrsWorkerController = workerController;
  server.once("close", () => workerController.closeOwnedWorkers());
  return server;
}

export async function startShellServer(options = {}) {
  const server = createShellServer(options);
  const host = options.host ?? "127.0.0.1";
  const port = Number.isInteger(options.port) ? options.port : DEFAULT_PORT;
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  const actualPort = typeof address === "object" && address ? address.port : port;
  return {
    server,
    url: `http://${host}:${actualPort}/`
  };
}

export function openBrowser(url) {
  const options = { detached: true, stdio: "ignore" };
  if (process.platform === "win32") {
    spawn("cmd", ["/c", "start", "", url], options).unref();
    return;
  }
  if (process.platform === "darwin") {
    spawn("open", [url], options).unref();
    return;
  }
  spawn("xdg-open", [url], options).unref();
}

function parseCommandLine(argumentsList) {
  const options = {};
  for (const argument of argumentsList) {
    if (argument === "--open") options.open = true;
    if (argument.startsWith("--port=")) options.port = Number(argument.slice("--port=".length));
  }
  if (options.port !== undefined && (!Number.isInteger(options.port) || options.port < 0 || options.port > 65535)) {
    throw new Error("--port 必须是 0 到 65535 的整数；0 表示自动选择空闲端口。");
  }
  return options;
}

const invokedFile = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : "";
if (import.meta.url === invokedFile) {
  const options = parseCommandLine(process.argv.slice(2));
  const started = await startShellServer(options);
  const stop = () => started.server.close(() => process.exit(0));
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  process.once("exit", () => started.server.lrsWorkerController.closeOwnedWorkers());
  process.stdout.write(`Litematic 静态图渲染器界面已启动：${started.url}\n`);
  process.stdout.write("仅监听本机 127.0.0.1；按 Ctrl+C 结束。\n");
  if (options.open) openBrowser(started.url);
}
