/**
 * Smoke tests for Modo Programa REST API (F1–F5).
 * Start server first: node server.js
 * Then: node test_program_api.js
 */
const BASE = process.env.BACKEND_URL || 'http://127.0.0.1:3000';

async function req(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = text;
  }
  return { status: res.status, json };
}

function assert(cond, msg) {
  if (!cond) throw new Error('FAIL: ' + msg);
  console.log('  ✓', msg);
}

async function main() {
  console.log('Testing against', BASE);

  // F1
  let r = await req('GET', '/program/weeks');
  assert(r.status === 200, 'GET /program/weeks 200');
  assert(Array.isArray(r.json) && r.json.length === 26, '26 weeks returned');
  assert(r.json[0].week === 1 && r.json[25].week === 26, 'weeks ordered 1..26');
  assert(r.json[11].chunks?.length >= 1, 'week 12 has chunks');

  r = await req('GET', '/program/weeks/12');
  assert(r.status === 200, 'GET /program/weeks/12 200');
  assert(r.json.week === 12, 'week 12 field');
  assert(r.json.conversation_prompt, 'has conversation_prompt');
  assert(Array.isArray(r.json.chunks), 'has chunks array');

  r = await req('GET', '/program/weeks/99');
  assert(r.status === 404, 'GET invalid week 404');

  // F2 state
  r = await req('PUT', '/program/state', {
    start_date: new Date(Date.now() - 14 * 86400000).toISOString().slice(0, 10),
    week_mode: 'auto',
  });
  assert(r.status === 200, 'PUT /program/state auto');
  assert(r.json.current_week === 3, `auto week is 3 (got ${r.json.current_week})`);

  r = await req('PUT', '/program/state', { week_mode: 'manual', current_week: 1 });
  assert(r.json.current_week === 1, 'manual week 1');
  r = await req('PUT', '/program/state', { current_week: 0 });
  assert(r.json.current_week === 1, 'clamp week min 1');
  r = await req('PUT', '/program/state', { current_week: 99 });
  assert(r.json.current_week === 26, 'clamp week max 26');

  // F4 sessions
  r = await req('POST', '/sessions', {
    week: 3,
    type: 'themed',
    started_at: new Date().toISOString(),
  });
  assert(r.status === 201 && r.json.id, 'POST /sessions');
  const sid = r.json.id;

  r = await req('PATCH', `/sessions/${sid}/end`, {
    ended_at: new Date().toISOString(),
    duration_seconds: 120,
  });
  assert(r.status === 200, 'PATCH end short session');
  assert(r.json.feedback_status === 'none', 'no feedback under 5 min');

  r = await req('POST', '/sessions', { week: 3, type: 'quick', started_at: new Date().toISOString() });
  const sid2 = r.json.id;
  r = await req('PATCH', `/sessions/${sid2}/end`, {
    ended_at: new Date().toISOString(),
    duration_seconds: 600,
    transcript: 'Student: I go to school yesterday.\nTutor: Oh, you went to school yesterday?',
  });
  assert(r.json.feedback_status === 'pending', 'feedback pending for 10 min session');

  // F5
  r = await req('GET', '/progress/summary?days=30');
  assert(r.status === 200, 'GET /progress/summary');
  assert(typeof r.json.today_minutes === 'number', 'today_minutes');
  assert(typeof r.json.streak === 'number', 'streak');
  assert(Array.isArray(r.json.days), 'days array');

  // F3 prompt unit check
  const {
    buildSystemPrompt,
    PHASE_MASTER_PROMPTS,
    PROGRAM_ELIAS_MASTER_PROMPT,
  } = await import('./services/promptBuilder.js');
  const { loadCurriculumSeedFile } = await import('./services/loadCurriculumSeed.js');
  const { weeks } = loadCurriculumSeedFile();
  assert(weeks.length === 26, 'official seed has 26 weeks');
  assert(weeks[0].persona_city === 'New York', 'week 1 city New York');
  assert(weeks[1].persona_city === 'Boston', 'week 2 city Boston (official seed)');
  const w3 = weeks.find((w) => w.week === 3);
  const p3 = buildSystemPrompt({ weekDoc: w3, phase: 1, programMode: true });
  assert(p3.content.includes(PROGRAM_ELIAS_MASTER_PROMPT.slice(0, 80)), 'week 3 includes program master');
  assert(p3.content.includes(PHASE_MASTER_PROMPTS[1]), 'week 3 includes phase 1 calibration');
  assert(p3.content.includes('Week number: 3'), 'week 3 injects week number');
  assert(p3.content.includes(w3.title), 'week 3 injects title');
  assert(p3.content.includes(w3.conversation_prompt.slice(0, 40)), 'week 3 includes week prompt');
  assert(p3.content.includes('<RESPONSE>'), 'program prompt keeps XML envelope');
  const w22 = weeks.find((w) => w.week === 22);
  const p22 = buildSystemPrompt({ weekDoc: w22, phase: 4, programMode: true });
  assert(p22.content.includes(PHASE_MASTER_PROMPTS[4]), 'week 22 includes phase 4 calibration');
  assert(p22.content.includes('Week number: 22'), 'week 22 injects week number');
  const pDefault = buildSystemPrompt({ programMode: false });
  assert(pDefault.content.includes('Natural Approach'), 'default prompt unchanged');
  assert(!pDefault.content.includes('Fluência em Inglês em 6 Meses'), 'default is not program master');

  console.log('\n✅ All program API smoke tests passed');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
