import { randomUUID } from 'node:crypto';
import { checkTadeuQuota, consumeTadeuUsage, VoiceMinuteAccumulator } from './tadeuMetering.js';
const costly = new Set(['mensagem_usuario', 'speech_end', 'traduzir_texto', 'echo_avaliar', 'echo_frase_nova', 'shadow_speak']);
export function installSocketMetering(socket, { check = checkTadeuQuota, consume = consumeTadeuUsage, sessionId = randomUUID() } = {}) {
  const meter = new VoiceMinuteAccumulator(sessionId);
  let pending = Promise.resolve();
  let failed = false;
  let scheduledMinutes = 0;
  const token = socket.data.accessToken;
  const emit = socket.emit.bind(socket);
  const fail = () => {
    failed = true;
    emit('erro_backend', 'Não foi possível validar a disponibilidade de voz.');
    socket.disconnect(true);
  };
  socket.use(async ([event], next) => {
    if (!costly.has(event)) return next();
    try { await pending; if (failed) throw new Error('metering_failed'); await check(token); next(); }
    catch { fail(); }
  });
  socket.emit = (event, ...args) => {
    if (event === 'audio_opus_frame') {
      if (failed) return socket;
      meter.addSpeech(20); // encoder contract: one 960-sample frame at 48kHz
      if (Math.floor(meter.totalSpeechMs / 60000) > scheduledMinutes) {
        scheduledMinutes = Math.floor(meter.totalSpeechMs / 60000);
        pending = pending.then(async () => {
          while (!failed && meter.consumedMinutes < Math.floor(meter.totalSpeechMs / 60000)) {
            const minute = meter.consumedMinutes + 1;
            await consume({ token, amount: 1, idempotencyKey: meter.idempotencyKey(minute) });
            meter.markConsumed(1);
          }
        }).catch(fail);
      }
    }
    return emit(event, ...args);
  };
  return { settled: () => pending, meter };
}
