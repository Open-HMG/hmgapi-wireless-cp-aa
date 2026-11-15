'use strict';
const $ = id => document.getElementById(id);
let replay = null, scenarios = [], stepIndex = 0, playTime = 0, playing = false, lastFrame = 0, tab = 'events', busy = false;
let clusterAt = null, hudAt = null;
let customScenario = null, animationId = null;
const controls = ['first', 'previous', 'play', 'next', 'last', 'timeline', 'speed'];
const text = (id, value) => { $(id).textContent = value; };
const pretty = value => JSON.stringify(value, null, 2);
const clock = ms => `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}.${String(Math.floor(ms % 1000)).padStart(3, '0')}`;
const label = value => value === null || value === undefined ? '—' : String(value);
const dist = value => value ? value.text : '—';

function error(message, editor = false) {
  const el = $(editor ? 'editor-error' : 'error');
  el.textContent = message || ''; el.hidden = !message;
}
function setBusy(value) {
  busy = value;
  ['scenario', 'edit', 'export', 'apply', 'import', 'import-button', ...controls].forEach(id => { $(id).disabled = value || (!replay && !['scenario', 'import', 'import-button'].includes(id)); });
  if (!value) text('apply', 'Apply & replay');
}
function stop() { playing = false; if (animationId !== null) cancelAnimationFrame(animationId); animationId = null; text('play', '▶'); $('play').setAttribute('aria-label', 'Play replay'); }

async function loadScenario(scenario, startAtGuidance = false) {
  if (busy) return false;
  stop(); setBusy(true); error('');
  try {
    const response = await fetch('/api/replay', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: typeof scenario === 'string' ? scenario : JSON.stringify(scenario)});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `Replay failed (${response.status})`);
    replay = result;
    const selected = scenarios.find(s => s.id === result.scenario.id);
    if (!selected || JSON.stringify(selected) !== JSON.stringify(result.scenario)) {
      customScenario = result.scenario;
      let option = $('scenario').querySelector('option[value="custom"]');
      if (!option) { option = new Option('Custom scenario', 'custom'); $('scenario').add(option); }
      option.textContent = result.scenario.title || 'Custom scenario'; $('scenario').value = 'custom';
    } else $('scenario').value = result.scenario.id;
    text('description', result.scenario.description || 'Custom version 1 replay scenario.');
    const sum = result.summary;
    $('summary').classList.toggle('failed', sum.failed > 0);
    $('summary').replaceChildren();
    const total = document.createElement('strong'); total.textContent = sum.failed ? `${sum.failed} failed` : `${sum.passed}/${sum.checks}`;
    $('summary').append(total, document.createTextNode(sum.failed ? 'EXPECTED ≠ OUTPUT' : sum.checks ? 'CHECKS PASSED' : 'NO EXPECTATIONS'));
    text('event-count', sum.events); text('call-count', sum.calls); text('check-count', sum.checks);
    const duration = result.steps[result.steps.length - 1].at;
    $('timeline').max = Math.max(1, duration); text('duration', clock(duration));
    text('speed-unit', result.scenario.units === 'km' ? 'KM/H' : 'MPH');
    let index = 0;
    if (startAtGuidance) {
      const first = result.steps.findIndex(s => s.display.hud.count > 0);
      if (first >= 0) index = first;
    }
    buildEvidence(); selectStep(index);
    return true;
  } catch (e) {
    error(e.message); if ($('editor').open) error(e.message, true); return false;
  } finally { setBusy(false); }
}

const arrowPaths = {
  straight: 'M32 56V9M17 24L32 9l15 15',
  right: 'M17 56V31Q17 20 28 20H55M41 7l14 13-14 13',
  left: 'M47 56V31Q47 20 36 20H9M23 7L9 20l14 13',
  slightRight: 'M23 56V37Q23 31 28 26L49 8M29 8h20v20',
  slightLeft: 'M41 56V37Q41 31 36 26L15 8M35 8H15v20',
  uturn: 'M47 56V23a15 15 0 0 0-30 0v22M6 34l11 11 11-11',
  sharpRight: 'M18 56V12l30 31M30 43h18V25',
  sharpLeft: 'M46 56V12L16 43M34 43H16V25',
  arrival: 'M19 57V8m0 0h32l-8 10 8 10H19',
};
function iconName(code, lane = false) {
  const laneNames = {1:'straight',2:'slightRight',3:'right',4:'sharpRight',5:'uturn',6:'sharpLeft',7:'left',8:'slightLeft'};
  const names = {3:'arrival',64:'straight',65:'straight',66:'slightRight',67:'right',68:'sharpRight',69:'sharpLeft',70:'left',71:'slightLeft',72:'uturn',112:'arrival',145:'slightLeft'};
  return (lane ? laneNames : names)[code];
}
function icon(code, lane = false) {
  const name = iconName(code, lane);
  const wrap = document.createElement('span');
  if (code === null || code === undefined || code === 0) { wrap.textContent = '—'; return wrap; }
  if (!name) { wrap.textContent = `#${code}`; wrap.title = 'Unmapped display icon'; return wrap; }
  // Path data is a fixed local mapping. Scenario text never enters HTML.
  wrap.innerHTML = `<svg viewBox="0 0 64 64" role="img" aria-label="${name}"><path d="${arrowPaths[name]}" fill="none" stroke="currentColor" stroke-width="5" stroke-linecap="square" stroke-linejoin="miter"/></svg>`;
  return wrap.firstChild;
}
function lanes(id, data) {
  const elements = data.map(lane => {
    const div = document.createElement('span'); div.className = 'lane' + (lane.state === 1 ? ' preferred' : '');
    div.title = `Stock lane state ${lane.state}`;
    lane.icons.forEach(code => div.append(icon(code, true))); return div;
  });
  $(id).replaceChildren(...elements);
  $(id).setAttribute('aria-label', data.length ? `${data.length} lanes; ${data.filter(l => l.state === 1).length} recommended` : 'No lane guidance');
}
function cluster(state) {
  const c = state.cluster, holder = $('cluster-guidance'); holder.replaceChildren();
  if (c.icon === 8 && c.heading !== null) {
    const compass = document.createElement('div'); compass.className = 'compass';
    const dirs = ['N','NE','E','SE','S','SW','W','NW']; compass.textContent = dirs[Math.round(c.heading / 45) % 8];
    const caption = document.createElement('div'); caption.className = 'compass-caption'; caption.textContent = `${c.heading}° · STOCK COMPASS`;
    holder.append(compass, caption); text('cluster-road', 'Last received compass');
  } else if (c.icon !== null && c.icon !== 0) {
    const row = document.createElement('div'); row.className = 'cluster-guidance-line';
    row.append(icon(c.icon));
    const d = document.createElement('span'); d.className = 'cluster-distance'; d.textContent = dist(c.distance); row.append(d); holder.append(row);
    text('cluster-road', state.road || `Icon ${c.icon}`);
  } else {
    const row = document.createElement('div'); row.className = 'cluster-guidance-line'; row.textContent = '—'; holder.append(row);
    text('cluster-road', 'No turn output');
  }
  text('ownership', state.routeActive ? 'PHONE ROUTE ACTIVE' : 'PHONE ROUTE IDLE');
  text('destination', state.destination || '—'); text('remaining', dist(state.remaining)); text('eta', 'ETA ' + (state.eta || '—'));
  const context = state.destination && state.remaining ? 'DESTINATION + TRIP PRESENT' : state.destination ? 'TRIP NOT SUPPLIED' : state.remaining ? 'DESTINATION NOT SUPPLIED' : 'NO ROUTE CONTEXT';
  text('context-status', context); lanes('cluster-lanes', state.lanes);
}
function render() {
  if (!replay) return;
  const step = replay.steps[stepIndex], state = step.display, hud = state.hud;
  cluster(state);
  $('hud-primary-icon').replaceChildren(icon(hud.count ? hud.primaryIcon : null));
  text('hud-primary', hud.count ? dist(hud.primary) : '—');
  $('hud-following-wrap').hidden = hud.count < 2;
  $('hud-secondary-icon').replaceChildren(icon(hud.secondaryIcon)); text('hud-secondary', dist(hud.secondary));
  text('hud-road', hud.count ? hud.road || 'Unnamed instruction' : 'No guidance output');
  lanes('hud-lanes', state.lanes);
  clusterAt = null; hudAt = null;
  for (let i = 0; i <= stepIndex; i++) {
    for (const call of replay.steps[i].calls) {
      if (['tbtInfo','tbtSubInfo','destinationName','turnList','crossroadName'].includes(call.method)) clusterAt = replay.steps[i].at;
      if (['turnListNew','tbtSubInfo2C','laneInfo'].includes(call.method)) hudAt = replay.steps[i].at;
    }
  }
  text('cluster-output', 'Last cluster output · ' + (clusterAt === null ? '—' : clock(clusterAt)));
  text('hud-output', 'Last HUD output · ' + (hudAt === null ? '—' : clock(hudAt)));
  text('component', label(step.bridge.component < 0 ? null : step.bridge.component));
  text('current', label(step.bridge.current < 0 ? null : step.bridge.current));
  text('following', label(step.bridge.following < 0 ? null : step.bridge.following));
  text('event-position', `EVENT ${String(stepIndex + 1).padStart(2, '0')} / ${replay.steps.length}`);
  text('event-label', step.input.label || step.input.kind);
  text('truth', step.input.truth || (step.checks.length ? 'Expected display values are checked against independently decoded output below.' : 'No independent expectation on this event. Inspect the input and emitted messages.'));
  $('step-checks').replaceChildren(...step.checks.map(check => {
    const span = document.createElement('span'); span.className = 'check-chip' + (check.passed ? '' : ' fail');
    span.textContent = `${check.passed ? '✓' : '✕'} ${check.field}: ${label(check.actual)}`;
    if (!check.passed) span.textContent += ` · expected ${label(check.expected)}`;
    return span;
  }));
  renderInspector(); renderTime();
}
function renderInspector() {
  if (!replay) return;
  const holder = $('inspector-content'), step = replay.steps[stepIndex];
  const scroll = holder.scrollTop; holder.replaceChildren();
  if (tab === 'events') {
    replay.steps.forEach((s, index) => {
      const button = document.createElement('button'); button.className = 'event-row' + (index === stepIndex ? ' active' : '');
      button.setAttribute('aria-current', index === stepIndex ? 'step' : 'false');
      const t = document.createElement('span'); t.className = 'event-time'; t.textContent = (s.at / 1000).toFixed(1) + 's';
      const info = document.createElement('span');
      const kind = document.createElement('span'); kind.className = 'event-kind'; kind.textContent = s.input.kind;
      const name = document.createElement('span'); name.className = 'event-name'; name.textContent = s.input.label || `Event ${index + 1}`;
      info.append(kind, name); button.append(t, info);
      if (s.checks.length) {
        const fail = s.checks.some(c => !c.passed), result = document.createElement('span');
        result.className = 'event-result' + (fail ? ' fail' : ''); result.textContent = fail ? '✕' : '✓'; button.append(result);
      }
      button.addEventListener('click', () => { stop(); selectStep(index); }); holder.append(button);
    });
    holder.scrollTop = scroll;
  } else if (tab === 'output') {
    if (!step.calls.length) empty('This event emitted no display calls. The last display state is retained.');
    for (const call of step.calls) {
      const card = document.createElement('div'); card.className = 'call-card';
      const h = document.createElement('h3'); h.textContent = call.method;
      const pre = document.createElement('pre'); pre.textContent = pretty(call.args); card.append(h, pre);
      if (call.method === 'turnListNew' || call.method === 'laneInfo') {
        const hex = document.createElement('div'); hex.className = 'frame-hex'; hex.textContent = call.args[0].map(b => b.toString(16).padStart(2, '0')).join(' '); card.append(hex);
      }
      holder.append(card);
    }
  } else if (tab === 'input') {
    const pre = document.createElement('pre'); pre.textContent = pretty(step.input); holder.append(pre);
  } else {
    const pre = document.createElement('pre'); pre.textContent = step.diagnostics.join('\n\n') || 'No bridge diagnostics for this event.'; holder.append(pre);
  }
  function empty(message) { const p = document.createElement('p'); p.className = 'empty-note'; p.textContent = message; holder.append(p); }
}
function renderTime() { text('time', clock(playTime)); $('timeline').value = playTime; }
function selectStep(index) { stepIndex = Math.max(0, Math.min(index, replay.steps.length - 1)); playTime = replay.steps[stepIndex].at; render(); }
function seek(ms) {
  playTime = ms; let index = 0;
  for (let i = 0; i < replay.steps.length; i++) { if (replay.steps[i].at <= ms) index = i; else break; }
  if (index !== stepIndex) { stepIndex = index; render(); } else renderTime();
}
function togglePlay() {
  if (!replay || busy) return;
  if (playing) { stop(); return; }
  if (playTime >= replay.steps[replay.steps.length - 1].at) selectStep(0);
  playing = true; lastFrame = 0; text('play', 'Ⅱ'); $('play').setAttribute('aria-label', 'Pause replay'); animationId = requestAnimationFrame(advance);
}
function advance(now) {
  if (!playing) return;
  if (lastFrame) {
    const end = replay.steps[replay.steps.length - 1].at;
    seek(Math.min(end, playTime + (now - lastFrame) * Number($('speed').value)));
    if (playTime >= end) { stop(); return; }
  }
  lastFrame = now; animationId = requestAnimationFrame(advance);
}
function buildEvidence() {
  const holder = $('evidence'); holder.replaceChildren();
  const p = document.createElement('p'); p.textContent = replay.scenario.provenance || 'Custom input. Provenance not supplied.'; holder.append(p);
  const ul = document.createElement('ul');
  [...replay.backendInfo.limitations, ...replay.model.assumptions].forEach(note => { const li = document.createElement('li'); li.textContent = note; ul.append(li); });
  const pre = document.createElement('pre'); pre.textContent = pretty(replay.backendInfo.sourceHashes); holder.append(ul, pre);
}

$('scenario').addEventListener('change', () => { const s = $('scenario').value === 'custom' ? customScenario : scenarios.find(s => s.id === $('scenario').value); if (s) loadScenario(s); });
$('first').addEventListener('click', () => { stop(); selectStep(0); });
$('previous').addEventListener('click', () => { stop(); selectStep(stepIndex - 1); });
$('next').addEventListener('click', () => { stop(); selectStep(stepIndex + 1); });
$('last').addEventListener('click', () => { stop(); selectStep(replay.steps.length - 1); });
$('play').addEventListener('click', togglePlay);
$('timeline').addEventListener('input', () => { stop(); seek(Number($('timeline').value)); });
document.querySelectorAll('[data-tab]').forEach(button => button.addEventListener('click', () => {
  tab = button.dataset.tab;
  document.querySelectorAll('[data-tab]').forEach(b => b.setAttribute('aria-selected', String(b === button)));
  $('inspector-content').setAttribute('aria-labelledby', button.id); $('inspector-content').scrollTop = 0; renderInspector();
}));
$('edit').addEventListener('click', () => { stop(); $('scenario-json').value = pretty(replay.scenario); error('', true); $('editor').showModal(); });
$('apply').addEventListener('click', async () => {
  const payload = $('scenario-json').value;
  if (new Blob([payload]).size > 1048576) { error('Scenario exceeds 1 MiB', true); return; }
  text('apply', 'Running Java…'); error('', true);
  if (await loadScenario(payload)) $('editor').close();
});
$('import-button').addEventListener('click', () => $('import').click());
$('import').addEventListener('change', async event => {
  const file = event.target.files[0];
  if (file) {
    if (file.size > 1048576) error('Scenario exceeds 1 MiB');
    else { try { await loadScenario(await file.text()); } catch (e) { error(e.message); } }
  }
  event.target.value = '';
});
$('export').addEventListener('click', () => {
  const blob = new Blob([pretty(replay)], {type: 'application/json'}), url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = 'palisade-replay.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
document.addEventListener('keydown', event => {
  if (!replay || busy || $('editor').open || /INPUT|TEXTAREA|SELECT|BUTTON/.test(event.target.tagName)) return;
  if (event.code === 'Space') { event.preventDefault(); togglePlay(); }
  else if (event.key === 'ArrowLeft') { event.preventDefault(); stop(); selectStep(stepIndex - 1); }
  else if (event.key === 'ArrowRight') { event.preventDefault(); stop(); selectStep(stepIndex + 1); }
});

(async function init() {
  controls.forEach(id => { $(id).disabled = true; });
  try {
    const responses = await Promise.all([fetch('/api/config'), fetch('/api/scenarios')]);
    if (responses.some(r => !r.ok)) throw new Error('Unable to load simulator configuration');
    const [config, list] = await Promise.all(responses.map(r => r.json()));
    text('backend', config.label); scenarios = list;
    if (!scenarios.length) throw new Error('No scenarios available');
    $('scenario').replaceChildren(...scenarios.map(s => new Option(s.title || s.id, s.id)));
    await loadScenario(scenarios[0], true);
  } catch (e) { error(e.message); }
})();
