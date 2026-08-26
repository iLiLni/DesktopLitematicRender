#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { createRenderPlan } from "../core/render-plan.js";
import { createRenderJob } from "../core/render-job.js";

const jobPath = process.argv[2];
if (!jobPath) {
  process.stderr.write("Usage: npm run plan -- /absolute/or/relative/job.json\n");
  process.exitCode = 1;
} else {
  try {
    const input = JSON.parse(await readFile(jobPath, "utf8"));
    const job = createRenderJob(input);
    const plan = createRenderPlan(job);
    process.stdout.write(`${JSON.stringify({ job, plan }, null, 2)}\n`);
  } catch (error) {
    process.stderr.write(`${error.name ?? "Error"}: ${error.message}\n`);
    if (error.details?.length) process.stderr.write(`${JSON.stringify(error.details)}\n`);
    process.exitCode = 1;
  }
}
