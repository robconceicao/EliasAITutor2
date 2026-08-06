/**
 * Smoke tests for Modo Programa REST API (F1–F5).
 * Start server first: node server.js
 * Then: node test_program_api.js
 */
// A porta precisa acompanhar o .env (PORT=3001), senão o smoke test nunca roda.
const PORT = process.env.PORT || 3001;
const BASE = process.env.BACKEND_URL || `http://127.0.0.1:${PORT}`;

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

  // Nivelamento — o início do programa não é fixo na Semana 1
  r = await req('GET', '/program/placement');
  assert(r.status === 200, 'GET /program/placement 200');
  assert(r.json.questions?.length === 20, '20 questões de nivelamento');
  assert(
    !JSON.stringify(r.json).includes('correct_index'),
    'nivelamento não vaza gabarito'
  );
  const placementSize = r.json.questions.length;

  r = await req('POST', '/program/placement/submit', {
    answers: new Array(placementSize).fill(-1),
  });
  assert(r.status === 200, 'POST placement (tudo errado) 200');
  assert(r.json.start_week === 1, 'errando tudo → começa na Semana 1');
  assert(r.json.state.placement_done === true, 'placement_done marcado');

  r = await req('POST', '/program/placement/submit', { beginner: true });
  assert(r.json.start_week === 1, 'atalho iniciante → Semana 1');

  r = await req('POST', '/program/placement/submit', { answers: [1, 2] });
  assert(r.status === 422, 'quantidade errada de respostas → 422');

  // F2 state — calendário anda, mas o gate de quiz é quem libera a semana
  const d14 = new Date(Date.now() - 14 * 86400000).toISOString().slice(0, 10);
  r = await req('PUT', '/program/state', { start_date: d14, week_mode: 'auto' });
  assert(r.status === 200, 'PUT /program/state auto');
  assert(r.json.calendar_week === 3, `calendário na semana 3 (got ${r.json.calendar_week})`);
  assert(
    r.json.current_week === 1 && r.json.gate_blocking_calendar === true,
    'sem quiz aprovado, o gate segura a semana e sinaliza o bloqueio'
  );

  // Dias travados pelo gate viram total_paused_days (a meta de 6 meses não some)
  r = await req('GET', '/program/state');
  assert(
    r.json.total_paused_days >= 1,
    `dia bloqueado contabilizado como pausa (got ${r.json.total_paused_days})`
  );

  // Liberando o gate, a semana do calendário passa a valer
  r = await req('POST', '/program/quiz/1/submit', { answers: [] });
  assert(r.status === 200, 'POST quiz submit responde 200');
  r = await req('PUT', '/program/state', {
    start_date: d14,
    week_mode: 'auto',
    mastery_cleared_week: 5,
    total_paused_days: 0,
  });
  assert(
    r.json.current_week === 3 && r.json.gate_blocking_calendar === false,
    `com o gate liberado, auto week = 3 (got ${r.json.current_week})`
  );

  r = await req('PUT', '/program/state', { week_mode: 'manual', current_week: 1 });
  assert(r.json.current_week === 1, 'manual week 1');
  r = await req('PUT', '/program/state', { current_week: 0 });
  assert(r.json.current_week === 1, 'clamp week min 1');
  r = await req('PUT', '/program/state', { current_week: 99 });
  assert(r.json.current_week <= 26, 'clamp week max 26');

  // Nivelamento em nível intermediário reancora tudo
  r = await req('PUT', '/program/state', { start_week: 9, placement_done: true });
  assert(r.json.start_week === 9, 'start_week persistido');
  assert(r.json.current_week >= 9, 'nunca volta antes da semana do nivelamento');

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

  const { computeTargetDate } = await import('./services/promptBuilder.js');
  assert(computeTargetDate('2026-01-15') === '2026-07-15', 'target = start + 6 months');
  const pTarget = buildSystemPrompt({
    weekDoc: w3,
    phase: 1,
    programMode: true,
    startDate: '2026-03-01',
  });
  assert(pTarget.content.includes('2026-03-01'), 'prompt injects start date');
  assert(pTarget.content.includes('September') || pTarget.content.includes('2026-09-01'), 'prompt injects +6m target');
  assert(!pTarget.content.includes('27 December 2026'), 'no fixed legacy target date');

  console.log('\n✅ All program API smoke tests passed');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
