import { PROTOCOL_VERSION } from "./constants.js";
import { ValidationError } from "./errors.js";

const COMMAND_TYPES = new Set(["configure_instance", "add_resource_pack", "reload_resources", "submit_render_job", "shutdown"]);
const EVENT_TYPES = new Set(["ready", "resources_reloaded", "progress", "completed", "failed"]);

export function configureInstanceCommand({ instancePath, minecraftVersion }) {
  if (typeof instancePath !== "string" || instancePath.length === 0) {
    throw new ValidationError("A Fabric worker requires an instance path.");
  }
  return {
    protocolVersion: PROTOCOL_VERSION,
    type: "configure_instance",
    instancePath,
    minecraftVersion: minecraftVersion ?? null
  };
}

export function addResourcePackCommand({ path, priority }) {
  if (typeof path !== "string" || path.length === 0) {
    throw new ValidationError("Resource-pack path must be a non-empty string.");
  }
  if (!Number.isInteger(priority)) {
    throw new ValidationError("Resource-pack priority must be an integer.");
  }
  return { protocolVersion: PROTOCOL_VERSION, type: "add_resource_pack", path, priority };
}

export function reloadResourcesCommand() {
  return { protocolVersion: PROTOCOL_VERSION, type: "reload_resources" };
}

export function toFabricWorkerRequest(job, plan) {
  if (job.execution?.workerMode !== "fabric" || plan.createdFor !== "fabric") {
    throw new ValidationError("Only Fabric render jobs can be submitted to this worker.");
  }
  return {
    protocolVersion: PROTOCOL_VERSION,
    type: "submit_render_job",
    job,
    plan
  };
}

export function shutdownCommand() {
  return { protocolVersion: PROTOCOL_VERSION, type: "shutdown" };
}

export function assertWorkerCommand(command) {
  if (!command || command.protocolVersion !== PROTOCOL_VERSION || !COMMAND_TYPES.has(command.type)) {
    throw new ValidationError("Malformed Fabric worker command.");
  }
  return command;
}

export function parseWorkerEvent(line) {
  let event;
  try {
    event = JSON.parse(line);
  } catch {
    throw new ValidationError("Fabric worker emitted invalid JSON.");
  }

  if (!event || event.protocolVersion !== PROTOCOL_VERSION || !EVENT_TYPES.has(event.type)) {
    throw new ValidationError("Fabric worker emitted an unsupported event.");
  }
  if (typeof event.message !== "string") {
    throw new ValidationError("Fabric worker event is missing a user-facing message.");
  }
  return event;
}
