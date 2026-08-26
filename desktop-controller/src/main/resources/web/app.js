(() => {
  const $ = id => document.getElementById(id);
  const ratioParts = {
    '16:9': [16, 9], '4:3': [4, 3], '3:2': [3, 2], '1:1': [1, 1],
    '2:3': [2, 3], '3:4': [3, 4], '9:16': [9, 16]
  };
  const presetDimensions = {
    '16:9': { '1080p': [1920, 1080], '2160p': [3840, 2160], '4320p': [7680, 4320], '130mp': [15200, 8550] },
    '4:3': { '1080p': [1664, 1248], '2160p': [3328, 2496], '4320p': [6656, 4992], '130mp': [13168, 9876] },
    '3:2': { '1080p': [1764, 1176], '2160p': [3528, 2352], '4320p': [7056, 4704], '130mp': [13968, 9312] },
    '1:1': { '1080p': [1440, 1440], '2160p': [2880, 2880], '4320p': [5760, 5760], '130mp': [11400, 11400] },
    '2:3': { '1080p': [1176, 1764], '2160p': [2352, 3528], '4320p': [4704, 7056], '130mp': [9312, 13968] },
    '3:4': { '1080p': [1248, 1664], '2160p': [2496, 3328], '4320p': [4992, 6656], '130mp': [9876, 13168] },
    '9:16': { '1080p': [1080, 1920], '2160p': [2160, 3840], '4320p': [4320, 7680], '130mp': [8550, 15200] }
  };
  let sessionId = null;
  let poller = null;
  const state = $('status');

  function status(text, bad = false) {
    state.textContent = text;
    state.style.color = bad ? 'var(--danger)' : 'var(--text)';
  }

  async function request(url, body) {
    const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || '本地服务请求失败。');
    return payload;
  }

  function list(id, values) {
    const element = $(id);
    element.replaceChildren(...values.map(value => {
      const option = document.createElement('option');
      option.value = value;
      return option;
    }));
  }

  function shortName(path) {
    return String(path || '').split(/[\\/]/).filter(Boolean).pop() || '';
  }

  function fileStem(path) {
    return shortName(path).replace(/\.litematic$/i, '').trim();
  }

  function setSchematicOptions(entries, preferredPath = '') {
    const select = $('litematicSelect');
    const normalized = (entries || []).map(entry => typeof entry === 'string'
      ? { name: shortName(entry), path: entry, folder: entry.slice(0, Math.max(entry.lastIndexOf('\\'), entry.lastIndexOf('/'))) }
      : entry).filter(entry => entry && entry.path);
    const duplicates = normalized.reduce((counts, entry) => {
      counts[entry.name] = (counts[entry.name] || 0) + 1;
      return counts;
    }, {});
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = normalized.length ? `请选择投影（共 ${normalized.length} 个）` : '此文件夹内没有 .litematic';
    const options = normalized.map(entry => {
      const option = document.createElement('option');
      option.value = entry.path;
      option.textContent = duplicates[entry.name] > 1 ? `${entry.name} — ${shortName(entry.folder)}` : entry.name;
      option.title = entry.path;
      return option;
    });
    select.replaceChildren(placeholder, ...options);
    if (preferredPath && normalized.some(entry => entry.path === preferredPath)) select.value = preferredPath;
    updateSchematicHint();
  }

  function updateSchematicHint() {
    const selected = $('litematicSelect').selectedOptions[0];
    $('schematicHint').textContent = $('litematicSelect').value
      ? `已选择：${selected ? selected.textContent : shortName($('litematicSelect').value)}`
      : '尚未选择投影文件。';
  }

  function setResourcePackOptions(paths) {
    const select = $('resourcePackSelect');
    const previous = select.value;
    const normalized = (paths || []).map(path => String(path || '')).filter(Boolean);
    const names = normalized.reduce((counts, path) => {
      const name = shortName(path);
      counts[name] = (counts[name] || 0) + 1;
      return counts;
    }, {});
    const defaultOption = document.createElement('option');
    defaultOption.value = '';
    defaultOption.textContent = 'Minecraft 默认材质包';
    const options = normalized.map(path => {
      const option = document.createElement('option');
      option.value = path;
      option.textContent = names[shortName(path)] > 1 ? `${shortName(path)} — 同名资源包` : shortName(path);
      option.title = path;
      return option;
    });
    select.replaceChildren(defaultOption, ...options);
    select.value = normalized.includes(previous) ? previous : '';
  }

  function selectedPackPath() {
    return $('resourcePackPath').value.trim() || $('resourcePackSelect').value;
  }

  function linkRange(range, number) {
    $(range).addEventListener('input', () => { $(number).value = $(range).value; });
    $(number).addEventListener('input', () => {
      const value = Number($(number).value);
      if (Number.isFinite(value)) $(range).value = value;
    });
  }

  function dimensionsForRatio() {
    const ratio = presetDimensions[$('aspectRatio').value];
    const value = ratio && ratio[$('resolution').value];
    return value ? value.slice() : null;
  }

  function applyResolutionPreset() {
    const dimensions = dimensionsForRatio();
    if (!dimensions) return;
    $('width').value = dimensions[0];
    $('height').value = dimensions[1];
    const label = $('resolution').selectedOptions[0].textContent.replace(/（.*$/, '').trim();
    $('resolutionHint').textContent = `${$('aspectRatio').value} · ${label}：${dimensions[0]} × ${dimensions[1]}。修改任一边会保持该比例。`;
  }

  function syncRatioDimension(changed) {
    const pair = ratioParts[$('aspectRatio').value];
    if (!pair) return;
    const ratio = pair[0] / pair[1];
    const width = Number($('width').value);
    const height = Number($('height').value);
    if (changed === 'width' && Number.isFinite(width) && width > 0) $('height').value = Math.max(1, Math.min(16384, Math.round(width / ratio)));
    if (changed === 'height' && Number.isFinite(height) && height > 0) $('width').value = Math.max(1, Math.min(16384, Math.round(height * ratio)));
    $('resolutionHint').textContent = `${$('aspectRatio').value} 比例已锁定；可继续直接修改宽度或高度。`;
  }

  function updateResolutionControls(applyPreset = true) {
    const custom = $('aspectRatio').value === 'custom';
    $('resolution').disabled = custom;
    if (custom) {
      $('resolutionHint').textContent = '自定义比例：分辨率预设已锁定，请分别输入宽度和高度。';
      return;
    }
    if (applyPreset) applyResolutionPreset();
  }

  function syncPrefixFromSchematic() {
    const stem = fileStem($('litematicSelect').value);
    if (stem) $('prefix').value = stem;
  }

  function job() {
    const selectedOffsetsDeg = [];
    if ($('direction0').checked) selectedOffsetsDeg.push(0);
    if ($('direction90').checked) selectedOffsetsDeg.push(90);
    if ($('direction180').checked) selectedOffsetsDeg.push(180);
    if ($('direction270').checked) selectedOffsetsDeg.push(270);
    if (!selectedOffsetsDeg.length) throw new Error('请至少选择一个相对四向。');
    const pack = selectedPackPath();
    return {
      schemaVersion: 1,
      source: { litematicPath: $('litematicSelect').value, instancePath: $('instancePath').value.trim(), minecraftVersion: $('version').value.trim() },
      resources: { selectedResourcePacks: pack ? [pack] : [] },
      camera: {
        projection: document.querySelector('input[name="projection"]:checked').value,
        baseAzimuthDeg: Number($('azimuth').value), elevationDeg: Number($('elevation').value), selectedOffsetsDeg
      },
      output: {
        directory: $('outputDirectory').value.trim(), width: Number($('width').value), height: Number($('height').value),
        background: $('background').value, namingPrefix: $('prefix').value.trim() || 'litematic'
      },
      execution: { framebufferMode: 'single', directionSchedule: $('schedule').value, lighting: $('lighting').value }
    };
  }

  async function inspect() {
    try {
      status('正在读取 Minecraft 游戏文件夹…');
      const result = await request('/api/inspect', { instancePath: $('instancePath').value.trim(), minecraftVersion: $('version').value.trim() });
      $('instancePath').value = result.root;
      $('version').value = result.selectedVersion;
      list('versions', result.versions || []);
      $('schematicDirectory').value = result.defaultSchematicDirectory || '';
      setSchematicOptions(result.schematicEntries || result.schematics || []);
      setResourcePackOptions(result.resourcePacks || []);
      if (!$('outputDirectory').value.trim()) $('outputDirectory').value = result.defaultOutputDirectory || '';
      $('runtime').textContent = result.runtime || '';
      $('instanceSummary').textContent = `${result.summary} ${result.hasVersionJson ? '已找到版本 JSON。' : '缺少同名版本 JSON。'}`;
      status('Minecraft 游戏文件夹读取完成。请选择投影文件后开始 PNG 渲染。');
    } catch (error) {
      status(error.message, true);
    }
  }

  async function scanSchematics() {
    try {
      status('正在扫描投影文件夹…');
      const previous = $('litematicSelect').value;
      const result = await request('/api/schematics', { directory: $('schematicDirectory').value.trim() });
      $('schematicDirectory').value = result.directory;
      setSchematicOptions(result.schematics || [], previous);
      status(`已扫描 ${result.count || 0} 个投影文件。请选择文件名后开始渲染。`);
    } catch (error) {
      status(error.message, true);
    }
  }

  async function bootstrap() {
    try {
      const response = await fetch('/api/bootstrap');
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || '无法读取本机默认设置。');
      $('runtime').textContent = result.runtime || '';
      if (!$('outputDirectory').value.trim()) $('outputDirectory').value = result.defaultOutputDirectory || '';
    } catch (error) {
      status(error.message, true);
    }
  }

  async function poll() {
    if (!sessionId) return;
    try {
      const response = await fetch(`/api/session?id=${encodeURIComponent(sessionId)}`);
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || '无法读取渲染状态。');
      const event = result.event || {};
      const fraction = typeof event.fraction === 'number' ? `（${Math.round(event.fraction * 100)}%）` : '';
      if (event.type === 'completed') {
        const count = Array.isArray(event.outputs) ? event.outputs.length : 0;
        status(`PNG 渲染完成，共输出 ${count} 个文件。`);
      } else if (event.type === 'failed') {
        status('PNG 渲染失败。请查看程序目录 logs 文件夹中的最新 .log。', true);
      } else {
        status(`PNG 渲染中…${fraction || (result.running ? '' : '正在整理结果…')}`);
      }
      if (event.type === 'completed' || event.type === 'failed' || (!result.running && event.type)) {
        clearInterval(poller); poller = null; $('renderButton').disabled = false;
      }
    } catch (error) {
      status(error.message, true);
      clearInterval(poller); poller = null; $('renderButton').disabled = false;
    }
  }

  async function render() {
    try {
      $('renderButton').disabled = true;
      status('正在创建隔离 Fabric 渲染会话…');
      const result = await request('/api/render', { job: job() });
      sessionId = result.sessionId;
      status(result.message || '正在启动 PNG 渲染…');
      if (poller) clearInterval(poller);
      poller = setInterval(poll, 700);
      poll();
    } catch (error) {
      $('renderButton').disabled = false;
      status(error.message, true);
    }
  }

  function setTheme(dark) {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    $('themeToggle').setAttribute('aria-pressed', String(dark));
    $('themeToggle').textContent = dark ? '明亮模式' : '夜间模式';
    localStorage.setItem('lrs-theme', dark ? 'dark' : 'light');
  }

  linkRange('azimuthRange', 'azimuth');
  linkRange('elevationRange', 'elevation');
  $('inspectButton').addEventListener('click', inspect);
  $('scanSchematicsButton').addEventListener('click', scanSchematics);
  $('litematicSelect').addEventListener('change', () => { updateSchematicHint(); syncPrefixFromSchematic(); });
  $('aspectRatio').addEventListener('change', () => updateResolutionControls(true));
  $('resolution').addEventListener('change', applyResolutionPreset);
  $('width').addEventListener('input', () => syncRatioDimension('width'));
  $('height').addEventListener('input', () => syncRatioDimension('height'));
  $('renderButton').addEventListener('click', render);
  $('themeToggle').addEventListener('click', () => setTheme(document.documentElement.dataset.theme !== 'dark'));
  $('stopButton').addEventListener('click', async () => {
    try { await request('/api/stop', {}); status('DsLR 网页服务正在关闭。'); } catch (error) { status(error.message, true); }
  });
  setTheme(localStorage.getItem('lrs-theme') === 'dark');
  updateResolutionControls(true);
  bootstrap();
})();
