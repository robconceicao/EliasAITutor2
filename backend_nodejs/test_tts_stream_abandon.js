/**
 * Confirms late primary audio is discarded after stream gen invalidation.
 * Simulates the production guard used in escutarRetornoElevenLabs(hooks.isActive).
 */
import assert from 'assert';

function createStreamGuard() {
  let gen = 0;
  let textOnly = false;
  let ativo = true;
  let activeSocket = null;

  return {
    attach(ws) {
      const myGen = ++gen;
      activeSocket = ws;
      return {
        isActive: () =>
          ativo && !textOnly && myGen === gen && activeSocket === ws,
        abandon() {
          gen += 1;
          if (activeSocket === ws) activeSocket = null;
        },
      };
    },
    goTextOnly() {
      textOnly = true;
      gen += 1;
      activeSocket = null;
    },
  };
}

const g = createStreamGuard();
const primary = { id: 'primary' };
const fallback = { id: 'fallback' };

const p = g.attach(primary);
assert.strictEqual(p.isActive(), true, 'primary active after attach');

// Late audio arrives after abandon (timeout → fallback)
p.abandon();
assert.strictEqual(p.isActive(), false, 'primary abandoned — late audio discarded');

const f = g.attach(fallback);
assert.strictEqual(f.isActive(), true, 'fallback active');
assert.strictEqual(p.isActive(), false, 'primary still dead after fallback attach');

// Text-only kills fallback too
g.goTextOnly();
assert.strictEqual(f.isActive(), false, 'text-only discards remaining TTS');

console.log('✅ TTS stream abandon / late-audio discard logic passed');
