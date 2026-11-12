'use strict';

/* =====================================================================
   FileMaster frontend (vanilla JavaScript)

   How it talks to the Java backend:
     1. The user picks files   -> we keep them in a JS array.
     2. We put them in a FormData object (a multipart/form-data request).
     3. We send it with XMLHttpRequest (it reports upload progress) or fetch.
     4. Spring returns the new file as bytes; we turn them into a Blob and
        point a Download link at it.

   "Each file separately" = the browser calls the single-file endpoint once per file.
   "Merge"               = the browser sends all files in ONE request to a /merge endpoint.
   ===================================================================== */

// Keep these in sync with application.properties. The server checks them again.
const MAX_FILES = 8;
const MAX_SIZE = 20 * 1024 * 1024;
const MAX_TOTAL = 60 * 1024 * 1024;

const COMPRESS_EXT = ['jpg', 'jpeg', 'png', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
                      'txt', 'csv', 'json', 'xml', 'html', 'css', 'js', 'md', 'log'];

const SVG_NS = 'http://www.w3.org/2000/svg';
const $ = (id) => document.getElementById(id);

/* ---------- small helpers ---------- */

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

function extOf(name) {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
}

function makeIcon(id) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'icon');
  const use = document.createElementNS(SVG_NS, 'use');
  use.setAttribute('href', '#' + id);
  svg.appendChild(use);
  return svg;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** Shows one message or a list of messages. textContent is used so text can never inject HTML. */
function showError(box, messages) {
  const list = Array.isArray(messages) ? messages : [messages];
  box.innerHTML = '';
  const wrapper = el('div');
  list.forEach((message) => wrapper.appendChild(el('div', '', message)));
  box.append(makeIcon('i-alert'), wrapper);
  box.hidden = false;
}

/** Returns an error message, or '' if the file looks fine. */
function checkFile(file, allowed, maxBytes = MAX_SIZE) {
  if (file.size === 0) return 'This file is empty.';
  if (file.size > maxBytes) return 'This file is too large (' + formatBytes(file.size) + '). The maximum is ' + Math.round(maxBytes / (1024 * 1024)) + ' MB.';
  const ext = extOf(file.name);
  if (!ext) return 'This file has no extension, so its type cannot be detected.';
  if (!allowed.includes(ext)) return "Unsupported file type '." + ext + "'.";
  return '';
}

/**
 * Adds newly chosen files to `items` (skipping duplicates and invalid files).
 * makeItem(file) builds the item object. Problems are collected in `errors`.
 */
function addFiles(items, newFiles, allowed, errors, makeItem) {
  const known = new Set(items.map((it) => it.file.name + '|' + it.file.size + '|' + it.file.lastModified));
  let total = items.reduce((sum, it) => sum + it.file.size, 0);

  for (const file of newFiles) {
    const key = file.name + '|' + file.size + '|' + file.lastModified;
    if (known.has(key)) continue;
    if (items.length >= MAX_FILES) {
      errors.push('You can add up to ' + MAX_FILES + ' files at once.');
      break;
    }
    const problem = checkFile(file, allowed);
    if (problem) { errors.push(file.name + ': ' + problem); continue; }
    if (total + file.size > MAX_TOTAL) {
      errors.push('The files together are limited to 60 MB.');
      break;
    }
    items.push(makeItem(file));
    known.add(key);
    total += file.size;
  }
}

/** Sends one request. Resolves with the response blob, rejects with a readable Error. */
function postForm(url, formData, onUploadProgress, timeoutMs = 240000) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);
    xhr.responseType = 'blob';
    xhr.timeout = timeoutMs;

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onUploadProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.upload.onload = () => onUploadProgress(100);

    xhr.onerror = () => reject(new Error('Network error. Check your connection and try again.'));
    xhr.ontimeout = () => reject(new Error('The server took too long to respond. Please try again.'));
    xhr.onload = async () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve({ blob: xhr.response, header: (name) => xhr.getResponseHeader(name) });
      } else {
        reject(new Error(await readServerError(xhr.response, xhr.status)));
      }
    };
    xhr.send(formData);
  });
}

async function readServerError(blob, status) {
  try {
    const data = JSON.parse(await blob.text());
    if (data && data.error) return data.error;
  } catch (ignored) { /* not JSON */ }
  return 'Something went wrong (error ' + status + '). Please try again.';
}

function filenameFrom(header, fallback) {
  const match = /filename="?([^";]+)"?/.exec(header || '');
  return match ? match[1] : fallback;
}

/** Wires click, keyboard and drag-and-drop to one dropzone. */
function setupDropzone(zone, input, onFiles) {
  zone.addEventListener('click', () => input.click());
  zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); }
  });
  ['dragenter', 'dragover'].forEach((type) => zone.addEventListener(type, (e) => {
    e.preventDefault();
    zone.classList.add('dragover');
  }));
  ['dragleave', 'drop'].forEach((type) => zone.addEventListener(type, (e) => {
    e.preventDefault();
    zone.classList.remove('dragover');
  }));
  zone.addEventListener('drop', (e) => {
    if (e.dataTransfer.files.length > 0) onFiles(Array.from(e.dataTransfer.files));
  });
  input.addEventListener('change', () => {
    if (input.files.length > 0) onFiles(Array.from(input.files));
    input.value = ''; // lets the user pick the same file again later
  });
}

/**
 * Draws the list of chosen files.
 * rows: [{ name, size, badge, select?: {options, value, onChange}, canMove? }]
 * handlers: { onRemove(index), onMove(index, delta) }
 */
function renderFileList(ul, rows, handlers) {
  ul.innerHTML = '';
  rows.forEach((row, index) => {
    const li = el('li', 'file-item');
    const meta = el('div', 'file-meta');
    meta.append(el('strong', '', row.name), el('small', '', formatBytes(row.size)));
    li.append(el('span', 'file-icon', row.badge), meta);

    if (row.select) {
      const select = el('select', 'row-select');
      select.setAttribute('aria-label', 'Convert ' + row.name + ' to');
      row.select.options.forEach((value) => {
        const option = el('option', '', value);
        option.value = value;
        select.appendChild(option);
      });
      select.value = row.select.value;
      select.addEventListener('change', () => row.select.onChange(select.value));
      li.appendChild(select);
    }

    if (row.canMove) {
      [['i-up', -1, 'Move up', index === 0], ['i-down', 1, 'Move down', index === rows.length - 1]]
        .forEach(([icon, delta, label, disabled]) => {
          const button = el('button', 'icon-btn');
          button.type = 'button';
          button.disabled = disabled;
          button.setAttribute('aria-label', label + ': ' + row.name);
          button.appendChild(makeIcon(icon));
          button.addEventListener('click', () => handlers.onMove(index, delta));
          li.appendChild(button);
        });
    }

    const remove = el('button', 'icon-btn');
    remove.type = 'button';
    remove.setAttribute('aria-label', 'Remove ' + row.name);
    remove.appendChild(makeIcon('i-x'));
    remove.addEventListener('click', () => handlers.onRemove(index));
    li.appendChild(remove);

    ul.appendChild(li);
  });
  ul.hidden = rows.length === 0;
}

/** The progress bar and the status line under the button. */
function createProgress(prefix) {
  const wrap = $(prefix + '-progress');
  const bar = $(prefix + '-bar');
  const status = $(prefix + '-status');
  return {
    reset() { wrap.hidden = true; bar.classList.remove('working'); bar.style.width = '0%'; status.textContent = ''; },
    start(text) { wrap.hidden = false; bar.classList.remove('working'); bar.style.width = '0%'; status.textContent = text; },
    set(fraction, text) { bar.classList.remove('working'); bar.style.width = Math.round(fraction * 100) + '%'; status.textContent = text; },
    working(text) { bar.classList.add('working'); status.textContent = text; },
    done(text) { bar.classList.remove('working'); bar.style.width = '100%'; status.textContent = text; }
  };
}

/** The list of finished files with a Download button each. Frees blob memory on reset. */
function createResultList(ul) {
  let urls = [];
  return {
    reset() {
      urls.forEach((url) => URL.revokeObjectURL(url));
      urls = [];
      ul.innerHTML = '';
    },
    add({ name, detail, note, blob, error }) {
      const li = el('li', 'result-item' + (error ? ' is-error' : ''));
      const meta = el('div', 'res-meta');
      meta.appendChild(el('strong', '', name));
      meta.appendChild(el('small', '', error || detail));
      if (note) meta.appendChild(el('small', 'res-note', note));
      li.appendChild(meta);

      if (blob) {
        const url = URL.createObjectURL(blob);
        urls.push(url);
        const link = el('a', 'btn btn-primary btn-small');
        link.href = url;
        link.download = name;
        link.append(makeIcon('i-download'), document.createTextNode(' Download'));
        li.appendChild(link);
      }
      ul.appendChild(li);
    }
  };
}

/** Uploads progress -> bar fraction for "file i of n". */
function uploadStep(progress, index, total, name, verb) {
  return (pct) => {
    if (pct < 100) {
      progress.set((index + (pct / 100) * 0.7) / total,
        'Uploading ' + name + (total > 1 ? ' (' + (index + 1) + ' of ' + total + ')' : '') + '… ' + pct + '%');
    } else {
      progress.working(verb + ' ' + name + (total > 1 ? ' (' + (index + 1) + ' of ' + total + ')' : '') + '…');
    }
  };
}

/* ---------- page navigation (#home, #compress, #convert) ---------- */

const views = {
  home: $('view-home'), compress: $('view-compress'), convert: $('view-convert'),
  image: $('view-image'), audio: $('view-audio'), video: $('view-video')
};

function route() {
  const name = location.hash.replace('#', '');
  const active = views[name] ? name : 'home';
  Object.keys(views).forEach((key) => { views[key].hidden = key !== active; });
  const view = views[active];
  view.classList.remove('enter');
  void view.offsetWidth; // restart the CSS animation
  view.classList.add('enter');
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', route);
route();

/* =====================================================================
   File Compressor
   ===================================================================== */

(function initCompressor() {
  const listEl = $('compress-files');
  const modesEl = $('compress-modes');
  const btn = $('compress-btn');
  const errorBox = $('compress-error');
  const resultBox = $('compress-result');
  const resultTitle = $('compress-result-title');
  const estimateNote = $('compress-estimate-note');
  const progress = createProgress('compress');
  const results = createResultList($('compress-result-list'));

  let items = [];
  let estimateTimer = null;
  let estimateAbort = null;

  const mode = () => (items.length > 1 ? document.querySelector('input[name="compress-mode"]:checked').value : 'individual');
  const preset = () => document.querySelector('input[name="preset"]:checked').value;

  function resetFeedback() {
    errorBox.hidden = true;
    resultBox.hidden = true;
    results.reset();
    progress.reset();
  }

  function refresh() {
    renderFileList(listEl, items.map((it) => ({
      name: it.file.name, size: it.file.size, badge: extOf(it.file.name).toUpperCase()
    })), { onRemove: (i) => { items.splice(i, 1); resetFeedback(); refresh(); } });

    modesEl.hidden = items.length < 2;
    btn.disabled = items.length === 0;
    btn.textContent = items.length < 2 ? 'Compress file'
      : mode() === 'merge' ? 'Compress and merge into one ZIP'
      : 'Compress ' + items.length + ' files';
    scheduleEstimate();
  }

  function onFiles(files) {
    resetFeedback();
    const errors = [];
    addFiles(items, files, COMPRESS_EXT, errors, (file) => ({ file }));
    if (errors.length > 0) showError(errorBox, errors);
    refresh();
  }

  /* ----- real sizes for Small / Medium / High ----- */

  function setSizeText(text) {
    document.querySelectorAll('.preset-size').forEach((node) => { node.textContent = text; });
  }

  function scheduleEstimate() {
    clearTimeout(estimateTimer);
    if (estimateAbort) estimateAbort.abort();
    estimateNote.hidden = true;
    if (items.length === 0) { setSizeText('Add a file to see the size'); return; }
    setSizeText('Calculating…');
    estimateTimer = setTimeout(runEstimate, 500);
  }

  async function runEstimate() {
    const controller = new AbortController();
    estimateAbort = controller;
    const form = new FormData();
    items.forEach((it) => form.append('files', it.file));
    form.append('mode', mode());

    try {
      const response = await fetch('/api/compress/estimate', { method: 'POST', body: form, signal: controller.signal });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || 'Could not calculate the sizes.');

      data.presets.forEach((p) => {
        const node = document.querySelector('.preset-size[data-preset="' + p.preset + '"]');
        if (node) {
          node.textContent = formatBytes(p.size) + (p.percentSaved > 0 ? ' · saves ' + p.percentSaved.toFixed(0) + '%' : ' · no savings');
        }
      });
      estimateNote.textContent = 'Original size: ' + formatBytes(data.originalSize)
        + '. These sizes come from really compressing your files.';
      estimateNote.hidden = false;
    } catch (err) {
      if (err.name === 'AbortError') return;
      setSizeText('Size unavailable');
    }
  }

  /* ----- compress ----- */

  async function runIndividual(chosen) {
    const batch = items.slice(); // a copy, so removing a file mid-way cannot break the loop
    const total = batch.length;
    let done = 0, originalSum = 0, resultSum = 0;
    progress.start('Starting…');
    resultBox.hidden = false;
    resultTitle.textContent = 'Working…';

    for (let i = 0; i < total; i++) {
      const file = batch[i].file;
      const form = new FormData();
      form.append('file', file);
      form.append('preset', chosen);
      try {
        const res = await postForm('/api/compress', form, uploadStep(progress, i, total, file.name, 'Compressing'));
        const original = Number(res.header('X-Original-Size'));
        const compressed = Number(res.header('X-Result-Size'));
        const saved = Number(res.header('X-Saved-Percent'));
        results.add({
          name: filenameFrom(res.header('Content-Disposition'), file.name),
          detail: formatBytes(original) + ' → ' + formatBytes(compressed) + ' · saved ' + saved.toFixed(1) + '%',
          note: res.header('X-Result-Note'),
          blob: res.blob
        });
        done++; originalSum += original; resultSum += compressed;
      } catch (err) {
        results.add({ name: file.name, error: err.message });
      }
      progress.set((i + 1) / total, 'Finished ' + (i + 1) + ' of ' + total);
    }

    progress.done('Done.');
    if (done === 0) {
      resultTitle.textContent = 'No files could be compressed';
    } else {
      const savedPct = originalSum > 0 ? Math.max(0, (1 - resultSum / originalSum) * 100) : 0;
      resultTitle.textContent = done + ' of ' + total + ' compressed: ' + formatBytes(originalSum)
        + ' → ' + formatBytes(resultSum) + ' (saved ' + savedPct.toFixed(1) + '%)';
    }
  }

  async function runMerge(chosen) {
    const form = new FormData();
    items.forEach((it) => form.append('files', it.file));
    form.append('preset', chosen);
    progress.start('Uploading…');

    const res = await postForm('/api/compress/merge', form, (pct) => {
      if (pct < 100) progress.set((pct / 100) * 0.7, 'Uploading… ' + pct + '%');
      else progress.working('Compressing and building the ZIP…');
    });
    const original = Number(res.header('X-Original-Size'));
    const compressed = Number(res.header('X-Result-Size'));
    const saved = Number(res.header('X-Saved-Percent'));

    resultBox.hidden = false;
    resultTitle.textContent = items.length + ' files merged into one ZIP';
    results.add({
      name: filenameFrom(res.header('Content-Disposition'), 'filemaster-compressed.zip'),
      detail: formatBytes(original) + ' → ' + formatBytes(compressed) + ' · saved ' + saved.toFixed(1) + '%',
      note: res.header('X-Result-Note'),
      blob: res.blob
    });
    progress.done('Done.');
  }

  async function run() {
    if (items.length === 0) return;
    resetFeedback();
    btn.disabled = true;
    try {
      if (mode() === 'merge') await runMerge(preset());
      else await runIndividual(preset());
    } catch (err) {
      progress.reset();
      showError(errorBox, err.message);
    } finally {
      btn.disabled = items.length === 0;
    }
  }

  setupDropzone($('compress-drop'), $('compress-input'), onFiles);
  document.querySelectorAll('input[name="compress-mode"]').forEach((radio) => radio.addEventListener('change', refresh));
  btn.addEventListener('click', run);
})();

/* =====================================================================
   File Converter
   ===================================================================== */

(function initConverter() {
  const listEl = $('convert-files');
  const modesEl = $('convert-modes');
  const mergeRadio = $('convert-merge-radio');
  const mergeHint = $('convert-merge-hint');
  const mergeRow = $('convert-merge-row');
  const mergeSelect = $('convert-merge-target');
  const btn = $('convert-btn');
  const errorBox = $('convert-error');
  const resultBox = $('convert-result');
  const resultTitle = $('convert-result-title');
  const progress = createProgress('convert');
  const results = createResultList($('convert-result-list'));

  let formats = null;   // answer of GET /api/convert/formats
  let items = [];       // { file, source, targets, target }

  fetch('/api/convert/formats')
    .then((r) => r.json())
    .then((data) => { formats = data; })
    .catch(() => showError(errorBox, 'Could not load the supported formats. Please refresh the page.'));

  const merging = () => items.length > 1 && mergeRadio.checked;

  function resetFeedback() {
    errorBox.hidden = true;
    resultBox.hidden = true;
    results.reset();
    progress.reset();
  }

  /** Formats that EVERY chosen file can be converted to (needed for merging). */
  function commonTargets() {
    if (items.length < 2) return [];
    if (!items.every((it) => formats.mergeFormats.includes(it.source))) return [];
    return items[0].targets.filter((t) => items.every((it) => it.targets.includes(t)));
  }

  function refresh() {
    const common = items.length > 1 && formats ? commonTargets() : [];
    const canMerge = common.length > 0;

    modesEl.hidden = items.length < 2;
    mergeRadio.disabled = !canMerge;
    if (items.length > 1) {
      mergeHint.textContent = canMerge
        ? 'All files joined, in the order shown'
        : items.some((it) => formats && !formats.mergeFormats.includes(it.source))
          ? 'Images cannot be merged'
          : 'These files have no format in common';
    }
    if (!canMerge && mergeRadio.checked) {
      document.querySelector('input[name="convert-mode"][value="individual"]').checked = true;
    }

    const isMerging = merging();
    mergeRow.hidden = !isMerging;
    if (isMerging) {
      const previous = mergeSelect.value;
      mergeSelect.innerHTML = '';
      common.forEach((value) => {
        const option = el('option', '', value);
        option.value = value;
        mergeSelect.appendChild(option);
      });
      if (common.includes(previous)) mergeSelect.value = previous;
    }

    renderFileList(listEl, items.map((it) => ({
      name: it.file.name,
      size: it.file.size,
      badge: it.source,
      canMove: isMerging,
      select: isMerging ? null : { options: it.targets, value: it.target, onChange: (v) => { it.target = v; } }
    })), {
      onRemove: (i) => { items.splice(i, 1); resetFeedback(); refresh(); },
      onMove: (i, delta) => {
        const j = i + delta;
        if (j < 0 || j >= items.length) return;
        [items[i], items[j]] = [items[j], items[i]];
        refresh();
      }
    });

    btn.disabled = items.length === 0;
    btn.textContent = items.length < 2 ? 'Convert file'
      : isMerging ? 'Merge and convert'
      : 'Convert ' + items.length + ' files';
  }

  function onFiles(files) {
    resetFeedback();
    if (!formats) {
      showError(errorBox, 'The supported formats are still loading. Please try again in a moment.');
      return;
    }
    const errors = [];
    addFiles(items, files, Object.keys(formats.extensions), errors, (file) => {
      const source = formats.extensions[extOf(file.name)];
      const targets = formats.conversions[source] || [];
      return { file, source, targets, target: targets[0] };
    });
    if (errors.length > 0) showError(errorBox, errors);
    refresh();
  }

  /* ----- convert ----- */

  async function runIndividual() {
    const batch = items.slice(); // a copy, so removing a file mid-way cannot break the loop
    const total = batch.length;
    let done = 0;
    progress.start('Starting…');
    resultBox.hidden = false;
    resultTitle.textContent = 'Working…';

    for (let i = 0; i < total; i++) {
      const item = batch[i];
      const form = new FormData();
      form.append('file', item.file);
      form.append('targetFormat', item.target);
      try {
        const res = await postForm('/api/convert', form, uploadStep(progress, i, total, item.file.name, 'Converting'));
        results.add({
          name: filenameFrom(res.header('Content-Disposition'), 'converted-file'),
          detail: item.source + ' → ' + item.target + ' · ' + formatBytes(Number(res.header('X-Original-Size')))
            + ' → ' + formatBytes(Number(res.header('X-Result-Size'))),
          note: res.header('X-Result-Note'),
          blob: res.blob
        });
        done++;
      } catch (err) {
        results.add({ name: item.file.name, error: err.message });
      }
      progress.set((i + 1) / total, 'Finished ' + (i + 1) + ' of ' + total);
    }

    progress.done('Done.');
    resultTitle.textContent = done === 0 ? 'No files could be converted' : done + ' of ' + total + ' converted';
  }

  async function runMerge() {
    const target = mergeSelect.value;
    const form = new FormData();
    items.forEach((it) => form.append('files', it.file));
    form.append('targetFormat', target);
    progress.start('Uploading…');

    const res = await postForm('/api/convert/merge', form, (pct) => {
      if (pct < 100) progress.set((pct / 100) * 0.7, 'Uploading… ' + pct + '%');
      else progress.working('Merging and converting to ' + target + '…');
    });

    resultBox.hidden = false;
    resultTitle.textContent = items.length + ' files merged into one ' + target + ' file';
    results.add({
      name: filenameFrom(res.header('Content-Disposition'), 'merged-file'),
      detail: formatBytes(Number(res.header('X-Original-Size'))) + ' → ' + formatBytes(Number(res.header('X-Result-Size'))),
      note: res.header('X-Result-Note'),
      blob: res.blob
    });
    progress.done('Done.');
  }

  async function run() {
    if (items.length === 0) return;
    resetFeedback();
    btn.disabled = true;
    try {
      if (merging()) await runMerge();
      else await runIndividual();
    } catch (err) {
      progress.reset();
      showError(errorBox, err.message);
    } finally {
      btn.disabled = items.length === 0;
    }
  }

  setupDropzone($('convert-drop'), $('convert-input'), onFiles);
  document.querySelectorAll('input[name="convert-mode"]').forEach((radio) => radio.addEventListener('change', refresh));
  btn.addEventListener('click', run);
})();


/* =====================================================================
   Image Compressor (JPG, PNG, WebP) - each image is sent on its own
   ===================================================================== */

(function initImageCompressor() {
  const IMAGE_EXT = ['jpg', 'jpeg', 'png', 'webp'];
  const listEl = $('image-files');
  const btn = $('image-btn');
  const errorBox = $('image-error');
  const resultBox = $('image-result');
  const resultTitle = $('image-result-title');
  const progress = createProgress('image');
  const results = createResultList($('image-result-list'));
  let items = [];

  const quality = () => document.querySelector('input[name="image-quality"]:checked').value;

  function resetFeedback() {
    errorBox.hidden = true;
    resultBox.hidden = true;
    results.reset();
    progress.reset();
  }

  function refresh() {
    renderFileList(listEl, items.map((it) => ({
      name: it.file.name, size: it.file.size, badge: extOf(it.file.name).toUpperCase()
    })), { onRemove: (i) => { items.splice(i, 1); resetFeedback(); refresh(); } });
    btn.disabled = items.length === 0;
    btn.textContent = items.length > 1 ? 'Compress ' + items.length + ' images' : 'Compress image';
  }

  function onFiles(files) {
    resetFeedback();
    const errors = [];
    addFiles(items, files, IMAGE_EXT, errors, (file) => ({ file }));
    if (errors.length > 0) showError(errorBox, errors);
    refresh();
  }

  async function run() {
    if (items.length === 0) return;
    resetFeedback();
    btn.disabled = true;

    const batch = items.slice(); // a copy, so removing a file mid-way cannot break the loop
    const total = batch.length;
    const chosen = quality();
    let done = 0, originalSum = 0, resultSum = 0;
    progress.start('Starting…');
    resultBox.hidden = false;
    resultTitle.textContent = 'Working…';

    for (let i = 0; i < total; i++) {
      const file = batch[i].file;
      const form = new FormData();
      form.append('file', file);
      form.append('quality', chosen);
      try {
        const res = await postForm('/api/image/compress', form, uploadStep(progress, i, total, file.name, 'Compressing'));
        const original = Number(res.header('X-Original-Size'));
        const compressed = Number(res.header('X-Result-Size'));
        const saved = Number(res.header('X-Saved-Percent'));
        results.add({
          name: filenameFrom(res.header('Content-Disposition'), file.name),
          detail: 'Original ' + formatBytes(original) + ' → compressed ' + formatBytes(compressed)
            + ' · saved ' + saved.toFixed(1) + '%',
          note: res.header('X-Result-Note'),
          blob: res.blob
        });
        done++; originalSum += original; resultSum += compressed;
      } catch (err) {
        results.add({ name: file.name, error: err.message });
      }
      progress.set((i + 1) / total, 'Finished ' + (i + 1) + ' of ' + total);
    }

    progress.done('Done.');
    if (done === 0) {
      resultTitle.textContent = 'No images could be compressed';
    } else {
      const savedPct = originalSum > 0 ? Math.max(0, (1 - resultSum / originalSum) * 100) : 0;
      resultTitle.textContent = done + ' of ' + total + ' compressed: ' + formatBytes(originalSum)
        + ' → ' + formatBytes(resultSum) + ' (saved ' + savedPct.toFixed(1) + '%)';
    }
    btn.disabled = items.length === 0;
  }

  setupDropzone($('image-drop'), $('image-input'), onFiles);
  btn.addEventListener('click', run);
})();

/* =====================================================================
   Audio Converter and Video Converter (FFmpeg on the server).
   Both screens work the same way, so one function builds both.
   ===================================================================== */

function initMediaTool(prefix, api) {
  const MAX_MEDIA = 50 * 1024 * 1024; // keep in sync with filemaster.max-media-size-mb
  const CLIENT_TIMEOUT = 330000;       // a little longer than the server's 240 s processing limit

  const listEl = $(prefix + '-files');
  const btn = $(prefix + '-btn');
  const errorBox = $(prefix + '-error');
  const resultBox = $(prefix + '-result');
  const resultTitle = $(prefix + '-result-title');
  const progress = createProgress(prefix);
  const results = createResultList($(prefix + '-result-list'));

  let formats = null;  // answer of GET /api/<audio|video>/formats
  let item = null;     // { file, source, targets, target }

  fetch('/api/' + api + '/formats')
    .then((r) => r.json())
    .then((data) => { formats = data; })
    .catch(() => showError(errorBox, 'Could not load the supported formats. Please refresh the page.'));

  function resetFeedback() {
    errorBox.hidden = true;
    resultBox.hidden = true;
    results.reset();
    progress.reset();
  }

  function refresh() {
    renderFileList(listEl, item ? [{
      name: item.file.name,
      size: item.file.size,
      badge: item.source,
      select: { options: item.targets, value: item.target, onChange: (v) => { item.target = v; } }
    }] : [], { onRemove: () => { item = null; resetFeedback(); refresh(); } });
    btn.disabled = !item;
  }

  function onFiles(files) {
    resetFeedback();
    if (!formats) {
      showError(errorBox, 'The supported formats are still loading. Please try again in a moment.');
      return;
    }
    if (files.length > 1) {
      showError(errorBox, 'Please add one file at a time.');
      return;
    }
    const file = files[0];
    const problem = checkFile(file, Object.keys(formats.extensions), MAX_MEDIA);
    if (problem) {
      showError(errorBox, file.name + ': ' + problem);
      return;
    }
    const source = formats.extensions[extOf(file.name)];
    const targets = formats.conversions[source] || [];
    item = { file, source, targets, target: targets[0] };
    refresh();
  }

  async function run() {
    if (!item) return;
    resetFeedback();
    btn.disabled = true;

    const current = item;
    const form = new FormData();
    form.append('file', current.file);
    form.append('targetFormat', current.target);
    progress.start('Uploading… 0%');

    try {
      const res = await postForm('/api/' + api + '/convert', form, (pct) => {
        if (pct < 100) progress.set((pct / 100) * 0.5, 'Uploading… ' + pct + '%');
        else progress.working('Converting ' + current.file.name + '… large files can take a few minutes.');
      }, CLIENT_TIMEOUT);

      resultBox.hidden = false;
      resultTitle.textContent = current.source + ' converted to ' + current.target;
      results.add({
        name: filenameFrom(res.header('Content-Disposition'), 'converted-file'),
        detail: current.source + ' → ' + current.target + ' · ' + formatBytes(Number(res.header('X-Original-Size')))
          + ' → ' + formatBytes(Number(res.header('X-Result-Size'))),
        note: res.header('X-Result-Note'),
        blob: res.blob
      });
      progress.done('Done.');
    } catch (err) {
      progress.reset();
      showError(errorBox, err.message);
    } finally {
      btn.disabled = !item;
    }
  }

  setupDropzone($(prefix + '-drop'), $(prefix + '-input'), onFiles);
  btn.addEventListener('click', run);
}

initMediaTool('audio', 'audio');
initMediaTool('video', 'video');
