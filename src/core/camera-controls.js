import { DEFAULT_DIAGONAL_AZIMUTH_DEG, DEFAULT_ISOMETRIC_ELEVATION_DEG, DIRECTION_OFFSETS_DEG, Projection } from "./constants.js";
import { normalizeDegrees, validateElevation } from "./camera.js";
import { ValidationError } from "./errors.js";

export function getCameraControls(camera = {}) {
  return {
    azimuth: {
      id: "camera.baseAzimuthDeg",
      label: "基准方位角",
      help: "45° 表示从 +X +Z 对角方向观察；四向会相对该角度每次旋转 90°。",
      value: normalizeDegrees(camera.baseAzimuthDeg ?? DEFAULT_DIAGONAL_AZIMUTH_DEG),
      slider: { min: 0, max: 359.9, step: 0.1, wraps: true },
      numericInput: { min: -36000, max: 36000, step: 0.01, unit: "°" }
    },
    elevation: {
      id: "camera.elevationDeg",
      label: "俯角",
      help: "从水平面向下计算；35.264° 为标准等距俯角。顶视请使用独立的顶视选项。",
      value: validateElevation(camera.elevationDeg ?? DEFAULT_ISOMETRIC_ELEVATION_DEG),
      slider: { min: 0, max: 89.9, step: 0.1, wraps: false },
      numericInput: { min: 0, max: 89.9, step: 0.01, unit: "°" }
    },
    projections: {
      id: "camera.projections",
      label: "投影方式",
      options: [
        { value: Projection.ORTHOGRAPHIC, label: "正交（对角观察）" },
        { value: Projection.PERSPECTIVE, label: "标准透视（对角观察）" },
        { value: Projection.BOTTOM_ORTHOGRAPHIC, label: "底部正交视角" }
      ]
    },
    directions: {
      id: "camera.selectedOffsetsDeg",
      label: "相对方向",
      options: DIRECTION_OFFSETS_DEG.map((offsetDeg) => ({
        value: offsetDeg,
        label: offsetDeg === 0 ? "基准方向" : `相对基准 +${offsetDeg}°`
      })),
      selectAllValue: [...DIRECTION_OFFSETS_DEG]
    }
  };
}

export function applyAngleInput(camera, field, rawValue) {
  const numeric = typeof rawValue === "number" ? rawValue : Number(rawValue);
  if (!Number.isFinite(numeric)) {
    throw new ValidationError("Angle input must be numeric.");
  }
  if (field === "baseAzimuthDeg") {
    return { ...camera, baseAzimuthDeg: normalizeDegrees(numeric) };
  }
  if (field === "elevationDeg") {
    return { ...camera, elevationDeg: validateElevation(numeric) };
  }
  throw new ValidationError(`Unknown camera angle field: ${field}`);
}
