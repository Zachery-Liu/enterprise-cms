/* Browser contract checks only. This loopback fixture never ships in the WAR. */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/webapp');
const out = path.resolve(__dirname, '../../../output/playwright');
const stamp = '2026-10-30T00:00:00Z';
const permissions = ['category:read', 'category:manage', ...['read','create','edit','delete','submit','audit','publish','offline'].map(p => `article:${p}`)].map(p => `cms:${p}`);
let uploadFails = true;
let signedIn = false, role = 'all', token = 'fixture-before', conflict = false, deny = false, expire = false;
let bulkRows = null;
const requests = [];
const article = { id: 101, title: '让每一篇文章，都拥有舒适的阅读体验', summary: '从清晰的标题到舒适的正文，用简洁的设计呈现值得阅读的内容。', content: '<h2>清晰的层次</h2><p>正文包含<strong>重点信息</strong>和<em>斜体</em>。</p><ol><li>第一项<ul><li>嵌套说明</li></ul></li><li>第二项</li></ol><blockquote><p>好的排版帮助读者理解内容。</p></blockquote><pre><code>const title = &quot;内容中心&quot;;</code></pre>', categoryId: 1,
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
  if (url.pathname === '/cms/media/images/fixture-image') { res.writeHead(200, {'Content-Type':'image/png'}); res.end(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1EAAAAASUVORK5CYII=', 'base64')); return; }
  if (!url.pathname.startsWith('/cms/api/')) {
    const file = path.resolve(root, '.' + decodeURIComponent(url.pathname.replace(/^\/cms/, '')));
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'text/javascript; charset=utf-8' : file.endsWith('.css') ? 'text/css' : 'text/html; charset=utf-8' });
    fs.createReadStream(file).pipe(res); return;
  }
  const endpoint = url.pathname.slice('/cms/api/'.length), method = req.method;
  const chunks = []; for await (const chunk of req) chunks.push(chunk);
  if (endpoint === 'media/images') {
    if (!signedIn || req.headers['x-csrf-token'] !== token || !req.headers['content-type']?.startsWith('multipart/form-data; boundary=')) { reply(res, 403, null, '上传未授权或格式错误'); return; }
    if (uploadFails) { uploadFails = false; reply(res, 503, null, '测试：首次上传失败，请重试'); return; }
    reply(res, 200, { id:'fixture-image', url:'/cms/media/images/fixture-image', width:1, height:1, size:68 }); return;
  }
  let body; try { body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : null; } catch { reply(res, 400, null); return; }
  requests.push({ endpoint, method, token: req.headers['x-csrf-token'], query: Object.fromEntries(url.searchParams), body: endpoint === 'auth/login' ? null : body });
  if (endpoint === 'auth/csrf-token') { reply(res, 200, { token }); return; }
  if (endpoint === 'auth/login') {
    if (req.headers['x-csrf-token'] !== token) { reply(res, 403, null); return; }
    signedIn = true; token = 'fixture-after'; reply(res, 200, { userId: 7, username: 'fixture-user', displayName: '测试编辑', status: 'ACTIVE' }); return;
  }
  if (endpoint === 'auth/logout') { signedIn = false; token = 'fixture-before'; reply(res, 200, null); return; }
  if (endpoint.startsWith('public/')) {
    const publicRecords = [
      {...article, title:'企业信息发布平台开放访问', summary:'访客可以在企业门户查看公开资讯、按栏目浏览内容并搜索已发布文章。', categoryName:'企业动态', status:'PUBLISHED', publishedAt: stamp},
      {...article, id:201, title:'公开资讯栏目上线', summary:'平台按栏目归集资讯，方便访客找到需要的信息。', categoryId:2, categoryName:'通知公告', publishedAt:'2026-10-28T02:00:00Z'},
      {...article, id:202, title:'数字化内容管理专题', summary:'介绍内容采编、审核发布与对外展示的常见做法。', categoryId:3, categoryName:'行业资讯', publishedAt:'2026-10-26T02:00:00Z'},
      {...article, id:203, title:'公开信息查询功能更新', summary:'资讯页支持标题搜索和栏目筛选。', categoryId:1, categoryName:'企业动态', publishedAt:'2026-10-24T02:00:00Z'}
    ];
    if (endpoint === 'public/categories') { reply(res, 200, [{id:1,name:'企业动态'},{id:2,name:'通知公告'},{id:3,name:'行业资讯'}]); return; }
    if (endpoint === 'public/articles') {
      const rows = publicRecords.filter(row => (!url.searchParams.get('title') || row.title.includes(url.searchParams.get('title'))) && (!url.searchParams.get('categoryId') || row.categoryId === Number(url.searchParams.get('categoryId'))));
      const page = Number(url.searchParams.get('page') || 1), size = Number(url.searchParams.get('size') || 10);
      reply(res, 200, {records:rows.slice((page-1)*size,page*size),total:rows.length,page,size}); return;
    }
    const found = publicRecords.find(row => String(row.id) === endpoint.split('/')[2]);
    reply(res, found ? 200 : 404, found || null); return;
  }
  if (!signedIn || expire) { expire = false; signedIn = false; reply(res, 401, null, '登录已失效'); return; }
  if (endpoint === 'auth/me') { reply(res, 200, user()); return; }
  if (method !== 'GET') {
    if (deny || req.headers['x-csrf-token'] !== token) { deny = false; role = 'reader'; reply(res, 403, null, '权限已撤销'); return; }
    if (body?.title?.includes('冲突') && !conflict) { conflict = true; reply(res, 409, null, '测试：版本冲突'); return; }
    if (conflict && !body?.title?.includes('冲突')) { conflict = false; }
    if (false) { conflict = false; reply(res, 409, null, '版本冲突'); return; }
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

server.listen(0, '127.0.0.1', () => console.log('B fixture: http://127.0.0.1:' + server.address().port + '/cms/content/index.html'));
