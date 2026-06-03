import { io } from 'socket.io-client';

const socket = io('http://localhost:3001', {
  transports: ['websocket']
});

console.log('Connecting to local backend...');

socket.on('connect', () => {
  console.log('Connected to local backend!');
  console.log('Emitting iniciar_sessao...');
  socket.emit('iniciar_sessao', 'test_user_123');
  
  // Wait a second and send a message
  setTimeout(() => {
    console.log('Emitting mensagem_usuario...');
    socket.emit('mensagem_usuario', 'Student English Level Profile: Beginner\nPlease introduce yourself as Elias and start the conversation immediately matching this level.');
  }, 1000);
});

socket.on('estado_ia', (estado) => {
  console.log(`[Event: estado_ia] -> ${estado}`);
});

socket.on('texto_chunk', (chunk) => {
  console.log(`[Event: texto_chunk] -> ${chunk}`);
});

socket.on('audio_opus_frame', (frame) => {
  console.log(`[Event: audio_opus_frame] -> seq: ${frame.seq}, size: ${frame.frame.length} chars base64`);
});

socket.on('mensagem_ia', (msg) => {
  console.log('[Event: mensagem_ia] -> received final response:');
  console.log(JSON.stringify(msg, null, 2));
  socket.disconnect();
  process.exit(0);
});

socket.on('erro_backend', (err) => {
  console.error(`[Event: erro_backend] -> ERROR: ${err}`);
  socket.disconnect();
  process.exit(1);
});

socket.on('disconnect', () => {
  console.log('Disconnected.');
});
