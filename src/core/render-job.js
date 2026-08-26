import { randomUUID } from "node:crypto";
import {
  Background,
  DEFAULT_DIAGONAL_AZIMUTH_DEG,
  DEFAULT_ISOMETRIC_ELEVATION_DEG,
  DIRECTION_OFFSETS_DEG,
  DirectionSchedule,
  FramebufferMode,
  LightingMode,
  Projection,
  RESOLUTION_PRESETS
} from "./constants.js";
import { normalizeDegrees, validateElevation } from "./camera.js";
import { ValidationError } from "./errors.js";

function assertObject(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ValidationError(`${label} must be an object.`);
  }
  return value;
}

function assertPath(value, label, { required = true } = {}) {
  if ((value === undefined || value === null || value === "") && !required) {
    return undefined;
  }
  if (typeof value !== "string" || value.trim() === "") {
    throw new ValidationError(`${label} must be a non-empty path string.`);
  }
  return value;
}

function validateResolution(resolution) {
  assertObject(resolution, "output.resolution");
  const { width, height } = resolution;
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
    throw new ValidationError("output.resolution requires positive integer width and height.");
  }
  if (width > 32768 || height > 32768) {
    throw new ValidationError("output.resolution exceeds the current 32768px safety limit.");
  }
  return { width, height };
}

function normalizeResourcePacks(packs = []) {
  if (!Array.isArray(packs)) {
    throw new ValidationError("resources.transientPacks must be an array.");
  }
  const seen = new Set();
  return packs.map((entry, index) => {
    const path = typeof entry === "string" ? entry : entry?.path;
    assertPath(path, `resources.transientPacks[${index}].path`);
    if (seen.has(path)) {
      throw new ValidationError(`Resource pack path is duplicated: ${path}`);
    }
    seen.add(path);
    const priority = typeof entry === "string" ? index : entry.priority ?? index;
    if (!Number.isInteger(priority)) {
      throw new ValidationError(`resources.transientPacks[${index}].priority must be an integer.`);
    }
    return { path, priority };
  });
}

function normalizeOffsets(offsets = DIRECTION_OFFSETS_DEG) {
  if (!Array.isArray(offsets) || offsets.length === 0) {
    throw new ValidationError("camera.selectedOffsetsDeg must contain at least one direction.");
  }
  return [...new Set(offsets.map(normalizeDegrees))];
}

function normalizeCamera(camera = {}) {
  assertObject(camera, "camera");
  const rawProjections = camera.projections ?? [camera.projection ?? Projection.ORTHOGRAPHIC];
  if (!Array.isArray(rawProjections) || rawProjections.length === 0) {
    throw new ValidationError("camera.projections must contain at least one projection.");
  }
  const projections = [...new Set(rawProjections)];
  for (const projection of projections) {
    if (!Object.values(Projection).includes(projection)) {
      throw new ValidationError(`Unsupported camera projection: ${projection}`);
    }
  }

  const baseAzimuthDeg = normalizeDegrees(camera.baseAzimuthDeg ?? DEFAULT_DIAGONAL_AZIMUTH_DEG);
  const elevationDeg = validateElevation(camera.elevationDeg ?? DEFAULT_ISOMETRIC_ELEVATION_DEG);
  const selectedOffsetsDeg = normalizeOffsets(camera.selectedOffsetsDeg);

  return {
    projections,
    baseAzimuthDeg,
    elevationDeg,
    selectedOffsetsDeg,
    includeTopDown: Boolean(camera.includeTopDown)
  };
}

function normalizeLighting(lighting = {}) {
  assertObject(lighting, "lighting");
  const mode = lighting.mode ?? LightingMode.TECHNICAL_FULLBRIGHT;
  if (!Object.values(LightingMode).includes(mode)) {
    throw new ValidationError(`Unsupported lighting.mode: ${mode}`);
  }
  return {
    mode,
    retainVanillaFaceShading: lighting.retainVanillaFaceShading ?? true,
    ambientOcclusion: lighting.ambientOcclusion ?? mode !== LightingMode.TECHNICAL_FULLBRIGHT,
    simulatedSunAzimuthDeg: normalizeDegrees(lighting.simulatedSunAzimuthDeg ?? 315),
    simulatedSunElevationDeg: validateElevation(lighting.simulatedSunElevationDeg ?? 45)
  };
}

function normalizeExecution(execution = {}, directionCount) {
  assertObject(execution, "execution");
  const framebufferMode = execution.framebufferMode ?? FramebufferMode.SINGLE;
  if (!Object.values(FramebufferMode).includes(framebufferMode)) {
    throw new ValidationError(`Unsupported execution.framebufferMode: ${framebufferMode}`);
  }

  const directionSchedule = execution.directionSchedule ?? DirectionSchedule.SEQUENTIAL;
  if (!Object.values(DirectionSchedule).includes(directionSchedule)) {
    throw new ValidationError(`Unsupported execution.directionSchedule: ${directionSchedule}`);
  }

  const tileEdgePx = execution.tileEdgePx ?? 2048;
  if (!Number.isInteger(tileEdgePx) || tileEdgePx < 256 || tileEdgePx > 8192) {
    throw new ValidationError("execution.tileEdgePx must be an integer from 256 to 8192.");
  }

  let requestedWorkers = execution.parallelWorkers ?? directionCount;
  if (!Number.isInteger(requestedWorkers) || requestedWorkers < 1 || requestedWorkers > 4) {
    throw new ValidationError("execution.parallelWorkers must be an integer from 1 to 4.");
  }
  if (directionSchedule === DirectionSchedule.SEQUENTIAL) {
    requestedWorkers = 1;
  }
  if (directionSchedule === DirectionSchedule.PARALLEL && directionCount < 2) {
    throw new ValidationError("Parallel direction scheduling requires at least two selected directions.");
  }

  return {
    workerMode: "fabric",
    framebufferMode,
    directionSchedule,
    parallelWorkers: Math.min(requestedWorkers, directionCount),
    tileEdgePx
  };
}

export function createRenderJob(input) {
  assertObject(input, "render job");
  const source = assertObject(input.source, "source");
  const resources = assertObject(input.resources ?? {}, "resources");
  const output = assertObject(input.output, "output");

  const normalizedCamera = normalizeCamera(input.camera);
  const directionCount = normalizedCamera.selectedOffsetsDeg.length * normalizedCamera.projections.length + (normalizedCamera.includeTopDown ? 1 : 0);
  const resolution = validateResolution(output.resolution ?? RESOLUTION_PRESETS["1080p"]);
  const background = output.background ?? Background.TRANSPARENT;
  if (!Object.values(Background).includes(background)) {
    throw new ValidationError(`Unsupported output.background: ${background}`);
  }

  const paddingPercent = output.paddingPercent ?? 5;
  if (!Number.isFinite(paddingPercent) || paddingPercent < 0 || paddingPercent > 50) {
    throw new ValidationError("output.paddingPercent must be between 0 and 50.");
  }

  const job = {
    schemaVersion: 1,
    id: input.id ?? randomUUID(),
    source: {
      litematicPath: assertPath(source.litematicPath, "source.litematicPath"),
      instancePath: assertPath(source.instancePath, "source.instancePath", { required: false }),
      minecraftVersion: typeof source.minecraftVersion === "string" ? source.minecraftVersion : undefined
    },
    resources: {
      transientPacks: normalizeResourcePacks(resources.transientPacks),
      selectedInstancePackPaths: normalizeResourcePacks(resources.selectedInstancePackPaths)
    },
    camera: normalizedCamera,
    lighting: normalizeLighting(input.lighting),
    output: {
      directory: assertPath(output.directory, "output.directory"),
      format: "png",
      background,
      resolution,
      paddingPercent,
      namingPrefix: output.namingPrefix ?? "litematic"
    },
    execution: normalizeExecution(input.execution, directionCount)
  };

  return Object.freeze(job);
}

export function cloneRenderJob(job, patch) {
  return createRenderJob({
    ...job,
    ...patch,
    source: { ...job.source, ...patch?.source },
    resources: { ...job.resources, ...patch?.resources },
    camera: { ...job.camera, ...patch?.camera },
    lighting: { ...job.lighting, ...patch?.lighting },
    output: { ...job.output, ...patch?.output },
    execution: { ...job.execution, ...patch?.execution }
  });
}
