import { AsyncLocalStorage } from 'node:async_hooks';
const users = new AsyncLocalStorage();
export function runAsUser(userId, operation) {
  if (typeof userId !== 'string' || !userId.trim()) throw new Error('authenticated_user_required');
  return users.run(userId, operation);
}
export function currentUserId() {
  const id = users.getStore();
  if (!id) throw new Error('authenticated_user_required');
  return id;
}
