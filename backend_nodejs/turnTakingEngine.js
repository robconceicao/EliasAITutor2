import Groq from 'groq-sdk';

export const TURN_DECISION = { WAIT: 'wait', RESPOND: 'respond', CLARIFY: 'clarify' };

export class TurnTakingEngine {
    constructor(sessionId) {
        this.sessionId           = sessionId;
        this.silenceThresholdMs  = 800;
        this.maxWaitMs           = 2500;
        this.avgUserPauseMs      = 900;
        this.pauseHistory        = [];
        this.pendingTimer        = null;
        this.onDecision          = null;
        this.groqClient          = process.env.GROQ_API_KEY ? new Groq({ apiKey: process.env.GROQ_API_KEY }) : null;
    }

    onSpeechStart() {
        if (this.pendingTimer) { clearTimeout(this.pendingTimer); this.pendingTimer = null; }
    }

    async onSpeechEnd(transcript, speechDurationMs, vadConfidence = 1.0) {
        if (speechDurationMs < 200 && vadConfidence < 0.6) return;
        const delay = this.calcDelay(transcript);
        this.pendingTimer = setTimeout(async () => {
            const decision = await this.decide(transcript, speechDurationMs);
            if (this.onDecision) this.onDecision(decision, transcript);
        }, delay);
    }

    calcDelay(transcript) {
        const isQuestion = /[?？]$/.test(transcript.trim()) ||
            /^(what|how|why|when|where|who|can|could|would|should|is|are|do|does|did)/i.test(transcript.trim());
        const isShort    = transcript.trim().split(/\s+/).length <= 3;
        let base         = this.avgUserPauseMs;
        if (isQuestion) base *= 0.7;
        if (isShort)    base *= 1.4;
        return Math.min(Math.max(base, 400), this.maxWaitMs);
    }

    async decide(transcript, speechDurationMs) {
        const quick = this.quickCheck(transcript);
        if (quick) return quick;
        try {
            const complete = await this.checkSemantic(transcript);
            this.learnPause(speechDurationMs);
            return complete ? TURN_DECISION.RESPOND : TURN_DECISION.WAIT;
        } catch (_) { return TURN_DECISION.RESPOND; }
    }

    quickCheck(t) {
        const s = t.trim();
        if (/[.!?]$/.test(s)) return TURN_DECISION.RESPOND;
        if (/\b(and|but|or|so|because|when|if|that|the|a|an|to|of|in|on|at|for|with)$/i.test(s))
            return TURN_DECISION.WAIT;
        if (s.split(/\s+/).length < 2) return TURN_DECISION.CLARIFY;
        return null;
    }

    async checkSemantic(transcript) {
        if (!this.groqClient) return true;
        try {
            const res = await this.groqClient.chat.completions.create({
                model: 'llama3-8b-8192',
                max_tokens: 10,
                messages: [
                    { 
                        role: 'system', 
                        content: 'You are a turn-taking detector. Reply only "yes" or "no". Does this utterance represent a complete thought expecting a response?' 
                    },
                    { role: 'user', content: `Utterance: "${transcript}"` }
                ]
            });
            return res.choices[0]?.message?.content?.toLowerCase().trim().includes('yes') ?? true;
        } catch (e) {
            console.error('[TurnTakingEngine] checkSemantic err:', e);
            return true;
        }
    }

    learnPause(durationMs) {
        this.pauseHistory.push(durationMs);
        if (this.pauseHistory.length > 10) this.pauseHistory.shift();
        const alpha = 0.3;
        this.avgUserPauseMs = this.pauseHistory.reduce(
            (acc, v) => acc * (1 - alpha) + v * alpha, this.avgUserPauseMs
        );
    }

    destroy() { if (this.pendingTimer) clearTimeout(this.pendingTimer); }
}
