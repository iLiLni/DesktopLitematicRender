import {
  DEFAULT_DIAGONAL_AZIMUTH_DEG,
  DEFAULT_ISOMETRIC_ELEVATION_DEG,
  DIRECTION_OFFSETS_DEG,
  Projection
} from "./constants.js";
import { ValidationError } from "./errors.js";

const EPSILON = 1e-9;

export function normalizeDegrees(value) {
  if (!Number.isFinite(value)) {
    throw new ValidationError("Camera angle must be a finite number.");
  }

  const normalized = value % 360;
  return normalized < 0 ? normalized + 360 : normalized;
}

export function degreesToRadians(value) {
  return (value * Math.PI) / 180;
}

export function radiansToDegrees(value) {
  return (value * 180) / Math.PI;
}

export function validateElevation(elevationDeg) {
  if (!Number.isFinite(elevationDeg) || elevationDeg < 0 || elevationDeg >= 90) {
    throw new ValidationError(
      "Elevation must be at least 0° and below 90°. Use the dedicated top-down view for 90°.",
      [{ field: "camera.elevationDeg", value: elevationDeg }]
    );
  }
  return elevationDeg;
}

function normalizeVector(vector) {
  const length = Math.hypot(vector[0], vector[1], vector[2]);
  if (length < EPSILON) {
    throw new ValidationError("Cannot normalize a zero-length camera vector.");
  }
  return vector.map((component) => component / length);
}

function cross(a, b) {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
  ];
}

function subtract(a, b) {
  return [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
}

function directionDescriptor(azimuthDeg) {
  const azimuth = normalizeDegrees(azimuthDeg);
  const diagonal = [
    { azimuth: 45, id: "posx_posz", label: "+X +Z" },
    { azimuth: 135, id: "negx_posz", label: "-X +Z" },
    { azimuth: 225, id: "negx_negz", label: "-X -Z" },
    { azimuth: 315, id: "posx_negz", label: "+X -Z" }
  ].find((candidate) => Math.abs(candidate.azimuth - azimuth) < EPSILON);

  if (diagonal) {
    return diagonal;
  }

  const text = azimuth.toFixed(3).replace(/\.000$/, "");
  return { azimuth, id: `custom_${text.replace(".", "_")}`, label: `${text}°` };
}

export function createObliqueCamera({
  projection = Projection.ORTHOGRAPHIC,
  azimuthDeg = DEFAULT_DIAGONAL_AZIMUTH_DEG,
  elevationDeg = DEFAULT_ISOMETRIC_ELEVATION_DEG,
  distance = 100,
  target = [0, 0, 0]
} = {}) {
  if (!Object.values(Projection).includes(projection)) {
    throw new ValidationError(`Unsupported projection: ${projection}`);
  }
  validateElevation(elevationDeg);
  if (!Number.isFinite(distance) || distance <= 0) {
    throw new ValidationError("Camera distance must be a positive number.");
  }
  if (!Array.isArray(target) || target.length !== 3 || target.some((value) => !Number.isFinite(value))) {
    throw new ValidationError("Camera target must be a three-component numeric vector.");
  }

  const azimuth = normalizeDegrees(azimuthDeg);
  const azimuthRad = degreesToRadians(azimuth);
  const elevationRad = degreesToRadians(elevationDeg);
  const horizontalDistance = Math.cos(elevationRad) * distance;
  const position = [
    target[0] + Math.cos(azimuthRad) * horizontalDistance,
    target[1] + Math.sin(elevationRad) * distance,
    target[2] + Math.sin(azimuthRad) * horizontalDistance
  ];
  const forward = normalizeVector(subtract(target, position));
  const worldUp = [0, 1, 0];
  const right = normalizeVector(cross(forward, worldUp));
  const up = normalizeVector(cross(right, forward));

  return {
    projection,
    azimuthDeg: azimuth,
    elevationDeg,
    position,
    target: [...target],
    forward,
    right,
    up
  };
}

export function createTopDownCamera({ azimuthDeg = DEFAULT_DIAGONAL_AZIMUTH_DEG, distance = 100, target = [0, 0, 0] } = {}) {
  if (!Number.isFinite(distance) || distance <= 0) {
    throw new ValidationError("Camera distance must be a positive number.");
  }

  const azimuth = normalizeDegrees(azimuthDeg);
  const screenRotation = degreesToRadians(azimuth);
  const forward = [0, -1, 0];
  const right = [Math.cos(screenRotation), 0, -Math.sin(screenRotation)];
  const up = [Math.sin(screenRotation), 0, Math.cos(screenRotation)];

  return {
    projection: Projection.ORTHOGRAPHIC,
    azimuthDeg: azimuth,
    elevationDeg: 90,
    position: [target[0], target[1] + distance, target[2]],
    target: [...target],
    forward,
    right,
    up
  };
}

export function createBottomCamera({ azimuthDeg = DEFAULT_DIAGONAL_AZIMUTH_DEG, distance = 100, target = [0, 0, 0] } = {}) {
  if (!Number.isFinite(distance) || distance <= 0) {
    throw new ValidationError("Camera distance must be a positive number.");
  }

  const azimuth = normalizeDegrees(azimuthDeg);
  const screenRotation = degreesToRadians(azimuth);
  const forward = [0, 1, 0];
  const right = [Math.cos(screenRotation), 0, -Math.sin(screenRotation)];
  const up = [Math.sin(screenRotation), 0, Math.cos(screenRotation)];

  return {
    projection: Projection.BOTTOM_ORTHOGRAPHIC,
    azimuthDeg: azimuth,
    elevationDeg: -90,
    position: [target[0], target[1] - distance, target[2]],
    target: [...target],
    forward,
    right,
    up
  };
}

export function buildDiagonalViews({
  projection = Projection.ORTHOGRAPHIC,
  baseAzimuthDeg = DEFAULT_DIAGONAL_AZIMUTH_DEG,
  elevationDeg = DEFAULT_ISOMETRIC_ELEVATION_DEG,
  selectedOffsetsDeg = DIRECTION_OFFSETS_DEG,
  target = [0, 0, 0]
} = {}) {
  if (!Array.isArray(selectedOffsetsDeg) || selectedOffsetsDeg.length === 0) {
    throw new ValidationError("At least one diagonal direction must be selected.");
  }

  const uniqueOffsets = [...new Set(selectedOffsetsDeg.map(normalizeDegrees))];
  return uniqueOffsets.map((offsetDeg) => {
    const azimuthDeg = normalizeDegrees(baseAzimuthDeg + offsetDeg);
    const descriptor = directionDescriptor(azimuthDeg);
    return {
      id: `${projection}_from_${descriptor.id}`,
      label: `${projection === Projection.ORTHOGRAPHIC ? "正交" : projection === Projection.BOTTOM_ORTHOGRAPHIC ? "底部正交" : "标准"}：从 ${descriptor.label} 观察`,
      kind: "oblique",
      relativeOffsetDeg: offsetDeg,
      camera: projection === Projection.BOTTOM_ORTHOGRAPHIC
        ? createBottomCamera({ azimuthDeg, target })
        : createObliqueCamera({ projection, azimuthDeg, elevationDeg, target })
    };
  });
}

export function buildTopDownView({ baseAzimuthDeg = DEFAULT_DIAGONAL_AZIMUTH_DEG, target = [0, 0, 0] } = {}) {
  return {
    id: "top_down",
    label: "正交：顶视",
    kind: "top_down",
    relativeOffsetDeg: 0,
    camera: createTopDownCamera({ azimuthDeg: baseAzimuthDeg, target })
  };
}

export function isDefaultDiagonalSet(baseAzimuthDeg, selectedOffsetsDeg) {
  return (
    Math.abs(normalizeDegrees(baseAzimuthDeg) - DEFAULT_DIAGONAL_AZIMUTH_DEG) < EPSILON &&
    selectedOffsetsDeg.length === DIRECTION_OFFSETS_DEG.length &&
    DIRECTION_OFFSETS_DEG.every((offset) => selectedOffsetsDeg.includes(offset))
  );
}
