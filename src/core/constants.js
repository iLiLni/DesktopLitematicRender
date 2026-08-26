export const PROTOCOL_VERSION = 1;

export const Projection = Object.freeze({
  ORTHOGRAPHIC: "orthographic",
  PERSPECTIVE: "perspective",
  BOTTOM_ORTHOGRAPHIC: "bottom_orthographic"
});

export const Background = Object.freeze({
  TRANSPARENT: "transparent",
  WHITE: "white"
});

export const FramebufferMode = Object.freeze({
  SINGLE: "single",
  TILED: "tiled"
});

export const DirectionSchedule = Object.freeze({
  SEQUENTIAL: "sequential",
  PARALLEL: "parallel"
});

export const LightingMode = Object.freeze({
  TECHNICAL_FULLBRIGHT: "technical_fullbright",
  VANILLA_STANDARD: "vanilla_standard",
  SIMULATED: "simulated"
});

export const DEFAULT_DIAGONAL_AZIMUTH_DEG = 45;
export const DEFAULT_ISOMETRIC_ELEVATION_DEG = 35.264389682754654;
export const DIRECTION_OFFSETS_DEG = Object.freeze([0, 90, 180, 270]);

export const RESOLUTION_PRESETS = Object.freeze({
  "1080p": Object.freeze({ width: 1920, height: 1080 }),
  "2160p": Object.freeze({ width: 3840, height: 2160 }),
  "4320p": Object.freeze({ width: 7680, height: 4320 })
});
