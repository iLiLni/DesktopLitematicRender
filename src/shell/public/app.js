const PRESETS = Object.freeze({
  "1080p": { width: 1920, height: 1080 },
  "2160p": { width: 3840, height: 2160 },
  "4320p": { width: 7680, height: 4320 }
});

const DIRECTIONS = Object.freeze([
  { offset: 0, label: "+X +Z" },
  { offset: 90, label: "−X +Z" },
  { offset: 180, label: "−X −Z" },
  { offset: 270, label: "+X −Z" }
]);

const state = {
  instance: null,
  litematicPath: "",
  temporaryPacks: [],
  selectedResourcePackPath: "",
  selectedOffsets: new Set([0, 90, 180, 270]),
  activeSession: null
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
let toastTimer = null;
let sessionTimer = null;

function showToast(message, { error = false } = {}) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.toggle("error", error);
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 4200);
}

async function api(url, options = {}) {
  const response = await fetch(url, options);
  const payload = await response.json().catch(() => ({ ok: false, error: "本地服务返回了无法识别的数据。" }));
  if (!response.ok || !payload.ok) throw new Error(payload.error ?? "本地服务请求失败。");
  return payload;
}

function basename(filePath) {
  return String(filePath ?? "").replaceAll("\\", "/").split("/").filter(Boolean).at(-1) ?? "未命名文件";
}

function inputEntries(instance, entryKey, listKey) {
  const entries = instance[entryKey];
  return entries?.length ? entries : (instance[listKey] ?? []).map((path) => ({ path, name: basename(path), scope: "instance" }));
}

function scopeLabel(scope) {
  return scope === "version" ? "当前版本目录" : "实例根目录";
}

function joinPath(folder, child) {
  if (!folder) return "";
  const separator = folder.includes("\\") ? "\\" : "/";
  return `${folder.replace(/[\\/]+$/, "")}${separator}${child}`;
}

function setTheme(isDark) {
  document.documentElement.dataset.theme = isDark ? "dark" : "light";
  $("#theme-toggle").checked = isDark;
  try { localStorage.setItem("lrs-theme", isDark ? "dark" : "light"); } catch {}
  drawCameraPreview();
}

function initializeTheme() {
  let theme = "";
  try { theme = localStorage.getItem("lrs-theme") || ""; } catch {}
  setTheme(theme ? theme === "dark" : window.matchMedia?.("(prefers-color-scheme: dark)").matches);
}

function formatAngle(value, decimals = 2) {
  const number = Number(value);
  return `${number.toFixed(decimals).replace(/\.00$/, "").replace(/(\.\d)0$/, "$1")}°`;
}

function normalizeAzimuth(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 45;
  return ((parsed % 360) + 360) % 360;
}

function clampElevation(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 35.264389682754654;
  return Math.min(89.99, Math.max(0, parsed));
}

function selectedProjectionValues() {
  const selected = document.querySelector('input[name="projection"]:checked');
  return selected ? [selected.value] : [];
}

function currentSchedule() {
  return document.querySelector('input[name="direction-schedule"]:checked').value;
}

function selectedBackground() {
  return document.querySelector('input[name="background"]:checked').value;
}

function selectedResources() {
  const selectedPath = state.selectedResourcePackPath;
  if (!selectedPath) return { selectedInstancePackPaths: [], transientPacks: [] };
  if (state.temporaryPacks.some((pack) => pack.path === selectedPath)) {
    return { selectedInstancePackPaths: [], transientPacks: [{ path: selectedPath, priority: 0 }] };
  }
  return { selectedInstancePackPaths: [selectedPath], transientPacks: [] };
}

function updateWorkerBadge(status, mode = "idle") {
  const badge = $("#worker-status");
  badge.textContent = status;
  badge.classList.toggle("ready", mode === "ready");
  badge.classList.toggle("error", mode === "error");
}

function setInstanceSummary(message, tone = "") {
  const element = $("#instance-summary");
  element.textContent = message;
  element.className = `instance-summary ${tone}`.trim();
}

function setLitematicSummary(message, tone = "") {
  const element = $("#litematic-summary");
  element.textContent = message;
  element.className = `file-summary ${tone}`.trim();
}

function createOption(value, label) {
  const option = document.createElement("option");
  option.value = value;
  option.textContent = label;
  return option;
}

function renderVersions(instance) {
  const list = $("#version-options");
  list.replaceChildren();
  for (const version of instance.versions) list.append(createOption(version, version));
  const input = $("#version-input");
  input.value = instance.selectedVersion ?? input.value ?? "";
}

function renderInstanceLitematics(instance) {
  const select = $("#schematic-select");
  const entries = inputEntries(instance, "litematicEntries", "litematics");
  select.replaceChildren();
  for (const entry of entries) select.append(createOption(entry.path, `${entry.name} · ${scopeLabel(entry.scope)}`));
  select.disabled = entries.length === 0;
  if (entries.some((entry) => entry.path === state.litematicPath)) select.value = state.litematicPath;
  else select.selectedIndex = -1;
  $("#schematic-placeholder").parentElement.classList.toggle("selected", Boolean(state.litematicPath));
}

function renderInstancePacks(instance) {
  const select = $("#resource-pack-select");
  const entries = inputEntries(instance, "resourcePackEntries", "resourcePacks");
  select.replaceChildren(createOption("", "Minecraft 默认材质包"));
  for (const entry of entries) select.append(createOption(entry.path, `${entry.name} · ${scopeLabel(entry.scope)}`));
  for (const pack of state.temporaryPacks) select.append(createOption(pack.path, `${pack.name} · 本次会话`));
  if (![...select.options].some((option) => option.value === state.selectedResourcePackPath)) {
    state.selectedResourcePackPath = "";
  }
  select.value = state.selectedResourcePackPath;
  updateResourcePackSummary();
}

function updateResourcePackSummary() {
  const select = $("#resource-pack-select");
  const option = select.selectedOptions[0];
  $("#resource-pack-summary").textContent = option?.value
    ? `当前使用：${option.textContent}`
    : "当前使用 Minecraft 默认材质包。";
}

function renderTemporaryPacks() {
  const list = $("#temporary-pack-list");
  list.replaceChildren();
  for (const [index, pack] of state.temporaryPacks.entries()) {
    const item = document.createElement("div");
    item.className = "temporary-pack-item";
    const info = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = pack.name;
    title.title = pack.path;
    const pathText = document.createElement("span");
    pathText.textContent = pack.description || pack.path;
    pathText.title = pack.path;
    info.append(title, pathText);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "remove-button";
    remove.textContent = "移除";
    remove.addEventListener("click", () => {
      if (state.selectedResourcePackPath === pack.path) state.selectedResourcePackPath = "";
      state.temporaryPacks.splice(index, 1);
      renderTemporaryPacks();
      renderInstancePacks(state.instance ?? { resourcePackEntries: [], resourcePacks: [] });
    });
    item.append(info, remove);
    list.append(item);
  }
}

function addTemporaryPack({ path, description = "" }) {
  if (state.temporaryPacks.some((item) => item.path === path)) {
    showToast("这个临时资源包已经在列表中。");
    return;
  }
  state.temporaryPacks.push({ path, name: basename(path), description });
  state.selectedResourcePackPath = path;
  renderTemporaryPacks();
  renderInstancePacks(state.instance ?? { resourcePackEntries: [], resourcePacks: [] });
}

async function inspectLitematic(filePath, { silent = false } = {}) {
  const pathValue = String(filePath ?? "").trim();
  if (!pathValue) throw new Error("请先选择或填写 .litematic 文件。");
  const { metadata } = await api("/api/litematic/inspect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: pathValue })
  });
  state.litematicPath = pathValue;
  $("#litematic-path").value = pathValue;
  const select = $("#schematic-select");
  if ([...select.options].some((option) => option.value === pathValue)) select.value = pathValue;
  $("#schematic-placeholder").parentElement.classList.add("selected");
  const meta = metadata.metadata ?? {};
  const pieces = [meta.Name || basename(pathValue)];
  if (meta.Author) pieces.push(`作者：${meta.Author}`);
  if (meta.TotalBlocks !== undefined) pieces.push(`方块：${meta.TotalBlocks}`);
  setLitematicSummary(pieces.join(" · "), "positive");
  if (!silent) showToast("已读取 Litematic 元数据。");
}

async function inspectInstance({ silent = false, selectedVersion = undefined } = {}) {
  const targetPath = $("#instance-path").value.trim();
  if (!targetPath) throw new Error("请填写 Minecraft 游戏文件夹路径。");
  updateWorkerBadge("正在读取游戏文件夹…");
  const { target } = await api("/api/instance/inspect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ targetPath, selectedVersion })
  });
  state.instance = target;
  renderVersions(target);
  renderInstanceLitematics(target);
  renderInstancePacks(target);
  const foundText = `已识别 ${target.target.kind === "version_directory" ? "版本隔离目录" : "Minecraft 游戏目录"}：${target.root}`;
  const versionText = target.selectedVersion
    ? `目标版本 ${target.selectedVersion}（JSON ${target.selectedVersionPaths?.hasVersionJson ? "已找到" : "未找到"}，JAR ${target.selectedVersionPaths?.hasVersionJar ? "已找到" : "未找到"}）`
    : `发现 ${target.versions.length} 个版本`;
  const inputText = `投影文件 ${target.litematics.length} 个，材质包 ${target.resourcePacks.length} 个`;
  setInstanceSummary(`${foundText}；${versionText}；${inputText}。`, target.exists ? "positive" : "negative");
  if (!$("#output-directory").value.trim()) {
    $("#output-directory").value = joinPath(target.root, "litematic-renders");
  }
  updateWorkerBadge(target.exists ? "游戏文件夹已接入 · 等待 Worker" : "游戏文件夹不存在", target.exists ? "ready" : "error");
  if (!silent) showToast(target.exists ? "游戏文件夹已读取。" : "路径已提交，但本机未找到该游戏文件夹。", { error: !target.exists });
}

async function addResourcePackFromPath(packPath) {
  const pathValue = String(packPath ?? "").trim();
  if (!pathValue) throw new Error("请填写或拖入资源包。");
  const { pack } = await api("/api/resource-pack/inspect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: pathValue })
  });
  const description = [pack.type === "zip" ? "ZIP" : "文件夹", pack.description || "无描述"].join(" · ");
  addTemporaryPack({ path: pack.path, description });
  $("#resource-pack-path").value = "";
  showToast(`已添加资源包：${basename(pack.path)}`);
}

async function uploadInput(file, kind) {
  if (!file) return;
  const expectedExtension = kind === "litematic" ? ".litematic" : ".zip";
  if (!file.name.toLowerCase().endsWith(expectedExtension)) {
    throw new Error(kind === "litematic" ? "请拖入 .litematic 文件。" : "请拖入资源包 ZIP，或使用“选择资源包文件夹”。");
  }
  showToast(`正在导入 ${file.name}…`);
  const result = await api("/api/session-input", {
    method: "POST",
    headers: {
      "x-lrs-input-kind": kind,
      "x-lrs-file-name": file.name
    },
    body: file
  });
  if (kind === "litematic") {
    await inspectLitematic(result.path, { silent: true });
    showToast("已导入并读取 Litematic。");
  } else {
    await addResourcePackFromPath(result.path);
  }
}

function normaliseFolderFiles(files) {
  const entries = [...files].map((item) => ({
    file: item.file ?? item,
    relativePath: String(item.relativePath ?? item.file?.webkitRelativePath ?? item.webkitRelativePath ?? item.file?.name ?? item.name).replaceAll("\\", "/")
  }));
  const parts = entries.map((entry) => entry.relativePath.split("/").filter(Boolean));
  const first = parts[0]?.[0];
  const sharesRoot = first && parts.every((segments) => segments.length > 1 && segments[0] === first);
  return entries.map((entry, index) => ({
    file: entry.file,
    relativePath: (sharesRoot ? parts[index].slice(1) : parts[index]).join("/")
  })).filter((entry) => entry.relativePath);
}

async function uploadResourcePackFolder(files, folderName = "resource-pack") {
  const entries = normaliseFolderFiles(files);
  if (!entries.length) throw new Error("资源包文件夹为空。");
  if (!entries.some((entry) => entry.relativePath === "pack.mcmeta")) {
    throw new Error("资源包文件夹根目录缺少 pack.mcmeta。");
  }
  showToast(`正在导入资源包文件夹（${entries.length} 个文件）…`);
  const session = await api("/api/session-folder/start", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind: "resource-pack", folderName })
  });
  for (const entry of entries) {
    await api("/api/session-folder/file", {
      method: "POST",
      headers: {
        "x-lrs-folder-id": session.id,
        "x-lrs-relative-path": entry.relativePath
      },
      body: entry.file
    });
  }
  const { pack } = await api("/api/session-folder/complete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: session.id })
  });
  const description = ["文件夹", pack.description || "无描述"].join(" · ");
  addTemporaryPack({ path: pack.path, description });
  showToast(`已导入资源包文件夹：${folderName}`);
}

function entryFile(entry) {
  return new Promise((resolve, reject) => entry.file(resolve, reject));
}

function directoryEntries(entry) {
  return new Promise((resolve, reject) => {
    const reader = entry.createReader();
    const all = [];
    const read = () => reader.readEntries((items) => {
      if (!items.length) resolve(all);
      else {
        all.push(...items);
        read();
      }
    }, reject);
    read();
  });
}

async function collectEntryFiles(entry, prefix = "") {
  if (entry.isFile) return [{ file: await entryFile(entry), relativePath: `${prefix}${entry.name}` }];
  if (!entry.isDirectory) return [];
  const children = await directoryEntries(entry);
  const nested = await Promise.all(children.map((child) => collectEntryFiles(child, `${prefix}${entry.name}/`)));
  return nested.flat();
}

async function uploadSelectedFiles(fileList, kind) {
  const files = [...fileList];
  if (kind === "resource-pack" && (files.length > 1 || files[0]?.webkitRelativePath)) {
    await uploadResourcePackFolder(files, files[0]?.webkitRelativePath?.split("/")[0] || "resource-pack");
    return;
  }
  await uploadInput(files[0], kind);
}

async function uploadDroppedFiles(event, kind) {
  const entries = [...(event.dataTransfer.items ?? [])].map((item) => item.webkitGetAsEntry?.()).filter(Boolean);
  const directory = entries.find((entry) => entry.isDirectory);
  if (kind === "resource-pack" && directory) {
    await uploadResourcePackFolder(await collectEntryFiles(directory), directory.name);
    return;
  }
  await uploadSelectedFiles(event.dataTransfer.files, kind);
}

function updateAnglesFrom(source) {
  const azimuth = normalizeAzimuth(source === "azimuth" ? $("#azimuth-number").value : $("#azimuth-range").value);
  $("#azimuth-range").value = azimuth;
  $("#azimuth-number").value = azimuth;
  $("#azimuth-readout").textContent = formatAngle(azimuth);

  const elevation = clampElevation(source === "elevation" ? $("#elevation-number").value : $("#elevation-range").value);
  $("#elevation-range").value = elevation;
  $("#elevation-number").value = elevation;
  $("#elevation-readout").textContent = formatAngle(elevation, 3);
  updateDirectionLabels();
  drawCameraPreview();
}

function updateDirectionLabels() {
  const base = normalizeAzimuth($("#azimuth-number").value);
  for (const button of $$(".direction-button")) {
    const offset = Number(button.dataset.offset);
    const description = button.querySelector("span");
    description.textContent = offset === 0
      ? `基准 · ${formatAngle(base)}`
      : `相对 +${offset}° · ${formatAngle(normalizeAzimuth(base + offset))}`;
  }
}

function updateParallelNote() {
  const parallel = currentSchedule() === "parallel";
  const note = $("#parallel-note");
  note.classList.toggle("is-danger", parallel);
  note.textContent = parallel
    ? "高配置提示：会启动多个隐藏 Fabric/Minecraft Worker。资源包、纹理图集和显存会重复占用，可能崩溃。"
    : "顺序渲染：仅启动一个隐藏 Worker，推荐。";
}

function setDirections(offsets) {
  state.selectedOffsets = new Set(offsets);
  for (const button of $$(".direction-button")) {
    button.classList.toggle("selected", state.selectedOffsets.has(Number(button.dataset.offset)));
  }
  drawCameraPreview();
}

function drawCameraPreview() {
  const canvas = $("#camera-preview");
  const dark = document.documentElement.dataset.theme === "dark";
  const palette = dark
    ? { ring: "#3b5478", top: "#263b63", left: "#1d3154", right: "#172946", edge: "#83a3ff", text: "#dbe7ff", selected: "#8eabff", muted: "#627694" }
    : { ring: "#ccd5e2", top: "#e8edff", left: "#d2dcff", right: "#bbcaff", edge: "#7388d6", text: "#223046", selected: "#315df4", muted: "#929cab" };
  const rectangle = canvas.getBoundingClientRect();
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
  const width = Math.max(220, Math.floor(rectangle.width * pixelRatio));
  const height = Math.max(180, Math.floor(rectangle.height * pixelRatio));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const context = canvas.getContext("2d");
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  const cssWidth = width / pixelRatio;
  const cssHeight = height / pixelRatio;
  context.clearRect(0, 0, cssWidth, cssHeight);

  const centerX = cssWidth * 0.5;
  const centerY = cssHeight * 0.54;
  const radius = Math.min(cssWidth, cssHeight) * 0.31;

  context.strokeStyle = palette.ring;
  context.lineWidth = 1;
  context.setLineDash([4, 5]);
  context.beginPath();
  context.arc(centerX, centerY, radius, 0, Math.PI * 2);
  context.stroke();
  context.setLineDash([]);

  const cube = (points, fill, stroke) => {
    context.beginPath();
    points.forEach(([x, y], index) => index ? context.lineTo(x, y) : context.moveTo(x, y));
    context.closePath();
    context.fillStyle = fill;
    context.fill();
    context.strokeStyle = stroke;
    context.stroke();
  };
  const scale = Math.min(cssWidth, cssHeight) * 0.12;
  const top = [centerX, centerY - scale * .72];
  const left = [centerX - scale, centerY - scale * .15];
  const right = [centerX + scale, centerY - scale * .15];
  const bottom = [centerX, centerY + scale * .5];
  const lower = [centerX, centerY + scale * 1.38];
  cube([top, right, bottom, left], palette.top, palette.edge);
  cube([left, bottom, lower, [centerX - scale, centerY + scale * .72]], palette.left, palette.edge);
  cube([bottom, right, [centerX + scale, centerY + scale * .72], lower], palette.right, palette.edge);

  context.fillStyle = palette.text;
  context.font = '700 12px "SimHei", sans-serif';
  context.textAlign = "center";
  context.fillText("机器中心", centerX, lower[1] + 22);

  const base = normalizeAzimuth($("#azimuth-number").value);
  for (const direction of DIRECTIONS) {
    const angle = ((base + direction.offset) * Math.PI) / 180;
    const selected = state.selectedOffsets.has(direction.offset);
    const x = centerX + Math.cos(angle) * radius;
    const y = centerY + Math.sin(angle) * radius * .62;
    const arrowStartX = centerX + Math.cos(angle) * (radius - 24);
    const arrowStartY = centerY + Math.sin(angle) * (radius - 24) * .62;
    context.strokeStyle = selected ? palette.selected : palette.ring;
    context.fillStyle = selected ? palette.selected : palette.muted;
    context.lineWidth = selected ? 2.5 : 1.5;
    context.beginPath();
    context.moveTo(arrowStartX, arrowStartY);
    context.lineTo(x, y);
    context.stroke();
    const arrowAngle = Math.atan2(y - arrowStartY, x - arrowStartX);
    context.beginPath();
    context.moveTo(x, y);
    context.lineTo(x - 8 * Math.cos(arrowAngle - Math.PI / 6), y - 8 * Math.sin(arrowAngle - Math.PI / 6));
    context.lineTo(x - 8 * Math.cos(arrowAngle + Math.PI / 6), y - 8 * Math.sin(arrowAngle + Math.PI / 6));
    context.closePath();
    context.fill();
    context.font = '700 11px "SimHei", sans-serif';
    context.fillText(direction.label, centerX + Math.cos(angle) * (radius + 24), centerY + Math.sin(angle) * (radius + 24) * .62 + 4);
  }
}

function formatMiB(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(0)} MiB`;
}

function sessionStatusText(status) {
  return {
    waiting_for_fabric_worker: "等待 Fabric Worker",
    worker_ready: "启动桥接已就绪，渲染器待接入",
    rendering: "正在渲染",
    completed: "渲染完成",
    failed: "Worker 失败"
  }[status] ?? "等待状态更新";
}

function selectedMinecraftVersion() {
  return $("#version-input").value.trim() || state.instance?.selectedVersion || "";
}

function renderSessionStatus(sessionStatus) {
  const summary = $("#session-summary");
  const message = sessionStatus.lastEvent?.message;
  const isProbe = state.activeSession?.kind === "fabric_bridge_probe";
  const fallback = isProbe ? "等待隐藏 Minecraft 启动事件。" : sessionStatus.outputDirectory;
  summary.textContent = `${isProbe && sessionStatus.status === "worker_ready" ? "Fabric 启动桥接已就绪" : sessionStatusText(sessionStatus.status)}：${message || fallback}`;
  summary.title = state.activeSession ? `命令流：${state.activeSession.commandsPath}\n事件流：${state.activeSession.eventsPath}` : "";
  if (sessionStatus.status === "worker_ready") updateWorkerBadge("Worker 桥接已就绪", "ready");
  if (sessionStatus.status === "rendering") updateWorkerBadge("Fabric Worker 正在渲染", "ready");
  if (sessionStatus.status === "completed") updateWorkerBadge("渲染完成", "ready");
  if (sessionStatus.status === "failed") updateWorkerBadge("Fabric Worker 失败", "error");
}

async function refreshSessionStatus() {
  if (!state.activeSession) return;
  try {
    const { status } = await api(`/api/render-session/status?id=${encodeURIComponent(state.activeSession.id)}`);
    renderSessionStatus(status);
    if (["completed", "failed"].includes(status.status)) clearInterval(sessionTimer);
  } catch (error) {
    clearInterval(sessionTimer);
    showToast(`无法读取 Worker 状态：${error.message}`, { error: true });
  }
}

function watchSession(session) {
  state.activeSession = session;
  clearInterval(sessionTimer);
  sessionTimer = setInterval(refreshSessionStatus, 3000);
  refreshSessionStatus();
}

function renderPlan(plan, session = null, worker = null) {
  $("#plan-summary").hidden = true;
  const details = $("#plan-details");
  details.hidden = false;
  const metrics = $("#plan-metrics");
  metrics.replaceChildren();
  const values = [
    ["输出视图", `${plan.risk.selectedViewCount} 张`],
    ["渲染瓦片", `${plan.risk.selectedTileCount} 块`],
    ["隐藏 Worker", `${plan.workers.length} 个`],
    ["预估每 Worker", formatMiB(plan.risk.estimatedBytesPerWorker)]
  ];
  for (const [label, value] of values) {
    const metric = document.createElement("div");
    metric.className = "metric";
    const small = document.createElement("span");
    small.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    metric.append(small, strong);
    metrics.append(metric);
  }
  const warnings = $("#warning-list");
  warnings.replaceChildren();
  if (plan.risk.warnings.length === 0) {
    const safe = document.createElement("div");
    safe.className = "warning info";
    safe.textContent = "计划未发现额外风险提示。任务已按顺序写入 Fabric Worker 命令流。";
    warnings.append(safe);
  } else {
    for (const warning of plan.risk.warnings) {
      const item = document.createElement("div");
      item.className = `warning ${warning.level}`;
      item.textContent = warning.message;
      warnings.append(item);
    }
  }
  const sessionSummary = $("#session-summary");
  if (session) {
    sessionSummary.hidden = false;
    sessionSummary.textContent = worker
      ? `隐藏 Fabric Worker 已${worker.mode === "reused" ? "复用" : "启动"}：PID ${worker.process.pid}；会话：${session.directory}`
      : `任务已保存，等待 Fabric Worker：${session.directory}`;
    sessionSummary.title = `命令流：${session.commandsPath}\n事件流：${session.eventsPath}`;
  } else {
    sessionSummary.hidden = true;
  }
  const views = $("#view-list");
  views.replaceChildren();
  for (const [index, task] of plan.viewTasks.entries()) {
    const item = document.createElement("li");
    const serial = document.createElement("span");
    serial.textContent = String(index + 1).padStart(2, "0");
    const text = document.createElement("div");
    const title = document.createElement("strong");
    title.textContent = task.view.label;
    const file = document.createElement("small");
    file.textContent = task.outputFileName;
    text.append(title, file);
    const tileCount = document.createElement("em");
    tileCount.textContent = task.tiles.length === 1 ? "单图" : `${task.tiles.length} 瓦片`;
    item.append(serial, text, tileCount);
    views.append(item);
  }
}

async function testFabricBridge() {
  if (!state.instance?.exists) throw new Error("请先读取一个存在的 Minecraft 游戏文件夹。");
  const minecraftVersion = selectedMinecraftVersion();
  if (!minecraftVersion) throw new Error("请先选择或填写目标 Minecraft 版本。");
  updateWorkerBadge("正在检查 Fabric 隐藏启动…");
  const { session, worker, bridge } = await api("/api/fabric-bridge-probe", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      targetPath: state.instance.root,
      minecraftVersion,
      launcher: { javaExecutable: $("#java-executable").value.trim() || undefined }
    })
  });
  $("#plan-summary").hidden = true;
  $("#plan-details").hidden = false;
  $("#plan-metrics").replaceChildren();
  $("#warning-list").replaceChildren();
  $("#view-list").replaceChildren();
  const summary = $("#session-summary");
  summary.hidden = false;
  summary.textContent = `Fabric 隐藏启动已请求：PID ${worker.process.pid}。正在等待 Bridge ready 事件。`;
  summary.title = `会话：${session.directory}\n启动日志：${bridge.launchLogPath}\nMinecraft 日志：${bridge.minecraftLogPath}`;
  watchSession(session);
  showToast("隐藏 Fabric 启动检测已发出，请等待状态更新。", { error: false });
}

function collectJob() {
  if (!state.instance?.exists) throw new Error("请先读取一个存在的 Minecraft 游戏文件夹。");
  if (!state.litematicPath) throw new Error("请先确认一个 .litematic 投影文件。");
  const projections = selectedProjectionValues();
  if (projections.length === 0) throw new Error("至少选择一种投影类型。");
  if (state.selectedOffsets.size === 0) throw new Error("至少选择一个对角方向。");
  const outputDirectory = $("#output-directory").value.trim();
  if (!outputDirectory) throw new Error("请填写 PNG 输出文件夹。");
  const schedule = currentSchedule();
  const selectedVersion = $("#version-input").value.trim() || state.instance.selectedVersion || undefined;
  const resources = selectedResources();
  return {
    source: {
      litematicPath: state.litematicPath,
      instancePath: state.instance.root,
      minecraftVersion: selectedVersion
    },
    resources,
    camera: {
      projections,
      baseAzimuthDeg: normalizeAzimuth($("#azimuth-number").value),
      elevationDeg: clampElevation($("#elevation-number").value),
      selectedOffsetsDeg: [...state.selectedOffsets].sort((a, b) => a - b),
      includeTopDown: $("#topdown-enabled").checked
    },
    lighting: {
      mode: $("#lighting-mode").value
    },
    output: {
      directory: outputDirectory,
      format: "png",
      background: selectedBackground(),
      resolution: {
        width: Number($("#output-width").value),
        height: Number($("#output-height").value)
      },
      namingPrefix: $("#naming-prefix").value.trim() || "litematic"
    },
    execution: {
      framebufferMode: $("#framebuffer-mode").value,
      directionSchedule: schedule,
      parallelWorkers: schedule === "parallel" ? 4 : 1,
      tileEdgePx: Number($("#tile-edge").value)
    }
  };
}

function bindDropZone(zoneSelector, inputSelector, kind) {
  const zone = $(zoneSelector);
  const input = $(inputSelector);
  const activatePicker = () => input.click();
  zone.addEventListener("click", activatePicker);
  zone.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      activatePicker();
    }
  });
  input.addEventListener("change", async () => {
    try { await uploadSelectedFiles(input.files, kind); } catch (error) { showToast(error.message, { error: true }); }
    input.value = "";
  });
  for (const eventName of ["dragenter", "dragover"]) {
    zone.addEventListener(eventName, (event) => {
      event.preventDefault();
      zone.classList.add("dragging");
    });
  }
  for (const eventName of ["dragleave", "drop"]) {
    zone.addEventListener(eventName, (event) => {
      event.preventDefault();
      zone.classList.remove("dragging");
    });
  }
  zone.addEventListener("drop", async (event) => {
    try { await uploadDroppedFiles(event, kind); } catch (error) { showToast(error.message, { error: true }); }
  });
}

function bindEvents() {
  $("#inspect-instance").addEventListener("click", async () => {
    try { await inspectInstance({ selectedVersion: $("#version-input").value.trim() || undefined }); } catch (error) { updateWorkerBadge("游戏文件夹读取失败", "error"); setInstanceSummary(error.message, "negative"); showToast(error.message, { error: true }); }
  });
  $("#version-input").addEventListener("change", async (event) => {
    try { await inspectInstance({ selectedVersion: event.target.value.trim() || undefined, silent: true }); } catch (error) { showToast(error.message, { error: true }); }
  });
  $("#version-input").addEventListener("keydown", (event) => {
    if (event.key === "Enter") $("#inspect-instance").click();
  });
  $("#inspect-litematic").addEventListener("click", async () => {
    try { await inspectLitematic($("#litematic-path").value); } catch (error) { setLitematicSummary(error.message, "negative"); showToast(error.message, { error: true }); }
  });
  $("#schematic-select").addEventListener("change", async (event) => {
    if (!event.target.value) return;
    try { await inspectLitematic(event.target.value); } catch (error) { setLitematicSummary(error.message, "negative"); showToast(error.message, { error: true }); }
  });
  $("#resource-pack-select").addEventListener("change", (event) => {
    state.selectedResourcePackPath = event.target.value;
    updateResourcePackSummary();
  });
  $("#add-resource-pack").addEventListener("click", async () => {
    try { await addResourcePackFromPath($("#resource-pack-path").value); } catch (error) { showToast(error.message, { error: true }); }
  });
  $("#resource-pack-path").addEventListener("keydown", (event) => {
    if (event.key === "Enter") $("#add-resource-pack").click();
  });
  $("#litematic-path").addEventListener("keydown", (event) => {
    if (event.key === "Enter") $("#inspect-litematic").click();
  });
  $("#resolution-preset").addEventListener("change", (event) => {
    const preset = PRESETS[event.target.value];
    if (!preset) return;
    $("#output-width").value = preset.width;
    $("#output-height").value = preset.height;
  });
  for (const id of ["output-width", "output-height"]) {
    $("#" + id).addEventListener("input", () => $("#resolution-preset").value = "custom");
  }
  $("#azimuth-range").addEventListener("input", () => updateAnglesFrom("range"));
  $("#azimuth-number").addEventListener("change", () => updateAnglesFrom("azimuth"));
  $("#elevation-range").addEventListener("input", () => updateAnglesFrom("range"));
  $("#elevation-number").addEventListener("change", () => updateAnglesFrom("elevation"));
  for (const button of $$(".direction-button")) {
    button.addEventListener("click", () => {
      const offset = Number(button.dataset.offset);
      if (state.selectedOffsets.has(offset)) state.selectedOffsets.delete(offset);
      else state.selectedOffsets.add(offset);
      setDirections(state.selectedOffsets);
    });
  }
  $("#select-all-directions").addEventListener("click", () => setDirections(DIRECTIONS.map((item) => item.offset)));
  $("#clear-directions").addEventListener("click", () => setDirections([]));
  for (const radio of $$('input[name="direction-schedule"]')) radio.addEventListener("change", updateParallelNote);
  $("#theme-toggle").addEventListener("change", (event) => setTheme(event.target.checked));
  $("#create-render-plan").addEventListener("click", async () => {
    try {
      const { plan, session, worker } = await api("/api/render-start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          job: collectJob(),
          launcher: {
            javaExecutable: $("#java-executable").value.trim() || undefined
          }
        })
      });
      renderPlan(plan, session, worker);
      watchSession(session);
      showToast(worker.mode === "reused" ? "已连接到现有 Fabric Worker。" : "隐藏 Fabric Worker 已启动。");
    } catch (error) {
      showToast(error.message, { error: true });
    }
  });
  $("#test-fabric-bridge").addEventListener("click", async () => {
    try { await testFabricBridge(); } catch (error) { updateWorkerBadge("Fabric 启动检测失败", "error"); showToast(error.message, { error: true }); }
  });
  bindDropZone("#litematic-drop-zone", "#litematic-file", "litematic");
  bindDropZone("#resource-pack-drop-zone", "#resource-pack-file", "resource-pack");
  $("#pick-resource-pack-directory").addEventListener("click", () => $("#resource-pack-directory").click());
  $("#resource-pack-directory").addEventListener("change", async (event) => {
    try { await uploadResourcePackFolder(event.target.files, event.target.files[0]?.webkitRelativePath?.split("/")[0] || "resource-pack"); } catch (error) { showToast(error.message, { error: true }); }
    event.target.value = "";
  });
  window.addEventListener("resize", drawCameraPreview);
}

async function initialize() {
  initializeTheme();
  bindEvents();
  updateAnglesFrom("range");
  updateParallelNote();
  renderTemporaryPacks();
  try {
    const defaults = await api("/api/defaults");
    $("#instance-path").value = defaults.defaultTargetPath;
    await inspectInstance({ silent: true });
  } catch (error) {
    setInstanceSummary("已填入 PCL 版本目录。请在你的 Windows 电脑运行本程序后点击“读取”。", "");
    updateWorkerBadge("等待实例接入");
  }
  drawCameraPreview();
}

initialize();
