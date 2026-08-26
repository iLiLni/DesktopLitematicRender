import {
  DirectionSchedule,
  FramebufferMode,
  LightingMode,
  PROTOCOL_VERSION
} from "./constants.js";
import { buildDiagonalViews, buildTopDownView } from "./camera.js";
import { ValidationError } from "./errors.js";

const BYTES_PER_PIXEL_ESTIMATE = 16;

export function createTiles(width, height, tileEdgePx) {
  if (![width, height, tileEdgePx].every(Number.isInteger) || width < 1 || height < 1 || tileEdgePx < 1) {
    throw new ValidationError("Tile dimensions must be positive integers.");
  }

  const tiles = [];
  for (let y = 0; y < height; y += tileEdgePx) {
    for (let x = 0; x < width; x += tileEdgePx) {
      const tileWidth = Math.min(tileEdgePx, width - x);
      const tileHeight = Math.min(tileEdgePx, height - y);
      tiles.push({
        id: `tile_${x}_${y}`,
        x,
        y,
        width: tileWidth,
        height: tileHeight,
        normalizedViewport: {
          left: x / width,
          top: y / height,
          right: (x + tileWidth) / width,
          bottom: (y + tileHeight) / height
        }
      });
    }
  }
  return tiles;
}

export function buildOutputFileName(job, view) {
  const safePrefix = job.output.namingPrefix.replace(/[^a-zA-Z0-9._-]+/g, "_").replace(/^_+|_+$/g, "") || "litematic";
  const { width, height } = job.output.resolution;
  return `${safePrefix}__${view.id}__${width}x${height}.png`;
}

function makeViewTasks(job, views) {
  const { width, height } = job.output.resolution;
  const tiles =
    job.execution.framebufferMode === FramebufferMode.TILED
      ? createTiles(width, height, job.execution.tileEdgePx)
      : [{ id: "full", x: 0, y: 0, width, height, normalizedViewport: { left: 0, top: 0, right: 1, bottom: 1 } }];

  return views.map((view) => ({
    id: `${job.id}:${view.id}`,
    view,
    outputFileName: buildOutputFileName(job, view),
    tiles,
    pixelCount: width * height
  }));
}

function assignWorkers(job, viewTasks) {
  if (job.execution.directionSchedule === DirectionSchedule.SEQUENTIAL) {
    return [{ id: "worker-1", viewTaskIds: viewTasks.map((task) => task.id) }];
  }

  const workerCount = Math.min(job.execution.parallelWorkers, viewTasks.length);
  const workers = Array.from({ length: workerCount }, (_, index) => ({ id: `worker-${index + 1}`, viewTaskIds: [] }));
  viewTasks.forEach((task, index) => workers[index % workers.length].viewTaskIds.push(task.id));
  return workers;
}

export function estimateExecutionRisk(job, viewTasks, workers) {
  const { width, height } = job.output.resolution;
  const rasterWidth = job.execution.framebufferMode === FramebufferMode.TILED ? Math.min(width, job.execution.tileEdgePx) : width;
  const rasterHeight = job.execution.framebufferMode === FramebufferMode.TILED ? Math.min(height, job.execution.tileEdgePx) : height;
  const estimatedBytesPerWorker = rasterWidth * rasterHeight * BYTES_PER_PIXEL_ESTIMATE;
  const estimatedBytesTotal = estimatedBytesPerWorker * workers.length;
  const warnings = [];

  if (job.execution.framebufferMode === FramebufferMode.SINGLE && Math.max(width, height) >= 7680) {
    warnings.push({
      level: "warning",
      code: "large_single_framebuffer",
      message: "8K 单图帧缓冲会占用较多显存；显存不足时请切换到瓦片渲染。"
    });
  }

  if (job.execution.directionSchedule === DirectionSchedule.PARALLEL) {
    warnings.push({
      level: "danger",
      code: "parallel_fabric_workers",
      message:
        "实验性四向并行会启动多个隐藏 Fabric/Minecraft Worker，纹理图集、资源包和图形上下文会重复占用内存。仅建议在高配置设备上使用。"
    });
  }

  if (job.resources.transientPacks.length > 0) {
    warnings.push({
      level: "info",
      code: "transient_pack_reload",
      message: "存在拖入的临时资源包。Worker 启动后需要执行一次资源重载。"
    });
  }

  if (job.lighting.mode === LightingMode.SIMULATED && job.output.background === "transparent") {
    warnings.push({
      level: "info",
      code: "transparent_simulated_lighting",
      message: "透明背景不会默认生成地面承接阴影；模拟光照只影响机器本体。"
    });
  }

  return {
    estimatedBytesPerWorker,
    estimatedBytesTotal,
    severity: warnings.some((warning) => warning.level === "danger") ? "high" : warnings.some((warning) => warning.level === "warning") ? "medium" : "low",
    warnings,
    selectedViewCount: viewTasks.length,
    selectedTileCount: viewTasks.reduce((sum, task) => sum + task.tiles.length, 0)
  };
}

export function createRenderPlan(job) {
  if (!job || job.schemaVersion !== 1 || job.execution?.workerMode !== "fabric") {
    throw new ValidationError("createRenderPlan requires a validated Fabric render job.");
  }

  const views = job.camera.projections.flatMap((projection) =>
    buildDiagonalViews({
      projection,
      baseAzimuthDeg: job.camera.baseAzimuthDeg,
      elevationDeg: job.camera.elevationDeg,
      selectedOffsetsDeg: job.camera.selectedOffsetsDeg
    })
  );

  if (job.camera.includeTopDown) {
    views.push(buildTopDownView({ baseAzimuthDeg: job.camera.baseAzimuthDeg }));
  }

  const viewTasks = makeViewTasks(job, views);
  const workers = assignWorkers(job, viewTasks);
  const risk = estimateExecutionRisk(job, viewTasks, workers);

  return Object.freeze({
    protocolVersion: PROTOCOL_VERSION,
    jobId: job.id,
    createdFor: "fabric",
    workers,
    viewTasks,
    risk
  });
}
