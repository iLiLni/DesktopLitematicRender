import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { startShellServer } from "../src/shell/server.mjs";

async function postJson(baseUrl, pathname, body) {
  const response = await fetch(new URL(pathname, baseUrl), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return { response, body: await response.json() };
}

test("Windows launchers use ASCII-safe batch files and keep their console open", async () => {
  const root = path.resolve(".");
  const launcher = await readFile(path.join(root, "LRS_START_SAFE.bat"), "utf8");
  const helper = await readFile(path.join(root, "LRS_PCL_INFO.bat"), "utf8");
  assert.match(launcher, /Press any key to close this window/);
  assert.match(launcher, /node "%APP_ROOT%src\\shell\\server\.mjs" --open --port=0/);
  assert.match(helper, /26\.1\.2-Fabric 0\.19\.3/);
  assert.match(helper, /Version JSON files/);
  assert.match(helper, /javaw\.exe/);
  for (const content of [launcher, helper]) {
    assert.equal(/[^\x00-\x7F]/.test(content), false);
  }
});

test("local UI shell serves Chinese page and accepts a direct PCL version directory", async (context) => {
  const temporaryRoot = await mkdtemp(path.join(process.cwd(), "shell-test-"));
  const minecraftRoot = path.join(temporaryRoot, ".minecraft");
  const versionName = "26.1.2-Fabric 0.19.3";
  const versionDirectory = path.join(minecraftRoot, "versions", versionName);
  const sessionDirectory = path.join(temporaryRoot, "session-inputs");
  await mkdir(versionDirectory, { recursive: true });
  await writeFile(path.join(versionDirectory, `${versionName}.json`), "{}");
  await writeFile(path.join(versionDirectory, `${versionName}.jar`), "placeholder");

  const started = await startShellServer({
    host: "127.0.0.1",
    port: 0,
    sessionDirectory,
    defaultTargetPath: versionDirectory,
    projectDirectory: path.join(temporaryRoot, "empty-project")
  });
  context.after(async () => {
    await new Promise((resolve, reject) => started.server.close((error) => error ? reject(error) : resolve()));
    await rm(temporaryRoot, { recursive: true, force: true });
  });

  const page = await fetch(started.url);
  assert.equal(page.status, 200);
  const pageText = await page.text();
  assert.match(pageText, /Litematic 静态图渲染器/);
  assert.match(pageText, /测试 Fabric 隐藏启动/);

  const inspected = await postJson(started.url, "/api/instance/inspect", { targetPath: versionDirectory });
  assert.equal(inspected.response.status, 200);
  assert.equal(inspected.body.target.target.kind, "version_directory");
  assert.equal(inspected.body.target.root, minecraftRoot);
  assert.equal(inspected.body.target.selectedVersion, versionName);
  assert.equal(inspected.body.target.selectedVersionPaths.hasVersionJson, true);

  const planned = await postJson(started.url, "/api/render-plan", {
    source: {
      litematicPath: path.join(temporaryRoot, "machine.litematic"),
      instancePath: minecraftRoot,
      minecraftVersion: versionName
    },
    resources: { transientPacks: [], selectedInstancePackPaths: [] },
    camera: {
      projections: ["orthographic", "perspective"],
      baseAzimuthDeg: 45,
      elevationDeg: 35.264389682754654,
      selectedOffsetsDeg: [0, 90, 180, 270],
      includeTopDown: false
    },
    lighting: { mode: "technical_fullbright" },
    output: {
      directory: path.join(temporaryRoot, "out"),
      background: "transparent",
      resolution: { width: 1920, height: 1080 },
      namingPrefix: "machine"
    },
    execution: { framebufferMode: "single", directionSchedule: "sequential", parallelWorkers: 1 }
  });
  assert.equal(planned.response.status, 200);
  assert.equal(planned.body.plan.createdFor, "fabric");
  assert.equal(planned.body.plan.viewTasks.length, 8);

  const litematicPath = path.join(temporaryRoot, "machine.litematic");
  const outputDirectory = path.join(temporaryRoot, "out");
  await writeFile(litematicPath, "placeholder");
  const saved = await postJson(started.url, "/api/render-session", {
    source: {
      litematicPath,
      instancePath: minecraftRoot,
      minecraftVersion: versionName
    },
    resources: { transientPacks: [], selectedInstancePackPaths: [] },
    camera: {
      projections: ["orthographic"],
      baseAzimuthDeg: 45,
      elevationDeg: 35.264389682754654,
      selectedOffsetsDeg: [0],
      includeTopDown: false
    },
    lighting: { mode: "technical_fullbright" },
    output: {
      directory: outputDirectory,
      background: "transparent",
      resolution: { width: 1920, height: 1080 },
      namingPrefix: "machine"
    },
    execution: { framebufferMode: "single", directionSchedule: "sequential", parallelWorkers: 1 }
  });
  assert.equal(saved.response.status, 201);
  assert.equal(saved.body.session.status, "waiting_for_fabric_worker");
  assert.equal(saved.body.session.expectedOutputs.length, 1);
  const savedStatusResponse = await fetch(new URL(`/api/render-session/status?id=${encodeURIComponent(saved.body.session.id)}`, started.url));
  const savedStatus = await savedStatusResponse.json();
  assert.equal(savedStatusResponse.status, 200);
  assert.equal(savedStatus.status.status, "waiting_for_fabric_worker");

  const blockedStart = await postJson(started.url, "/api/render-start", {
    job: {
      source: {
        litematicPath,
        instancePath: minecraftRoot,
        minecraftVersion: versionName
      },
      resources: { transientPacks: [], selectedInstancePackPaths: [] },
      camera: {
        projections: ["orthographic"],
        baseAzimuthDeg: 45,
        elevationDeg: 35.264389682754654,
        selectedOffsetsDeg: [0],
        includeTopDown: false
      },
      lighting: { mode: "technical_fullbright" },
      output: {
        directory: outputDirectory,
        background: "transparent",
        resolution: { width: 1920, height: 1080 },
        namingPrefix: "machine"
      },
      execution: { framebufferMode: "single", directionSchedule: "sequential", parallelWorkers: 1 }
    }
  });
  assert.equal(blockedStart.response.status, 409);
  assert.match(blockedStart.body.error, /PNG 输出/);

  const folderStart = await postJson(started.url, "/api/session-folder/start", { kind: "resource-pack", folderName: "folder-pack" });
  assert.equal(folderStart.response.status, 201);
  for (const [relativePath, content] of [
    ["pack.mcmeta", JSON.stringify({ pack: { pack_format: 34, description: "Folder upload" } })],
    ["assets/minecraft/textures/block/stone.png", "placeholder"]
  ]) {
    const upload = await fetch(new URL("/api/session-folder/file", started.url), {
      method: "POST",
      headers: {
        "x-lrs-folder-id": folderStart.body.id,
        "x-lrs-relative-path": relativePath
      },
      body: content
    });
    assert.equal(upload.status, 201);
  }
  const folderComplete = await postJson(started.url, "/api/session-folder/complete", { id: folderStart.body.id });
  assert.equal(folderComplete.response.status, 200);
  assert.equal(folderComplete.body.pack.type, "directory");
  assert.equal(folderComplete.body.pack.hasMinecraftAssets, true);
});
