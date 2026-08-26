import {
  addResourcePackCommand,
  configureInstanceCommand,
  reloadResourcesCommand,
  shutdownCommand,
  toFabricWorkerRequest
} from "./worker-protocol.js";
import { ValidationError } from "./errors.js";

const SessionState = Object.freeze({
  NEW: "new",
  CONFIGURED: "configured",
  DIRTY_RESOURCES: "dirty_resources",
  READY_TO_RENDER: "ready_to_render",
  RENDERING: "rendering",
  CLOSED: "closed"
});

export class FabricWorkerSession {
  #state = SessionState.NEW;
  #instance = null;
  #transientPacks = [];

  get state() {
    return this.#state;
  }

  configureInstance({ instancePath, minecraftVersion }) {
    this.#assertState([SessionState.NEW]);
    this.#instance = { instancePath, minecraftVersion };
    this.#state = SessionState.CONFIGURED;
    return configureInstanceCommand(this.#instance);
  }

  addTransientResourcePack({ path, priority }) {
    this.#assertState([SessionState.CONFIGURED, SessionState.DIRTY_RESOURCES, SessionState.READY_TO_RENDER]);
    if (this.#transientPacks.some((pack) => pack.path === path)) {
      throw new ValidationError(`Resource pack has already been added: ${path}`);
    }
    this.#transientPacks.push({ path, priority });
    this.#state = SessionState.DIRTY_RESOURCES;
    return addResourcePackCommand({ path, priority });
  }

  reloadResources() {
    this.#assertState([SessionState.CONFIGURED, SessionState.DIRTY_RESOURCES]);
    this.#state = SessionState.READY_TO_RENDER;
    return reloadResourcesCommand();
  }

  submit(job, plan) {
    this.#assertState([SessionState.READY_TO_RENDER]);
    if (job.source.instancePath && job.source.instancePath !== this.#instance.instancePath) {
      throw new ValidationError("The render job instance differs from the configured Fabric worker instance.");
    }
    this.#state = SessionState.RENDERING;
    return toFabricWorkerRequest(job, plan);
  }

  markCompleted() {
    this.#assertState([SessionState.RENDERING]);
    this.#state = SessionState.READY_TO_RENDER;
  }

  shutdown() {
    this.#assertState([SessionState.CONFIGURED, SessionState.DIRTY_RESOURCES, SessionState.READY_TO_RENDER]);
    this.#state = SessionState.CLOSED;
    return shutdownCommand();
  }

  #assertState(allowedStates) {
    if (!allowedStates.includes(this.#state)) {
      throw new ValidationError(`Fabric worker session cannot perform this action while ${this.#state}.`);
    }
  }
}

export { SessionState };
