const activeGenerations = new Map();

function registerGeneration(sessionId, llmAbort, ttsAbort) {
    activeGenerations.set(sessionId, { llmAbort, ttsAbort });
}

function clearGeneration(sessionId) {
    activeGenerations.delete(sessionId);
}

function registerBargeInHandler(socket) {
    socket.on('barge_in', ({ sessionId }) => {
        const gen = activeGenerations.get(sessionId);
        if (!gen) return;
        if (gen.llmAbort && !gen.llmAbort.signal.aborted) gen.llmAbort.abort();
        if (gen.ttsAbort && !gen.ttsAbort.signal.aborted) gen.ttsAbort.abort();
        socket.emit('audio_stream_cancelled', { sessionId, reason: 'barge_in' });
        clearGeneration(sessionId);
    });
}

export { registerBargeInHandler, registerGeneration, clearGeneration };
