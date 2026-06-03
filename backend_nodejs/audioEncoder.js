import pkg from '@discordjs/opus';
const { OpusEncoder } = pkg;

const SAMPLE_RATE = 48000;
const CHANNELS = 1;
const FRAME_SIZE = 960; // 20ms @ 48kHz

const encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS);

/**
 * Converte buffer PCM Float32 (little-endian) em chunks Opus
 * @param {Buffer} pcmFloat32Buffer - Buffer raw da Cartesia
 * @returns {Buffer[]} Array de frames Opus encodados
 */
export function encodePCMToOpus(pcmFloat32Buffer) {
  // Float32 → Int16 (Opus espera PCM Int16)
  const float32Array = new Float32Array(
    pcmFloat32Buffer.buffer,
    pcmFloat32Buffer.byteOffset,
    pcmFloat32Buffer.byteLength / 4
  );

  const int16Array = new Int16Array(float32Array.length);
  for (let i = 0; i < float32Array.length; i++) {
    // Clamp e converte: float [-1.0, 1.0] → int16 [-32768, 32767]
    const s = Math.max(-1, Math.min(1, float32Array[i]));
    int16Array[i] = s < 0 ? s * 32768 : s * 32767;
  }

  const opusFrames = [];
  const bytesPerFrame = FRAME_SIZE * 2; // Int16 = 2 bytes

  for (let offset = 0; offset + bytesPerFrame <= int16Array.byteLength; offset += bytesPerFrame) {
    const chunk = Buffer.from(int16Array.buffer, offset, bytesPerFrame);
    try {
      const encoded = encoder.encode(chunk, FRAME_SIZE);
      opusFrames.push(encoded);
    } catch (err) {
      console.error('[OpusEncoder] Frame encoding error:', err.message);
    }
  }

  return opusFrames;
}

export { FRAME_SIZE, SAMPLE_RATE };
