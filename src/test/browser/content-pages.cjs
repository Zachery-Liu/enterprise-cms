/* Browser contract checks only. This loopback fixture never ships in the WAR. */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/webapp');
const out = path.resolve(__dirname, '../../../output/playwright');
const stamp = '2026-10-30T00:00:00Z';
const permissions = ['category:read', 'category:manage', ...['read','create','edit','delete','submit','audit','publish','offline'].map(p => `article:${p}`)].map(p => `cms:${p}`);
let signedIn = false, role = 'all', token = 'fixture-before', conflict = false, deny = false, expire = false;
let bulkRows = null;
const requests = [];
const article = { id: 101, title: '<img src=x onerror=alert(1)> 测试文章', summary: '仅用于浏览器交互验证', content: '<p>安全正文</p>', categoryId: 1,
  categoryName: '新闻', authorId: 7, authorDisplayName: '测试编辑', status: 'DRAFT', version: 0, createdAt: stamp, updatedAt: stamp, publishedAt: null };
const tree = [{ id: 1, name: '新闻', parentId: null, sortOrder: 0, version: 0, status: 'ENABLED', children: [] },
  { id: 2, name: '停用分类', parentId: null, sortOrder: 1, version: 0, status: 'DISABLED', children: [
    { id: 3, name: '受停用祖先影响', parentId: 2, sortOrder: 0, version: 0, status: 'ENABLED', children: [] }] }];
function user() { return { userId: 7, username: 'fixture-user', displayName: '测试编辑', permissionCodes: role === 'all' ? permissions : ['cms:article:read'], roleCodes: [] }; }
function categoryEntry(id, entries = tree) {
  for (const node of entries) {
    if (node.id === id) return { node, entries };
    const found = categoryEntry(id, node.children); if (found) return found;
  }
  return null;
}
function reply(res, status, data, message = '成功') { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ code: status === 200 ? 'OK' : 'FIXTURE_ERROR', message, data, requestId: 'browser-fixture' })); }
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/favicon.ico') { res.writeHead(204); res.end(); return; }
  if (!url.pathname.startsWith('/cms/api/')) {
    const file = path.resolve(root, '.' + decodeURIComponent(url.pathname.replace(/^\/cms/, '')));
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'text/javascript; charset=utf-8' : file.endsWith('.css') ? 'text/css' : 'text/html; charset=utf-8' });
    fs.createReadStream(file).pipe(res); return;
  }
  const endpoint = url.pathname.slice('/cms/api/'.length), method = req.method;
  const chunks = []; for await (const chunk of req) chunks.push(chunk);
  let body; try { body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : null; } catch { reply(res, 400, null); return; }
  requests.push({ endpoint, method, token: req.headers['x-csrf-token'], query: Object.fromEntries(url.searchParams), body: endpoint === 'auth/login' ? null : body });
  if (endpoint === 'auth/csrf-token') { reply(res, 200, { token }); return; }
  if (endpoint === 'auth/login') {
    if (req.headers['x-csrf-token'] !== token) { reply(res, 403, null); return; }
    signedIn = true; token = 'fixture-after'; reply(res, 200, { userId: 7, username: 'fixture-user', displayName: '测试编辑', status: 'ACTIVE' }); return;
  }
  if (endpoint === 'auth/logout') { signedIn = false; token = 'fixture-before'; reply(res, 200, null); return; }
  if (endpoint.startsWith('public/')) {
    if (endpoint === 'public/articles') { reply(res, 200, { records: article.status === 'PUBLISHED' ? [article] : [], total: article.status === 'PUBLISHED' ? 1 : 0, page: 1, size: 10 }); return; }
    reply(res, article.status === 'PUBLISHED' ? 200 : 404, article.status === 'PUBLISHED' ? article : null); return;
  }
  if (!signedIn || expire) { expire = false; signedIn = false; reply(res, 401, null, '登录已失效'); return; }
  if (endpoint === 'auth/me') { reply(res, 200, user()); return; }
  if (method !== 'GET') {
    if (deny || req.headers['x-csrf-token'] !== token) { deny = false; role = 'reader'; reply(res, 403, null, '权限已撤销'); return; }
    if (conflict) { conflict = false; reply(res, 409, null, '版本冲突'); return; }
  }
  if (endpoint === 'categories/tree') { reply(res, 200, tree); return; }
  if (endpoint.startsWith('categories')) {
    if (method === 'POST') {
      const node = { id: 4, version: 0, status: 'ENABLED', children: [], ...body };
      (node.parentId ? categoryEntry(node.parentId).node.children : tree).push(node);
      reply(res, 200, node); return;
    }
    const found = categoryEntry(Number(endpoint.split('/')[1]));
    if (!found) { reply(res, 404, null); return; }
    if (method === 'DELETE') { found.entries.splice(found.entries.indexOf(found.node), 1); reply(res, 200, null); return; }
    if (endpoint.endsWith('/parent')) {
      found.entries.splice(found.entries.indexOf(found.node), 1);
      (body.parentId ? categoryEntry(body.parentId).node.children : tree).push(found.node);
    }
    Object.assign(found.node, body, { version: found.node.version + 1 }); reply(res, 200, found.node); return;
  }
  if (endpoint === 'articles/101/history') { reply(res, 200, { records: [{ id: 1, articleId: 101, action: 'CREATE', fromStatus: null, toStatus: 'DRAFT', operatorDisplayName: '测试编辑', createdAt: stamp }], total: 1, page: 1, size: 20 }); return; }
  if (endpoint === 'articles' && method === 'POST') { Object.assign(article, body, { version: 0, status: 'DRAFT' }); reply(res, 200, article); return; }
  if (endpoint === 'articles' || endpoint === 'articles/review-queue') {
    const rows = (bulkRows || [article]).filter(row => (endpoint !== 'articles/review-queue' || row.status === 'PENDING')
      && (!url.searchParams.get('title') || row.title.includes(url.searchParams.get('title'))) && (!url.searchParams.get('status') || row.status === url.searchParams.get('status')));
    const page = Number(url.searchParams.get('page') || 1), size = Number(url.searchParams.get('size') || 20);
    reply(res, 200, { records: rows.slice((page - 1) * size, page * size).map(row => ({ ...row, submittedAt: stamp })), total: rows.length, page, size }); return;
  }
  if (endpoint === 'articles/101' && method === 'GET') { reply(res, 200, article); return; }
  if (endpoint === 'articles/101' && method === 'PUT') { Object.assign(article, body, { version: article.version + 1 }); reply(res, 200, article); return; }
  if (endpoint === 'articles/101' && method === 'DELETE') { reply(res, 200, null); return; }
  if (endpoint === 'articles/batch-publish') {
    const items = body.items.map(item => (bulkRows || [article]).find(row => row.id === item.id));
    for (const row of items) { row.status = 'PUBLISHED'; row.version++; row.publishedAt = stamp; }
    reply(res, 200, { count: items.length, items }); return;
  }
  const action = endpoint.split('/')[2], state = { submit: 'PENDING', approve: 'APPROVED', reject: 'REJECTED', publish: 'PUBLISHED', offline: 'OFFLINE', reopen: 'DRAFT' }[action];
  if (state) { article.status = state; article.version++; if (state === 'PUBLISHED') article.publishedAt = stamp; reply(res, 200, article); return; }
  reply(res, 404, null, '接口不存在');
});

async function verify(base) {
  const { chromium } = require(process.env.CMS_PLAYWRIGHT_MODULE || 'playwright');
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ headless: true, ...(process.env.CMS_CHROMIUM_EXECUTABLE ? { executablePath: process.env.CMS_CHROMIUM_EXECUTABLE } : {}) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const checks = [];
  const check = label => { checks.push(label); process.stdout.write(`PASS ${label}\n`); };
  const visible = selector => page.locator(selector).waitFor({ state: 'visible' });
  const click = name => page.getByRole('button', { name, exact: true }).click();
  const menu = name => page.getByRole('menuitem', { name, exact: true }).click();
  const closeDialog = () => page.locator('.el-dialog:visible .el-dialog__headerbtn').click();
  const tableReady = () => visible('.el-table__body-wrapper tbody tr');
  try {
    await page.goto(`${base}/cms/content/index.html`); await visible('.login-card'); check('Element Plus 登录表单，匿名无后台数据');
    await page.locator('input[name=username]').fill('fixture-user'); await page.locator('input[name=password]').fill('fixture-password'); await click('登录');
    await tableReady(); assert.equal(await page.locator('.el-table__body-wrapper img').count(), 0); check('登录后重取 CSRF，表格标题安全转义');
    await click('编辑'); await visible('textarea[name=content]');
    await page.locator('.el-dialog:visible .el-select').click(); await visible('.el-select-dropdown:visible');
    const options = page.locator('.el-select-dropdown:visible [role=option]'); assert.equal(await options.count(), 1);
    await options.first().click(); check('ElSelect 排除停用分类及停用祖先后代');
    await page.locator('input[name=articleTitle]').fill('保留的编辑内容'); conflict = true; await click('保存修改');
    await page.getByText(/当前输入已保留/).waitFor(); assert.equal(await page.locator('input[name=articleTitle]').inputValue(), '保留的编辑内容');
    assert.equal(requests.filter(r => r.endpoint === 'articles/101' && r.method === 'PUT').length, 1); check('409 保留编辑输入，不自动重试');
    await page.locator('textarea[name=content]').fill('<p>预览内容</p><script>parent.document.body.dataset.injected="yes"</script><img src="https://invalid.example/x">');
    await page.getByRole('tab', { name: '正文预览', exact: true }).click(); await visible('.el-dialog iframe');
    assert.equal(await page.locator('.el-dialog iframe').getAttribute('sandbox'), ''); assert.equal(await page.locator('body').getAttribute('data-injected'), null); check('预览使用 sandbox 与 CSP 隔离');
    await closeDialog(); await page.locator('input[name=filterTitle]').fill('不存在的标题'); await click('查询');
    await page.getByText('没有符合条件的文章', { exact: true }).waitFor(); await click('重置'); await tableReady(); check('组件筛选空态与重置');
    await menu('分类管理'); await click('新增分类'); await visible('input[name=categoryName]');
    await page.locator('input[name=categoryName]').fill('组件库测试分类'); await click('确认保存'); await page.locator('.el-dialog').waitFor({ state: 'hidden' });
    assert.equal(requests.find(r => r.endpoint === 'categories' && r.method === 'POST').token, 'fixture-after');
    assert.ok(await page.locator('.el-tree').count()); check('ElTree 分类维护与登录后 CSRF 写入');
    await menu('文章管理'); await tableReady(); await click('提交审核'); await click('确认提交审核');
    await menu('审核队列'); await page.getByRole('button', { name: '退回', exact: true }).waitFor();
    await click('退回'); await page.locator('textarea[name=comment]').fill('   '); await click('确认退回');
    await page.getByText('请填写退回原因。', { exact: true }).waitFor(); assert.equal(requests.filter(r => r.endpoint.endsWith('/reject')).length, 0); check('审核退回拒绝空白原因');
    await closeDialog(); await click('通过'); await click('确认通过'); await page.getByText('没有符合条件的文章', { exact: true }).waitFor();
    await menu('文章管理'); await page.getByRole('button', { name: '发布', exact: true }).waitFor();
    await page.locator('.el-table__body-wrapper .el-checkbox').click(); await click('批量发布（1/50）'); await click('确认整批发布');
    await page.getByRole('button', { name: '下架', exact: true }).waitFor();
    assert.equal(await page.locator('.el-table__body-wrapper input[type=checkbox]').isChecked(), false);
    assert.equal(requests.find(r => r.endpoint === 'articles/batch-publish').body.items[0].version, 2); check('组件表格选择及批量当前版本提交');
    await page.screenshot({ path: path.join(out, 'content-workbench.png'), fullPage: true });
    await click('历史'); await page.getByRole('cell', { name: 'CREATE', exact: true }).waitFor(); await closeDialog(); check('ElDialog 状态历史与分页');
    const publicPage = await browser.newPage(); publicPage.on('pageerror', error => errors.push(error.message));
    await publicPage.goto(`${base}/cms/content/public.html`); await publicPage.locator('.reading-item .el-link').click(); await publicPage.locator('iframe').waitFor(); check('公开列表到安全正文详情');
    await click('下架'); await click('确认下架'); await page.getByRole('button', { name: '重开编辑', exact: true }).waitFor();
    await publicPage.reload(); await publicPage.getByText('文章不存在，或已停止公开。', { exact: true }).waitFor(); assert.equal(await publicPage.locator('iframe').count(), 0); await publicPage.close(); check('公开 404 清除下架正文');
    await click('重开编辑'); await click('确认重开编辑'); await page.getByRole('button', { name: '编辑', exact: true }).waitFor();
    await click('编辑'); await visible('textarea[name=content]'); deny = true; const before = requests.filter(r => r.method === 'PUT').length;
    await click('保存修改'); await page.getByText('请求被拒绝', { exact: true }).waitFor(); assert.equal(requests.filter(r => r.method === 'PUT').length, before + 1);
    await click('重新加载'); await tableReady(); assert.equal(await page.getByRole('button', { name: '编辑', exact: true }).count(), 0);
    await menu('审核队列'); await page.getByText('没有此页面的读取权限', { exact: true }).waitFor(); check('403 后重新读取权限，禁止写重试及越权按钮');
    expire = true; await menu('文章管理'); await visible('.login-card');
    assert.equal(await page.locator('.el-table').count(), 0); assert.equal(await page.locator('textarea[name=content]').count(), 0); check('401 卸载后台表格与编辑内容');
    await page.setViewportSize({ width: 390, height: 844 }); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.screenshot({ path: path.join(out, 'content-mobile-login.png'), fullPage: true }); check('390px 组件布局无横向溢出');
    await verifyBoundaries(page, base, check);
    assert.equal(errors.length, 0, errors.join('\n')); check('无未捕获脚本异常');
    assert.ok(requests.filter(r => !r.endpoint.startsWith('auth/') && r.method !== 'GET').every(r => r.token === 'fixture-after'));
    fs.writeFileSync(path.join(out, 'content-browser-results.json'), JSON.stringify({ fixtureOnly: true, ui: 'Vue 3 + Element Plus', checks, uncaughtErrors: errors, passed: checks.length }, null, 2));
  } finally { await browser.close(); }
}
async function verifyBoundaries(page, base, check) {
  const click = name => page.getByRole('button', { name, exact: true }).click();
  const menu = name => page.getByRole('menuitem', { name, exact: true }).click();
  const selectCategory = name => page.locator('.el-tree').getByText(name, { exact: true }).click();
  const waitCategorySaved = () => page.getByText('请从左侧选择分类', { exact: true }).waitFor();
  const lastRequest = (endpoint, method) => requests.filter(item => item.endpoint === endpoint && item.method === method).at(-1);
  role = 'all'; await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${base}/cms/content/index.html`);
  await page.locator('input[name=username]').fill('fixture-user'); await page.locator('input[name=password]').fill('fixture-password'); await click('登录');
  await page.locator('.el-table__body-wrapper tbody tr').waitFor();
  const initial = requests.find(item => item.endpoint === 'articles' && item.method === 'GET').query;
  assert.equal(initial.categoryId, undefined); assert.equal(initial.authorId, undefined); check('空筛选不隐式发送分类或作者 ID');

  await menu('分类管理'); await selectCategory('组件库测试分类'); await click('更名');
  await page.locator('input[name=categoryName]').fill('边界维护分类'); conflict = true; const renameBefore = requests.filter(item => item.endpoint === 'categories/4' && item.method === 'PUT').length;
  await click('确认保存'); await page.getByText(/当前输入已保留/).waitFor();
  assert.equal(await page.locator('input[name=categoryName]').inputValue(), '边界维护分类');
  assert.equal(requests.filter(item => item.endpoint === 'categories/4' && item.method === 'PUT').length, renameBefore + 1);
  await click('确认保存'); await waitCategorySaved();
  assert.deepEqual(lastRequest('categories/4', 'PUT').body, { version: 0, name: '边界维护分类' }); check('分类更名冲突保留输入，手动重试携带原版本');

  await selectCategory('边界维护分类'); await click('移动'); await page.locator('.el-dialog:visible .el-select').click();
  const parents = page.locator('.el-select-dropdown:visible [role=option]'); await parents.first().waitFor(); assert.equal(await parents.count(), 1);
  await parents.getByText('新闻', { exact: true }).click(); await click('确认保存'); await waitCategorySaved();
  assert.deepEqual(lastRequest('categories/4/parent', 'PATCH').body, { version: 1, parentId: 1 });
  await page.locator('.el-tree > .el-tree-node').filter({ hasText: '新闻' }).locator(':scope > .el-tree-node__content .el-tree-node__expand-icon').click();
  await selectCategory('边界维护分类'); await click('移动');
  await page.locator('.el-dialog:visible .el-select').hover(); await page.locator('.el-dialog:visible .el-select__clear').click();
  await click('确认保存'); await waitCategorySaved();
  assert.deepEqual(lastRequest('categories/4/parent', 'PATCH').body, { version: 2, parentId: null }); check('分类移动排除自身，移回根节点明确发送 null');

  await selectCategory('边界维护分类'); await click('排序'); await page.locator('.el-dialog:visible input').fill('17'); await page.locator('.el-dialog:visible input').press('Tab');
  await click('确认保存'); await waitCategorySaved(); assert.deepEqual(lastRequest('categories/4/sort', 'PATCH').body, { version: 3, sortOrder: 17 });
  await selectCategory('边界维护分类'); await click('停用'); await click('确认保存'); await waitCategorySaved();
  assert.deepEqual(lastRequest('categories/4/status', 'PATCH').body, { version: 4, status: 'DISABLED' });
  await selectCategory('边界维护分类'); await click('启用'); await click('确认保存'); await waitCategorySaved();
  assert.deepEqual(lastRequest('categories/4/status', 'PATCH').body, { version: 5, status: 'ENABLED' }); check('分类排序和启停使用逐次更新后的版本');
  await selectCategory('边界维护分类'); await click('删除'); await click('确认保存'); await waitCategorySaved();
  assert.equal(lastRequest('categories/4', 'DELETE').query.version, '6'); assert.equal(await page.locator('.el-tree').getByText('边界维护分类', { exact: true }).count(), 0); check('分类删除携带版本，成功后刷新树');

  await menu('文章管理'); await page.getByRole('button', { name: '编辑', exact: true }).waitFor(); await click('编辑');
  await page.locator('input[name=articleTitle]').fill('   '); const writesBefore = requests.filter(item => item.endpoint === 'articles/101' && item.method === 'PUT').length;
  await click('保存修改'); await page.getByText('请输入标题', { exact: true }).waitFor();
  assert.equal(requests.filter(item => item.endpoint === 'articles/101' && item.method === 'PUT').length, writesBefore); check('文章必填校验失败不发写请求');
  await page.locator('input[name=articleTitle]').fill('有效草稿');
  await page.getByRole('button', { name: '保存修改', exact: true }).evaluate(control => { control.click(); control.click(); });
  await page.locator('.el-dialog').waitFor({ state: 'hidden' });
  await page.locator('.el-table__body-wrapper').getByText('有效草稿', { exact: true }).waitFor();
  assert.equal(requests.filter(item => item.endpoint === 'articles/101' && item.method === 'PUT').length, writesBefore + 1);
  assert.deepEqual(Object.keys(lastRequest('articles/101', 'PUT').body).sort(), ['categoryId', 'content', 'summary', 'title', 'version']); check('快速重复保存只提交一次，不发送状态或身份字段');

  bulkRows = Array.from({ length: 81 }, (_, index) => ({ ...article, id: 1000 + index, title: `边界文章${String(index + 1).padStart(2, '0')}`, status: 'APPROVED', version: index % 7 }));
  await click('刷新'); await page.locator('.el-table__body-wrapper').getByText('边界文章01', { exact: true }).waitFor();
  assert.equal(await page.locator('.el-table__body-wrapper tbody tr').count(), 20);
  await click('下一页'); await page.locator('.el-table__body-wrapper').getByText('边界文章21', { exact: true }).waitFor();
  assert.equal(lastRequest('articles', 'GET').query.page, '2'); assert.equal(lastRequest('articles', 'GET').query.size, '20'); check('分页发送页码与页大小，第二页展示对应记录');
  await page.locator('.page-pagination .el-select').click();
  await page.locator('.el-select-dropdown:visible [role=option]').filter({ hasText: '100条/页' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 81);
  assert.equal(lastRequest('articles', 'GET').query.page, '1'); assert.equal(lastRequest('articles', 'GET').query.size, '100'); check('变更页大小回到第一页，保留数据库总数');
  await page.locator('.el-table__header-wrapper .el-checkbox').click();
  await page.getByRole('button', { name: '批量发布（50/50）', exact: true }).waitFor();
  assert.equal(await page.locator('.el-table__body-wrapper input[type=checkbox]:checked').count(), 50);
  await click('批量发布（50/50）');
  assert.equal(await page.locator('.el-dialog .el-table__body-wrapper tbody tr').count(), 50);
  await click('确认整批发布'); await page.locator('.el-dialog').waitFor({ state: 'hidden' });
  const sent = lastRequest('articles/batch-publish', 'POST').body.items;
  assert.equal(sent.length, 50); assert.equal(new Set(sent.map(item => item.id)).size, 50);
  assert.ok(sent.every(item => item.version === (item.id - 1000) % 7));
  await page.waitForFunction(() => document.querySelectorAll('.el-table__body-wrapper input[type=checkbox]:checked').length === 0); check('全选 81 条限制为 50 条，逐项版本正确且提交后清空选择');

  bulkRows = null; await click('刷新'); await page.getByRole('button', { name: '编辑', exact: true }).waitFor(); await click('编辑');
  await page.locator('textarea[name=content]').waitFor(); deny = true;
  // Delay body consumption deterministically in the test browser. The real fixture response
  // and fetch headers are retained; no sleep or production hook is required.
  await page.evaluate(() => {
    const nativeFetch = window.fetch; let once = true;
    window.fetch = async (...args) => {
      const response = await nativeFetch(...args);
      if (once && response.url.endsWith('/api/auth/me') && response.ok) {
        once = false; const parse = response.json.bind(response);
        response.json = async () => {
          window.identityBodyPending = true;
          await new Promise(resolve => { window.releaseIdentityBody = resolve; });
          const value = await parse(); window.identityBodyRead = true; return value;
        };
      }
      return response;
    };
    window.restoreNativeFetch = () => { window.fetch = nativeFetch; };
  });
  await click('保存修改'); await page.waitForFunction(() => window.identityBodyPending === true);
  await page.getByText('请求被拒绝', { exact: true }).waitFor();
  const readsBeforeLogout = requests.filter(item => item.endpoint === 'articles' && item.method === 'GET').length;
  await click('退出登录'); await page.locator('.login-card').waitFor();
  await page.evaluate(() => { window.releaseIdentityBody(); });
  await page.waitForFunction(() => window.identityBodyRead === true);
  await page.evaluate(async () => { await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame); });
  assert.equal(await page.locator('.login-card').isVisible(), true); assert.equal(await page.locator('.el-table').count(), 0);
  assert.equal(requests.filter(item => item.endpoint === 'articles' && item.method === 'GET').length, readsBeforeLogout); check('退出后迟到的身份响应体不能恢复旧用户或触发后台查询');
  await page.evaluate(() => { window.restoreNativeFetch(); });
}
server.listen(0, '127.0.0.1', async () => {
  const base = `http://127.0.0.1:${server.address().port}`;
  process.stdout.write(`Fixture only: ${base}/cms/content/index.html\n`);
  if (process.argv.includes('--serve')) return;
  try { await verify(base); } catch (error) { process.stderr.write(error.stack + '\n'); process.exitCode = 1; }
  finally { server.close(); }
});
