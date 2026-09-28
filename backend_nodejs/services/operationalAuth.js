import { runAsUser } from './userContext.js';

export async function verifyAccessToken(token, { fetchImpl = fetch, env = process.env } = {}) {
  if (typeof token !== 'string' || !token || token.length > 8192) throw new Error('auth_required');
  const base = env.AUTH_SUPABASE_URL || env.TADEU_APPS_SUPABASE_URL;
  const key = env.AUTH_SUPABASE_ANON_KEY || env.TADEU_APPS_SUPABASE_ANON_KEY;
  if (!base || !key || !base.startsWith('https://')) throw new Error('auth_not_configured');
  // Auth service validates signature, expiration and the user. Never trust decoded JWT/userId alone.
  const response = await fetchImpl(base.replace(/\/$/, '') + '/auth/v1/user', {
    headers: { apikey: key, Authorization: 'Bearer ' + token },
    signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error('auth_invalid');
  const user = await response.json();
  if (typeof user.id !== 'string' || !user.id) throw new Error('auth_invalid');
  return { id: user.id };
}
export function createHttpAuth(verify = verifyAccessToken) {
  return async (req, res, next) => {
    try {
      const header = req.headers.authorization || '';
      const token = /^Bearer (\S+)$/i.exec(header)?.[1];
      const user = await verify(token);
      req.auth = { userId: user.id, token };
      runAsUser(user.id, next);
    } catch {
      res.status(401).json({ error: 'auth_required', message: 'Entre na sua conta para continuar.' });
    }
  };
}
export function createSocketAuth(verify = verifyAccessToken) {
  return async (socket, next) => {
    try {
      const token = socket.handshake.auth?.accessToken || socket.handshake.auth?.tadeuToken;
      const user = await verify(token);
      socket.data.userId = user.id;
      socket.data.accessToken = token;
      socket.use(async (_packet, proceed) => {
        try {
          const fresh = await verify(token);
          if (fresh.id !== user.id) throw new Error('auth_changed');
          proceed();
        } catch {
          socket.emit('erro_backend', 'Sessão expirada. Entre novamente.');
          socket.disconnect(true);
        }
      });
      // Bind EVERY handler, including callbacks registered by supporting modules.
      const on = socket.on.bind(socket);
      socket.on = (event, listener) => on(event, (...args) => {
        try {
          const result = runAsUser(user.id, () => listener(...args));
          Promise.resolve(result).catch(() => socket.emit('erro_backend', 'Não foi possível concluir a operação.'));
        } catch {
          socket.emit('erro_backend', 'Não foi possível concluir a operação.');
        }
      });
      next();
    } catch { next(new Error('auth_required')); }
  };
}
