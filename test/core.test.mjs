import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { deflateSync, gunzipSync, gzipSync } from "node:zlib";

import {
  buildDiagonalViews,
  buildTopDownView,
  createObliqueCamera
} from "../src/core/camera.js";
import { applyAngleInput, getCameraControls } from "../src/core/camera-controls.js";
import { DEFAULT_DIAGONAL_AZIMUTH_DEG, DEFAULT_ISOMETRIC_ELEVATION_DEG, Projection, RESOLUTION_PRESETS } from "../src/core/constants.js";
import { inspectMinecraftInstance, inspectMinecraftTarget, resolveMinecraftTarget } from "../src/core/instance.js";
import { readLitematicMetadataFromBuffer, Tag } from "../src/core/nbt.js";
import { createRenderJob } from "../src/core/render-job.js";
import { createRenderPlan, createTiles } from "../src/core/render-plan.js";
import { createFabricProbeSession, createRenderSession, getRenderSessionStatus } from "../src/core/render-session.js";
import { inspectResourcePack } from "../src/core/resource-pack.js";
import { MinecraftWorkerController, isSameMinecraftVersionProcess } from "../src/core/minecraft-process.js";
import { findBridgeWorkerJar, findBuiltWorkerJar, libraryAllowed, prepareHiddenMinecraftWorker, readMinecraftLaunchDescriptor } from "../src/core/minecraft-launcher.js";
import { FabricWorkerSession, SessionState } from "../src/core/worker-session.js";

function baseJob(overrides = {}) {
  return {
    id: "test-job",
    source: { litematicPath: "/tmp/machine.litematic", instancePath: "/tmp/.minecraft", minecraftVersion: "1.21.1" },
    resources: { transientPacks: [], selectedInstancePackPaths: [] },
    camera: {
      projections: ["orthographic"],
      baseAzimuthDeg: 45,
      elevationDeg: DEFAULT_ISOMETRIC_ELEVATION_DEG,
      selectedOffsetsDeg: [0, 90, 180, 270],
      includeTopDown: false
    },
    lighting: { mode: "technical_fullbright", ambientOcclusion: false },
    output: { directory: "/tmp/out", background: "transparent", resolution: RESOLUTION_PRESETS["1080p"], namingPrefix: "machine" },
    execution: { framebufferMode: "single", directionSchedule: "sequential", parallelWorkers: 1 },
    ...overrides
  };
}

function intBuffer(value) {
  const buffer = Buffer.alloc(4);
  buffer.writeInt32BE(value);
  return buffer;
}

function stringBuffer(value) {
  const encoded = Buffer.from(value, "utf8");
  const header = Buffer.alloc(2);
  header.writeUInt16BE(encoded.length);
  return Buffer.concat([header, encoded]);
}

function namedTag(type, name, payload) {
  return Buffer.concat([Buffer.from([type]), stringBuffer(name), payload]);
}

function compoundPayload(entries) {
  return Buffer.concat([...entries, Buffer.from([Tag.END])]);
}

function makeLitematicBuffer() {
  const metadata = compoundPayload([
    namedTag(Tag.STRING, "Name", stringBuffer("Clock")),
    namedTag(Tag.STRING, "Author", stringBuffer("Tester")),
    namedTag(Tag.INT, "TotalBlocks", intBuffer(42))
  ]);
  const regions = compoundPayload([
    namedTag(Tag.LONG_ARRAY, "BlockStates", Buffer.concat([intBuffer(1), Buffer.alloc(8)]))
  ]);
  const root = Buffer.concat([
    Buffer.from([Tag.COMPOUND]),
    stringBuffer("Litematic"),
    namedTag(Tag.INT, "Version", intBuffer(6)),
    namedTag(Tag.INT, "MinecraftDataVersion", intBuffer(3465)),
    namedTag(Tag.COMPOUND, "Metadata", metadata),
    namedTag(Tag.COMPOUND, "Regions", regions),
    Buffer.from([Tag.END])
  ]);
  return gzipSync(root);
}

function makeStoredZip(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const [name, raw] of Object.entries(entries)) {
    const nameBuffer = Buffer.from(name, "utf8");
    const data = Buffer.isBuffer(raw) ? raw : Buffer.from(raw, "utf8");
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuffer.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, nameBuffer, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuffer.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, nameBuffer);
    offset += local.length + nameBuffer.length + data.length;
  }
  const central = Buffer.concat(centralParts);
  const local = Buffer.concat(localParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(Object.keys(entries).length, 8);
  eocd.writeUInt16LE(Object.keys(entries).length, 10);
  eocd.writeUInt32LE(central.length, 12);
  eocd.writeUInt32LE(local.length, 16);
  return Buffer.concat([local, central, eocd]);
}

test("diagonal camera defaults to +X +Z and forms four diagonal directions", () => {
  const views = buildDiagonalViews({ projection: Projection.ORTHOGRAPHIC });
  assert.equal(views.length, 4);
  assert.deepEqual(views.map((view) => view.camera.azimuthDeg), [45, 135, 225, 315]);
  assert.equal(views[0].id, "orthographic_from_posx_posz");
  assert.equal(views[3].id, "orthographic_from_posx_negz");
  assert.equal(views[0].camera.elevationDeg, DEFAULT_ISOMETRIC_ELEVATION_DEG);
});

test("custom azimuth and elevation are retained for a single selected diagonal-relative view", () => {
  const views = buildDiagonalViews({
    projection: Projection.PERSPECTIVE,
    baseAzimuthDeg: 17.5,
    elevationDeg: 27.25,
    selectedOffsetsDeg: [0]
  });
  assert.equal(views.length, 1);
  assert.equal(views[0].camera.azimuthDeg, 17.5);
  assert.equal(views[0].camera.elevationDeg, 27.25);
  assert.match(views[0].id, /^perspective_from_custom_/);
});

test("UI-neutral camera controls expose both slider and exact numeric inputs", () => {
  const controls = getCameraControls({ baseAzimuthDeg: 45, elevationDeg: DEFAULT_ISOMETRIC_ELEVATION_DEG });
  assert.equal(controls.azimuth.slider.step, 0.1);
  assert.equal(controls.azimuth.numericInput.step, 0.01);
  assert.equal(controls.elevation.slider.max, 89.9);
  assert.equal(applyAngleInput({}, "baseAzimuthDeg", "-45").baseAzimuthDeg, 315);
  assert.equal(applyAngleInput({}, "elevationDeg", "20.5").elevationDeg, 20.5);
});

test("camera points at its target from the expected diagonal position", () => {
  const camera = createObliqueCamera({ azimuthDeg: DEFAULT_DIAGONAL_AZIMUTH_DEG, elevationDeg: DEFAULT_ISOMETRIC_ELEVATION_DEG });
  assert.ok(camera.position[0] > 0);
  assert.ok(camera.position[1] > 0);
  assert.ok(camera.position[2] > 0);
  assert.ok(Math.abs(Math.hypot(...camera.forward) - 1) < 1e-10);
  const top = buildTopDownView({ baseAzimuthDeg: 45 });
  assert.equal(top.camera.elevationDeg, 90);
});

test("render jobs remain Fabric-only and generate a deterministic sequential plan", () => {
  const job = createRenderJob(baseJob());
  const plan = createRenderPlan(job);
  assert.equal(job.execution.workerMode, "fabric");
  assert.equal(plan.workers.length, 1);
  assert.equal(plan.viewTasks.length, 4);
  assert.equal(plan.risk.severity, "low");
  assert.equal(plan.viewTasks[0].outputFileName, "machine__orthographic_from_posx_posz__1920x1080.png");
});

test("one job can batch orthographic and perspective diagonal view sets", () => {
  const job = createRenderJob(
    baseJob({
      camera: {
        projections: ["orthographic", "perspective"],
        baseAzimuthDeg: 45,
        elevationDeg: DEFAULT_ISOMETRIC_ELEVATION_DEG,
        selectedOffsetsDeg: [0],
        includeTopDown: true
      }
    })
  );
  const plan = createRenderPlan(job);
  assert.deepEqual(job.camera.projections, ["orthographic", "perspective"]);
  assert.deepEqual(plan.viewTasks.map((task) => task.view.id), [
    "orthographic_from_posx_posz",
    "perspective_from_posx_posz",
    "top_down"
  ]);
});

test("8K tiled parallel planning warns about high-resource worker mode", () => {
  const input = baseJob({
    output: { directory: "/tmp/out", background: "white", resolution: RESOLUTION_PRESETS["4320p"], namingPrefix: "machine" },
    execution: { framebufferMode: "tiled", tileEdgePx: 2048, directionSchedule: "parallel", parallelWorkers: 4 }
  });
  const plan = createRenderPlan(createRenderJob(input));
  assert.equal(plan.workers.length, 4);
  assert.equal(plan.viewTasks[0].tiles.length, 12);
  assert.equal(plan.risk.severity, "high");
  assert.ok(plan.risk.warnings.some((warning) => warning.code === "parallel_fabric_workers"));
  assert.equal(createTiles(5, 3, 2).length, 6);
});

test("Minecraft process controller reuses a matching process and only closes owned workers", async () => {
  const stopped = [];
  let launchCount = 0;
  const controller = new MinecraftWorkerController({
    platform: "win32",
    findExisting: async ({ minecraftVersion }) => minecraftVersion === "already-running"
      ? [{ pid: 88, name: "javaw.exe", commandLine: "--version already-running -Dlrs.worker=true" }]
      : [],
    launch: async () => {
      launchCount += 1;
      return { pid: 77, once: () => {} };
    },
    terminate: ({ pid }) => {
      stopped.push(pid);
      return true;
    }
  });

  const reused = await controller.ensure({
    sessionId: "session-reused",
    minecraftVersion: "already-running",
    launchSpec: { command: "javaw.exe" }
  });
  assert.equal(reused.mode, "reused");
  assert.equal(reused.ownsProcess, false);
  assert.equal(launchCount, 0);
  assert.equal(controller.closeSession("session-reused"), false);

  const started = await controller.ensure({
    sessionId: "session-owned",
    minecraftVersion: "new-version",
    launchSpec: { command: "javaw.exe" }
  });
  assert.equal(started.mode, "started");
  assert.equal(started.ownsProcess, true);
  assert.equal(launchCount, 1);
  assert.deepEqual(controller.getOwnedWorkers().map((worker) => worker.pid), [77]);
  assert.deepEqual(controller.closeOwnedWorkers().map((worker) => worker.stopped), [true]);
  assert.deepEqual(stopped, [77]);
});

test("Minecraft process matching recognizes launcher arguments and version folders", () => {
  assert.equal(isSameMinecraftVersionProcess({ commandLine: 'javaw.exe --version "26.1.2-Fabric 0.19.3"' }, "26.1.2-Fabric 0.19.3"), true);
  assert.equal(isSameMinecraftVersionProcess({ commandLine: "javaw.exe -DgameDir=D:/Release/.minecraft/versions/26.1.2-Fabric 0.19.3/" }, "26.1.2-Fabric 0.19.3"), true);
  assert.equal(isSameMinecraftVersionProcess({ commandLine: "javaw.exe --version different" }, "26.1.2-Fabric 0.19.3"), false);
});

test("ordinary matching Minecraft clients are never reused as a DsLR worker", async () => {
  let launched = 0;
  const controller = new MinecraftWorkerController({
    platform: "win32",
    findExisting: async () => [{ pid: 99, name: "javaw.exe", commandLine: "--version 26.1.2-Fabric 0.19.3" }],
    launch: async () => {
      launched += 1;
      return { pid: 101, once: () => {} };
    },
    terminate: () => true
  });
  const result = await controller.ensure({
    sessionId: "isolated-worker",
    minecraftVersion: "26.1.2-Fabric 0.19.3",
    launchSpec: { command: "javaw.exe" }
  });
  assert.equal(result.mode, "started");
  assert.equal(result.matchingProcesses[0].pid, 99);
  assert.equal(launched, 1);
});

test("unexpected hidden Worker exit is recorded in its session event stream", async () => {
  const temporary = await mkdtemp(path.join(process.cwd(), ".tmp-lrs-worker-exit-"));
  try {
    const eventsPath = path.join(temporary, "events.jsonl");
    await writeFile(eventsPath, "");
    const child = new EventEmitter();
    child.pid = 123;
    const controller = new MinecraftWorkerController({
      platform: "win32",
      findExisting: async () => [],
      launch: async () => child,
      terminate: () => true
    });
    await controller.ensure({
      sessionId: "exit-event-session",
      minecraftVersion: "26.1.2-Fabric 0.19.3",
      launchSpec: {
        command: "javaw.exe",
        metadata: {
          eventsPath,
          launchLogPath: "C:\\worker\\lrs-worker-launch.log",
          minecraftLogPath: "C:\\worker\\logs\\latest.log"
        }
      }
    });
    child.emit("exit", 1, null);
    const event = JSON.parse((await readFile(eventsPath, "utf8")).trim());
    assert.equal(event.type, "failed");
    assert.match(event.message, /退出代码 1/);
    assert.match(event.message, /lrs-worker-launch\.log/);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("hidden Fabric launch preparation resolves inherited version metadata without changing the instance", async () => {
  const temporary = await mkdtemp(path.join(process.cwd(), ".tmp-lrs-launch-"));
  try {
    const root = path.join(temporary, ".minecraft");
    const versions = path.join(root, "versions");
    const baseDirectory = path.join(versions, "base");
    const fabricDirectory = path.join(versions, "fabric");
    const sessionDirectory = path.join(temporary, "session");
    const workerJar = path.join(temporary, "litematic-render-worker.jar");
    await mkdir(baseDirectory, { recursive: true });
    await mkdir(fabricDirectory, { recursive: true });
    await mkdir(sessionDirectory, { recursive: true });
    await writeFile(path.join(baseDirectory, "base.jar"), "client");
    await writeFile(workerJar, "worker");
    const token = "$" + "{";
    await writeFile(path.join(baseDirectory, "base.json"), JSON.stringify({
      id: "base",
      mainClass: "net.fabricmc.loader.impl.launch.knot.KnotClient",
      assetIndex: { id: "assets-test" },
      libraries: [
        {
          name: "example:base:1",
          downloads: { artifact: { path: "example/base/1/base-1.jar" } }
        },
        {
          name: "example:native:1",
          downloads: {
            artifact: { path: "example/native/1/native-1.jar" },
            classifiers: { "natives-windows-64": { path: "example/native/1/native-1-natives-windows-64.jar" } }
          },
          natives: { windows: "natives-windows-" + token + "arch}" }
        }
      ],
      arguments: {
        jvm: ["-Djava.library.path=" + token + "natives_directory}", "-cp", token + "classpath}"],
        game: ["--gameDir", token + "game_directory}", "--assetsDir", token + "assets_root}", "--width", token + "resolution_width}"]
      }
    }));
    await writeFile(path.join(fabricDirectory, "fabric.json"), JSON.stringify({
      id: "fabric",
      inheritsFrom: "base",
      jar: "base",
      libraries: [],
      arguments: { game: ["--height", token + "resolution_height}"] }
    }));
    const artifact = path.join(root, "libraries", "example", "base", "1", "base-1.jar");
    const nativeArtifact = path.join(root, "libraries", "example", "native", "1", "native-1.jar");
    const nativeJar = path.join(root, "libraries", "example", "native", "1", "native-1-natives-windows-64.jar");
    await mkdir(path.dirname(artifact), { recursive: true });
    await mkdir(path.dirname(nativeArtifact), { recursive: true });
    await writeFile(artifact, "base-library");
    await writeFile(nativeArtifact, "native-library");
    await writeFile(nativeJar, makeStoredZip({ "bin/render.dll": Buffer.from([1, 2, 3]) }));

    const descriptor = await readMinecraftLaunchDescriptor({ instanceRoot: root, minecraftVersion: "fabric" });
    assert.deepEqual(descriptor.inheritedIds, ["base"]);
    assert.equal(descriptor.descriptor.mainClass, "net.fabricmc.loader.impl.launch.knot.KnotClient");
    const spec = await prepareHiddenMinecraftWorker({
      instanceRoot: root,
      minecraftVersion: "fabric",
      sessionDirectory,
      workerJarPath: workerJar,
      javaExecutable: "C:\\Java\\javaw.exe",
      platform: "win32"
    });
    assert.equal(spec.command, "C:\\Java\\javaw.exe");
    assert.ok(spec.args.includes("-Dlrs.worker=true"));
    assert.ok(spec.args.includes("-Dlrs.session=" + sessionDirectory));
    assert.ok(spec.args.includes("net.fabricmc.loader.impl.launch.knot.KnotClient"));
    assert.ok(spec.args.includes("--width"));
    assert.ok(spec.args.includes("854"));
    assert.equal(spec.logFile, path.join(spec.metadata.workerRoot, "lrs-worker-launch.log"));
    assert.equal(spec.metadata.eventsPath, path.join(sessionDirectory, "events.jsonl"));
    assert.equal(await readFile(path.join(spec.metadata.modsDirectory, "litematic-render-worker.jar"), "utf8"), "worker");
    assert.deepEqual(await readFile(path.join(spec.metadata.nativesDirectory, "render.dll")), Buffer.from([1, 2, 3]));
    assert.equal(await readFile(path.join(baseDirectory, "base.jar"), "utf8"), "client");
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("Minecraft library rules only allow the matching Windows entries", () => {
  assert.equal(libraryAllowed({ rules: [{ action: "allow", os: { name: "windows" } }] }, { platform: "win32" }), true);
  assert.equal(libraryAllowed({ rules: [{ action: "allow", os: { name: "linux" } }] }, { platform: "win32" }), false);
});

test("only a production Fabric Worker JAR is eligible for automatic launch", async () => {
  const temporary = await mkdtemp(path.join(process.cwd(), ".tmp-lrs-worker-jar-"));
  try {
    const output = path.join(temporary, "fabric-worker", "build", "libs");
    await mkdir(output, { recursive: true });
    const bridgeJar = path.join(output, "litematic-render-worker-1.0.0-bridge.jar");
    await writeFile(bridgeJar, "bridge");
    assert.equal(await findBuiltWorkerJar(temporary), "");
    assert.equal(await findBridgeWorkerJar(temporary), "");
    await writeFile(bridgeJar, makeStoredZip({ "lrs-renderer-capabilities.json": JSON.stringify({ mode: "bridge", png: false }) }));
    assert.equal(await findBridgeWorkerJar(temporary), bridgeJar);
    const productionJar = path.join(output, "litematic-render-worker-1.0.0.jar");
    await writeFile(productionJar, makeStoredZip({ "lrs-renderer-capabilities.json": JSON.stringify({ png: false }) }));
    assert.equal(await findBuiltWorkerJar(temporary), "");
    await writeFile(productionJar, makeStoredZip({ "lrs-renderer-capabilities.json": JSON.stringify({ png: true }) }));
    assert.equal(await findBuiltWorkerJar(temporary), productionJar);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("Fabric bridge probe creates an isolated command stream without a render job", async () => {
  const temporary = await mkdtemp(path.join(process.cwd(), ".tmp-lrs-probe-"));
  try {
    const session = await createFabricProbeSession({
      instancePath: path.join(temporary, ".minecraft"),
      minecraftVersion: "26.1.2-Fabric 0.19.3",
      sessionDirectory: path.join(temporary, "sessions")
    });
    const commands = (await readFile(session.commandsPath, "utf8")).trim().split("\n").map(JSON.parse);
    const manifest = JSON.parse(await readFile(session.manifestPath, "utf8"));
    assert.equal(session.kind, "fabric_bridge_probe");
    assert.equal(manifest.kind, "fabric_bridge_probe");
    assert.deepEqual(commands.map((command) => command.type), ["configure_instance", "reload_resources"]);
    assert.deepEqual(session.expectedOutputs, []);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("Fabric worker session supports packs dragged in after startup", () => {
  const session = new FabricWorkerSession();
  assert.equal(session.state, SessionState.NEW);
  assert.equal(session.configureInstance({ instancePath: "/tmp/.minecraft", minecraftVersion: "1.21.1" }).type, "configure_instance");
  assert.equal(session.addTransientResourcePack({ path: "/tmp/dragged.zip", priority: 100 }).type, "add_resource_pack");
  assert.equal(session.state, SessionState.DIRTY_RESOURCES);
  assert.equal(session.reloadResources().type, "reload_resources");
  const job = createRenderJob(baseJob());
  const request = session.submit(job, createRenderPlan(job));
  assert.equal(request.type, "submit_render_job");
  assert.equal(session.state, SessionState.RENDERING);
  session.markCompleted();
  assert.equal(session.shutdown().type, "shutdown");
  assert.equal(session.state, SessionState.CLOSED);
});

test("render session persists an ordered Fabric command stream", async () => {
  const temporary = await mkdtemp(path.join(process.cwd(), ".tmp-lrs-session-"));
  try {
    const instancePath = path.join(temporary, ".minecraft");
    const litematicPath = path.join(temporary, "machine.litematic");
    const packPath = path.join(temporary, "pack.zip");
    const outputDirectory = path.join(temporary, "output");
    await mkdir(instancePath, { recursive: true });
    await writeFile(litematicPath, makeLitematicBuffer());
    await writeFile(packPath, makeStoredZip({ "pack.mcmeta": JSON.stringify({ pack: { pack_format: 34, description: "Pack" } }) }));
    const job = createRenderJob(baseJob({
      source: { litematicPath, instancePath, minecraftVersion: "26.1.2-Fabric 0.19.3" },
      resources: { transientPacks: [{ path: packPath, priority: 0 }], selectedInstancePackPaths: [] },
      output: { directory: outputDirectory, background: "transparent", resolution: RESOLUTION_PRESETS["1080p"], namingPrefix: "machine" }
    }));
    const plan = createRenderPlan(job);
    const session = await createRenderSession({ job, plan, sessionDirectory: path.join(temporary, "sessions") });
    const commandLines = (await readFile(session.commandsPath, "utf8")).trim().split("\n").map(JSON.parse);
    const manifest = JSON.parse(await readFile(session.manifestPath, "utf8"));
    assert.equal((await getRenderSessionStatus({ sessionDirectory: path.join(temporary, "sessions"), id: session.id })).status, "waiting_for_fabric_worker");
    await writeFile(session.eventsPath, "{\"protocolVersion\":1,\"type\":\"ready\",\"message\":\"Bridge ready\"}\n", { flag: "a" });
    assert.equal((await getRenderSessionStatus({ sessionDirectory: path.join(temporary, "sessions"), id: session.id })).status, "worker_ready");
    assert.deepEqual(commandLines.map((command) => command.type), ["configure_instance", "add_resource_pack", "reload_resources", "submit_render_job"]);
    assert.equal(manifest.status, "waiting_for_fabric_worker");
    assert.equal(manifest.resourcePacks[0].path, path.resolve(packPath));
    assert.equal(session.expectedOutputs.length, 4);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("Litematic metadata reader accepts gzip NBT and safely skips region block states", () => {
  const result = readLitematicMetadataFromBuffer(makeLitematicBuffer());
  assert.equal(result.rootName, "Litematic");
  assert.equal(result.formatVersion, 6);
  assert.equal(result.minecraftDataVersion, 3465);
  assert.deepEqual(result.metadata, { Name: "Clock", Author: "Tester", TotalBlocks: 42 });
});

test("Litematic metadata reader also accepts zlib-compressed input", () => {
  const result = readLitematicMetadataFromBuffer(deflateSync(gunzipSync(makeLitematicBuffer())));
  assert.equal(result.metadata.Name, "Clock");
});

test("resource-pack inspection accepts a dragged ZIP and instance discovery lists common inputs", async () => {
  const temporary = await mkdtemp(path.join(process.cwd(), ".tmp-lrs-core-"));
  try {
    const root = path.join(temporary, ".minecraft");
    await mkdir(path.join(root, "versions", "1.21.1"), { recursive: true });
    await mkdir(path.join(root, "resourcepacks"), { recursive: true });
    await mkdir(path.join(root, "schematics"), { recursive: true });
    await mkdir(path.join(root, "versions", "1.21.1", "resourcepacks", "version-pack"), { recursive: true });
    await mkdir(path.join(root, "versions", "1.21.1", "schematics"), { recursive: true });
    const directoryPackPath = path.join(root, "resourcepacks", "directory-pack");
    await mkdir(path.join(directoryPackPath, "assets", "minecraft"), { recursive: true });
    await writeFile(path.join(directoryPackPath, "pack.mcmeta"), JSON.stringify({ pack: { pack_format: 34, description: "Directory pack" } }));
    const zipPath = path.join(root, "resourcepacks", "test-pack.zip");
    await writeFile(
      zipPath,
      makeStoredZip({
        "pack.mcmeta": JSON.stringify({ pack: { pack_format: 34, description: "Test pack" } }),
        "assets/minecraft/textures/block/stone.png": Buffer.from([1, 2, 3])
      })
    );
    await writeFile(path.join(root, "schematics", "clock.litematic"), makeLitematicBuffer());
    await writeFile(path.join(root, "versions", "1.21.1", "resourcepacks", "version-pack", "pack.mcmeta"), JSON.stringify({ pack: { pack_format: 34, description: "Version pack" } }));
    await writeFile(path.join(root, "versions", "1.21.1", "schematics", "version-clock.litematic"), makeLitematicBuffer());
    await writeFile(path.join(root, "versions", "1.21.1", "1.21.1.json"), "{}");
    await writeFile(path.join(root, "versions", "1.21.1", "1.21.1.jar"), "jar-placeholder");

    const pack = await inspectResourcePack(zipPath);
    assert.equal(pack.type, "zip");
    assert.equal(pack.packFormat, 34);
    assert.equal(pack.description, "Test pack");
    assert.equal(pack.hasMinecraftAssets, true);

    const directoryPack = await inspectResourcePack(directoryPackPath);
    assert.equal(directoryPack.type, "directory");
    assert.equal(directoryPack.hasMinecraftAssets, true);

    const instance = await inspectMinecraftInstance(root);
    assert.deepEqual(instance.versions, ["1.21.1"]);
    assert.deepEqual(instance.resourcePacks, [directoryPackPath, zipPath]);
    assert.deepEqual(instance.litematics, [path.join(root, "schematics", "clock.litematic")]);

    const directVersionPath = path.join(root, "versions", "1.21.1");
    const target = await inspectMinecraftTarget(directVersionPath);
    assert.equal(target.target.kind, "version_directory");
    assert.equal(target.target.instanceRoot, root);
    assert.equal(target.selectedVersion, "1.21.1");
    assert.equal(target.selectedVersionAvailable, true);
    assert.equal(target.selectedVersionPaths.hasVersionJson, true);
    assert.equal(target.selectedVersionPaths.hasVersionJar, true);
    assert.equal(target.selectedVersionPaths.resourcepacksPath, path.join(root, "versions", "1.21.1", "resourcepacks"));
    assert.equal(target.selectedVersionPaths.schematicsPath, path.join(root, "versions", "1.21.1", "schematics"));
    assert.deepEqual(target.resourcePacks, [
      path.join(root, "versions", "1.21.1", "resourcepacks", "version-pack"),
      directoryPackPath,
      zipPath
    ]);
    assert.deepEqual(target.litematics, [
      path.join(root, "versions", "1.21.1", "schematics", "version-clock.litematic"),
      path.join(root, "schematics", "clock.litematic")
    ]);
    assert.deepEqual(target.resourcePackEntries.map((entry) => entry.scope), ["version", "instance", "instance"]);
    assert.deepEqual(target.litematicEntries.map((entry) => entry.scope), ["version", "instance"]);
    assert.equal(resolveMinecraftTarget(root).kind, "instance_root");
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
