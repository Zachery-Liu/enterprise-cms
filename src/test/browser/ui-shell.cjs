// Loopback-only browser fixture for the UI A shell; never packaged in the WAR.
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { chromium } = require(process.env.CMS_PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../../main/webapp'), out = path.resolve(__dirname, '../../../output/playwright/ui-a');
let signedIn = false, account = 'editor', expireNext = false, logoutFailure = false, identityDelay = 0;
const reads = [], checks = [], errors = [];
const permissions = ['read','create','edit','audit','publish'].map(p => `cms:article:${p}`).concat('cms:category:read','cms:category:manage');
const user = () => ({ userId: account === 'other' ? 8 : 7, username: account, displayName: account === 'other' ? '另一账号' : '内容编辑', roleCodes: [], permissionCodes: permissions });
const tree = [{ id: 1, parentId: null, name: '产品动态', status: 'ENABLED', sortOrder: 0, version: 0, children: [] }];
const record = { id: 1, title: '一段很长的文章标题，用于检查表格在窄屏上的表现与内容边界', summary: '示例摘要', content: '<p>示例正文</p>', categoryId: 1, categoryName: '产品动态', authorId: 7, authorDisplayName: '内容编辑', status: 'DRAFT', version: 0, createdAt: '2026-11-07T00:00:00Z', updatedAt: '2026-11-07T00:00:00Z', publishedAt: '2026-11-07T00:00:00Z' };
const pageData = () => ({ records: [record], total: 1, page: 1, size: 20 });
function reply(res, status, data, message='测试响应') { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ code: status < 300 ? 'OK' : 'TEST_ERROR', message, data, requestId: 'ui-shell-fixture' })); }
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost'), match = /^\/(cms|alternate)\/api\/(.*)$/.exec(url.pathname);
  if (!match) {
    const file = path.resolve(root, '.' + decodeURIComponent(url.pathname.replace(/^\/(cms|alternate)/, '')));
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html' }); fs.createReadStream(file).pipe(res); return;
  }
  const endpoint = match[2]; reads.push(endpoint);
  const chunks=[]; for await (const chunk of req) chunks.push(chunk);
  const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : {};
  if (endpoint === 'auth/csrf-token') return reply(res,200,{token:'test-token'});
  if (endpoint === 'auth/login') { signedIn=true; account=body.username; return reply(res,200,null); }
  if (endpoint === 'auth/logout') {
    if (logoutFailure) return reply(res,500,null,'测试退出失败');
    signedIn=false; return reply(res,200,null);
  }
  if (endpoint.startsWith('public/')) return reply(res,200,endpoint === 'public/articles' ? pageData() : record);
  if (expireNext) { signedIn=false; expireNext=false; }
  if (!signedIn) return reply(res,401,null,'请登录');
  if (endpoint === 'auth/me') { if(identityDelay) await new Promise(r=>setTimeout(r,identityDelay)); return reply(res,200,user()); }
  if (endpoint === 'categories/tree') return reply(res,200,tree);
  if (endpoint === 'articles' && req.method === 'POST') return reply(res,409,null,'版本冲突');
  if (endpoint === 'articles' || endpoint === 'articles/review-queue') return reply(res,200,pageData());
  if (endpoint === 'articles/1') return reply(res,200,record);
  reply(res,404,null);
});
async function verify(base) {
  const browser = await chromium.launch({ headless:true, executablePath:process.env.CMS_CHROMIUM_EXECUTABLE });
  const context = await browser.newContext({ viewport:{width:1440,height:960} });
  const p = await context.newPage(); p.on('pageerror', e=>errors.push(e.message));
  const check = name => { checks.push(name); console.log('PASS '+name); };
  const login = async (name='editor') => { await p.locator('[name=username]').fill(name); await p.locator('[name=password]').fill('test-only-password'); await p.getByRole('button',{name:'登录工作台',exact:true}).click(); await p.locator('.app-shell').waitFor({state:'visible'}); };
  const shot = name => p.screenshot({path:path.join(out,name+'.png'),fullPage:true});
  const cancelLeave = async action => { const handled = p.waitForEvent('dialog').then(d=>d.dismiss()); await action(); await handled; };
  const noOverflow = async () => assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth <= innerWidth + 1));
  try {
    await p.goto(base+'/cms/content/public.html#public'); await p.locator('.reading-card').waitFor();
    assert.equal(reads.some(x=>x.startsWith('auth/')),false); assert.equal(await p.getByRole('button',{name:'退出登录'}).count(),0); check('公开入口不恢复后台会话或显示退出'); await shot('public-desktop');
    await p.goto(base+'/cms/content/index.html?title=中文&page=2#public/1'); await p.waitForURL('**/public.html?title=*&page=2#public/1'); check('旧公开链接保留查询参数并跳转独立入口');
    await p.goto(base+'/alternate/content/index.html#review'); await p.locator('.login-card').waitFor();
    assert.equal(await p.locator('.app-aside').count(),0); assert.ok(!(await p.locator('body').innerText()).includes('登录已失效')); await shot('login-desktop');
    await login(); assert.ok(p.url().endsWith('#review')); check('匿名直接后台只显示登录并恢复审核目标，支持另一上下文');
    await p.locator('.app-aside').getByRole('menuitem',{name:'分类管理'}).click(); await p.locator('.category-layout').waitFor();
    await p.locator('.app-aside').getByRole('menuitem',{name:'文章管理'}).click(); await p.getByRole('button',{name:'新建文章',exact:true}).waitFor(); await shot('admin-desktop');
    await p.getByRole('button',{name:'新建文章',exact:true}).click(); await p.locator('[name=articleTitle]').fill('需要保留的草稿'); await p.locator('[name=content]').fill('<p>未保存正文</p>');
    await cancelLeave(()=>p.locator('.app-aside').getByRole('menuitem',{name:'分类管理'}).dispatchEvent('click'));
    assert.ok(p.url().endsWith('#articles')); assert.equal(await p.locator('[name=articleTitle]').inputValue(),'需要保留的草稿');
    await p.waitForFunction(()=>document.querySelector('.app-aside .is-active')?.textContent === '文章管理'); check('菜单取消离开保留 URL、高亮和草稿');
    await cancelLeave(()=>p.evaluate(()=>history.back())); await p.waitForTimeout(150);
    assert.ok(p.url().endsWith('#articles')); assert.equal(await p.locator('[name=articleTitle]').inputValue(),'需要保留的草稿'); check('浏览器返回取消保留 URL 和草稿');
    const before = reads.filter(x=>x==='auth/logout').length;
    await cancelLeave(()=>p.getByRole('button',{name:'退出登录'}).dispatchEvent('click')); assert.equal(reads.filter(x=>x==='auth/logout').length,before); check('取消退出不发送退出请求');
    await p.locator('.el-dialog').getByRole('combobox').click(); await p.getByRole('option',{name:'产品动态',exact:true}).click();
    await p.getByRole('button',{name:'保存草稿',exact:true}).click(); await p.getByText(/版本冲突.*当前输入已保留/).waitFor(); assert.equal(await p.locator('[name=articleTitle]').inputValue(),'需要保留的草稿'); check('409 保存失败保留输入');
    expireNext=true; await p.getByRole('button',{name:'保存草稿',exact:true}).click(); await p.locator('.login-card').waitFor();
    assert.equal(await p.locator('[name=articleTitle]').isVisible(),false); await login();
    await p.locator('[name=articleTitle]').waitFor({state:'visible'}); assert.equal(await p.locator('[name=articleTitle]').inputValue(),'需要保留的草稿'); check('会话过期隐藏锁定草稿，同账号重登恢复');
    expireNext=true; await p.getByRole('button',{name:'保存草稿',exact:true}).click(); await p.locator('.login-card').waitFor(); await login('other');
    assert.equal(await p.locator('[name=articleTitle]').count(),0); check('切换账号销毁旧草稿');
    logoutFailure=true; await p.getByRole('button',{name:'退出登录'}).click(); await p.getByText(/退出未完成/).waitFor(); assert.equal(await p.locator('.app-shell').isVisible(),true); check('退出失败明确提示且保留后台'); logoutFailure=false;
    for(const width of [390,768,1280,1440]) { await p.setViewportSize({width,height:900}); await noOverflow(); }
    await p.setViewportSize({width:390,height:844}); await p.reload(); await p.locator('.app-shell').waitFor();
    assert.equal(await p.locator('.app-aside').count(),0); await p.getByRole('button',{name:'展开高级筛选'}).waitFor(); await noOverflow(); await shot('admin-mobile');
    await p.getByRole('button',{name:'打开导航菜单'}).click(); await p.getByRole('dialog',{name:'导航菜单'}).getByRole('menuitem',{name:'分类管理'}).click(); await p.locator('.category-layout').waitFor(); await noOverflow(); check('四种宽度不横向溢出，手机抽屉可导航');
    await p.getByRole('button',{name:'退出登录'}).click(); await p.locator('.login-card').waitFor(); await noOverflow(); await shot('login-mobile');
    signedIn=true; identityDelay=500; await p.goto(base+'/cms/content/index.html#articles'); await p.getByRole('status').waitFor(); assert.equal(await p.locator('.login-card').count(),0); await p.locator('.app-shell').waitFor(); check('会话恢复先显示加载，不闪现登录页');
    assert.deepEqual(errors,[]); fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({checks,errors},null,2));
  } finally { await browser.close(); }
}
server.listen(0,'127.0.0.1',async()=>{
  fs.mkdirSync(out,{recursive:true});
  try { await verify(`http://127.0.0.1:${server.address().port}`); }
  catch(e){ console.error(e); process.exitCode=1; }
  finally { server.close(); }
});
