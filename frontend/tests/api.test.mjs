import { test, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, StaleResponse, forget, request, uploadImage, writesLocked } from '../src/api.ts';

const originalFetch = globalThis.fetch;
globalThis.window = new EventTarget();
globalThis.location = new URL('https://cms.example/other-context/content/index.html#articles');
const image = { id: 'abc-123', url: '/other-context/media/images/abc-123', width: 20, height: 30, size: 6 };
const file = () => new File(['sample'], 'photo.png', { type: 'image/png' });
const ok = data => Response.json({ code: 'OK', message: 'OK', data });
beforeEach(() => { forget(); writesLocked.value = false; });
after(() => { globalThis.fetch = originalFetch; });

test('multipart keeps browser boundary, Cookie and CSRF, and uses actual context', async () => {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    return ok(calls.length === 1 ? { token: 'csrf-test' } : image);
  };
  assert.deepEqual(await uploadImage(file()), image);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].url, 'https://cms.example/other-context/api/media/images');
  assert.ok(calls[1].init.body instanceof FormData);
  assert.equal(calls[1].init.body.get('file').name, 'photo.png');
  assert.equal(calls[1].init.headers['Content-Type'], undefined);
  assert.equal(calls[1].init.headers['X-CSRF-Token'], 'csrf-test');
  assert.equal(calls[1].init.credentials, 'same-origin');
});
test('existing JSON requests still serialize normally', async () => {
  globalThis.fetch = async (url, init) => {
    if (String(url).endsWith('csrf-token')) return ok({ token: 't' });
    assert.equal(init.headers['Content-Type'], 'application/json');
    assert.equal(init.body, '{"title":"中文"}');
    return ok(null);
  };
  await request('articles', { method: 'POST', body: { title: '中文' } });
});
test('already cancelled upload never requests CSRF or sends file', async () => {
  globalThis.fetch = async () => { assert.fail('unexpected request'); };
  const controller = new AbortController(); controller.abort();
  await assert.rejects(uploadImage(file(), { signal: controller.signal }), { name: 'AbortError' });
});
test('abort during upload stays AbortError rather than network failure', async () => {
  const controller = new AbortController();
  globalThis.fetch = async url => {
    if (String(url).endsWith('csrf-token')) return ok({ token: 't' });
    controller.abort(); throw controller.signal.reason;
  };
  await assert.rejects(uploadImage(file(), { signal: controller.signal }), { name: 'AbortError' });
});
test('session changes while response body arrives reject stale result', async () => {
  globalThis.fetch = async url => String(url).endsWith('csrf-token') ? ok({ token: 't' }) : {
    ok: true, status: 200, json: async () => { forget(); return { code: 'OK', message: 'OK', data: image }; }
  };
  await assert.rejects(uploadImage(file()), StaleResponse);
});
test('stale malformed response does not surface a misleading format error', async () => {
  globalThis.fetch = async url => String(url).endsWith('csrf-token') ? ok({ token: 't' }) : {
    status: 200, json: async () => { forget(); throw new Error('interrupted body'); }
  };
  await assert.rejects(uploadImage(file()), StaleResponse);
});
test('container HTML 413 produces actionable ApiError and request ID without retry', async () => {
  let writes = 0;
  globalThis.fetch = async url => {
    if (String(url).endsWith('csrf-token')) return ok({ token: 't' });
    writes++; return new Response('<html>too large</html>', { status: 413, headers: { 'X-Request-Id': 'test-413' } });
  };
  await assert.rejects(uploadImage(file()), e => e instanceof ApiError && e.status === 413 && e.requestId === 'test-413');
  assert.equal(writes, 1);
});
test('untrusted or mismatched image URLs are rejected', async () => {
  for (const url of ['https://outside.example/photo.png', '//outside.example/a', '/wrong/media/images/abc-123', '/other-context/media/images/../a', 'data:image/png;base64,abc']) {
    forget();
    globalThis.fetch = async u => ok(String(u).endsWith('csrf-token') ? { token: 't' } : { ...image, url });
    await assert.rejects(uploadImage(file()), ApiError);
  }
});
test('suspended session blocks business writes without sending a request', async () => {
  writesLocked.value = true;
  globalThis.fetch = async () => { assert.fail('suspended write reached network'); };
  await assert.rejects(request('articles', { method: 'POST', body: { title: 'draft' } }), e => e instanceof ApiError && e.status === 401);
});
test('suspended session still permits explicit reauthentication', async () => {
  writesLocked.value = true;
  let requests = 0;
  globalThis.fetch = async url => { requests++; return ok(String(url).endsWith('csrf-token') ? {token:'t'} : null); };
  await request('auth/login', { method:'POST', body:{username:'test',password:'test-only'} });
  assert.equal(requests, 2);
});
