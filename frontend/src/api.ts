import { shallowRef } from 'vue';
import type { ImageUploadResult, User } from './models';
export const currentUser = shallowRef<User | null>(null);
export const permissionRevision = shallowRef(0);
export const can = (permission: string) => Boolean(currentUser.value?.permissionCodes.includes(permission));
export class ApiError extends Error {
  constructor(public status: number, message: string, public requestId = '') { super(message); }
}
export class StaleResponse extends Error {}
let token = '', epoch = 0;
export const forget = () => { token = ''; epoch++; currentUser.value = null; };
export function errorText(error: unknown): string {
  if (!(error instanceof Error)) return '操作未成功，请稍后重试。';
  return error.message + (error instanceof ApiError && error.status === 409 ? ' 请核对最新数据，当前输入已保留，未自动覆盖。' : '')
    + (error instanceof ApiError && error.requestId ? `（请求编号：${error.requestId}）` : '');
}
type Options = { method?: string; body?: unknown; signal?: AbortSignal; query?: Record<string, string | number | null | undefined> };
export async function request<T>(path: string, options: Options = {}): Promise<T> {
  const generation = epoch;
  options.signal?.throwIfAborted();
  const base = import.meta.env?.DEV ? new URL('/api/', location.origin) : new URL('../api/', location.href);
  const url = new URL(path, base), method = options.method || 'GET', write = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  for (const [name, value] of Object.entries(options.query || {})) if (value !== '' && value !== undefined && value !== null) url.searchParams.set(name, String(value));
  if (write && !token) await csrf(options.signal);
  options.signal?.throwIfAborted();
  if (generation !== epoch) throw new StaleResponse();
  let response: Response;
  const multipart = options.body instanceof FormData;
  try { response = await fetch(url, { method, signal: options.signal, credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json', ...(write ? { 'X-CSRF-Token': token } : {}),
    ...(options.body === undefined || multipart ? {} : { 'Content-Type': 'application/json' }) },
    ...(options.body === undefined ? {} : { body: multipart ? options.body as FormData : JSON.stringify(options.body) }) }); }
  catch (error) {
    options.signal?.throwIfAborted();
    if (error instanceof Error && error.name === 'AbortError') throw error;
    if (generation !== epoch) throw new StaleResponse();
    throw new ApiError(0, '无法连接服务，请检查网络后重试。');
  }
  options.signal?.throwIfAborted();
  if (generation !== epoch) throw new StaleResponse();
  let raw: unknown;
  try { raw = await response.json(); } catch (error) {
    options.signal?.throwIfAborted();
    if (generation !== epoch) throw new StaleResponse();
    if (error instanceof Error && error.name === 'AbortError') throw error;
    if (response.status === 401) { forget(); window.dispatchEvent(new Event('cms:unauthenticated')); }
    if (response.status === 403) { token = ''; permissionRevision.value++; }
    throw new ApiError(response.status, response.status === 413 ? '图片超过上传大小限制。' : '服务未返回有效接口响应。', response.headers.get('X-Request-Id') || '');
  }
  options.signal?.throwIfAborted();
  // Headers may arrive before logout while the response body is still streaming.
  if (generation !== epoch) throw new StaleResponse();
  if (!raw || typeof raw !== 'object' || !('code' in raw) || typeof raw.code !== 'string'
    || !('message' in raw) || typeof raw.message !== 'string' || !('data' in raw)) throw new ApiError(response.status, '服务响应格式无效。');
  const result = { code: raw.code, message: raw.message, data: raw.data,
    requestId: 'requestId' in raw && typeof raw.requestId === 'string' ? raw.requestId : '' };
  if (!response.ok || result.code !== 'OK') {
    if (response.status === 401) { forget(); window.dispatchEvent(new Event('cms:unauthenticated')); }
    if (response.status === 403) { token = ''; permissionRevision.value++; }
    throw new ApiError(response.status, result.message || '操作未成功', result.requestId);
  }
  return result.data as T;
}
async function csrf(signal?: AbortSignal) { token = (await request<{ token: string }>('auth/csrf-token', { signal })).token; }
export async function uploadImage(file: File, options: { signal?: AbortSignal } = {}): Promise<ImageUploadResult> {
  const body = new FormData(); body.append('file', file);
  const result = await request<ImageUploadResult>('media/images', { method: 'POST', body, signal: options.signal });
  // Only accept a media URL supplied by the server in this application's context.
  const prefix = import.meta.env?.DEV ? `${import.meta.env.VITE_CMS_CONTEXT || '/cms'}/media/images/` : new URL('../media/images/', location.href).pathname;
  if (!result || typeof result.id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(result.id)
    || result.url !== prefix + result.id || ![result.width, result.height, result.size].every(n => Number.isSafeInteger(n) && n > 0)
    || result.width > 8192 || result.height > 8192 || result.width * result.height > 20000000 || result.size > 5 * 1024 * 1024)
    throw new ApiError(200, '图片上传响应格式无效。');
  return result;
}
export async function restore() { currentUser.value = await request<User>('auth/me'); }
export async function login(username: string, password: string) {
  await csrf(); await request('auth/login', { method: 'POST', body: { username, password } });
  token = ''; await csrf(); await restore();
}
export async function logout() { await request('auth/logout', { method: 'POST', body: {} }); forget(); }
