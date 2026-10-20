/* Real A controllers + B security/logging. Run only against an explicit loopback test deployment. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
async function main() {
  const base = process.env.CMS_CONTENT_LIVE_BASE;
  assert.ok(base, 'Set CMS_CONTENT_LIVE_BASE to the explicit local test deployment');
  assert.ok(['127.0.0.1','localhost'].includes(new URL(base).hostname), 'Loopback test harness only');
  const username = process.env.CMS_CONTENT_LIVE_USER, password = process.env.CMS_CONTENT_LIVE_PASSWORD;
  assert.ok(username && password, 'Provide explicit test-only credentials in environment variables');
  const { chromium } = require(process.env.CMS_PLAYWRIGHT_MODULE || 'playwright');
  const browser = await chromium.launch({ headless: true, ...(process.env.CMS_CHROMIUM_EXECUTABLE ? { executablePath: process.env.CMS_CHROMIUM_EXECUTABLE } : {}) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const out = path.resolve(__dirname, '../../../output/playwright'); fs.mkdirSync(out, { recursive: true });
  const errors = [], checks = []; page.on('pageerror', error => errors.push(error.message));
  const check = label => { checks.push(label); process.stdout.write(`PASS ${label}\n`); };
  const click = name => page.getByRole('button', { name, exact: true }).click();
  const menu = name => page.getByRole('menuitem', { name, exact: true }).click();
  const suffix = Date.now().toString(36), category = `浏览器分类-${suffix}`, title = `真实联调文章-${suffix}`;
  const prefix = process.env.CMS_CONTENT_LIVE_OUTPUT_PREFIX || 'content-live';
  assert.match(prefix, /^[a-z0-9-]+$/);
  async function filterOwnArticle() {
    const loaded = page.waitForResponse(response => {
      const url = new URL(response.url());
      return response.request().method() === 'GET' && /\/api\/articles(?:\/review-queue)?$/.test(url.pathname) && url.searchParams.get('title') === title;
    });
    await page.locator('input[name=filterTitle]').fill(title); await click('查询'); await loaded;
  }
  try {
    await page.goto(`${base}/content/index.html`); await page.locator('.login-card').waitFor();
    await page.locator('input[name=username]').fill(username); await page.locator('input[name=password]').fill(password); await click('登录');
    await page.getByRole('button', { name: '新建文章', exact: true }).waitFor();
    const identity = await (await page.request.get(`${base}/api/auth/me`)).json(); const actor = identity.data.userId;
    check('真实 B 登录、令牌轮换和 auth/me 权限加载');
    await menu('分类管理'); await click('新增分类'); await page.locator('input[name=categoryName]').fill(category); await click('确认保存');
    await page.locator('.el-tree').getByText(category, { exact: true }).waitFor(); check('分类组件调用真实受保护 Controller 并持久化');
    await menu('文章管理'); await filterOwnArticle(); await click('新建文章'); await page.locator('input[name=articleTitle]').fill(title);
    await page.locator('textarea[name=content]').fill('<p>真实后端正文</p><script>window.injected=true</script>');
    await page.locator('.el-dialog:visible .el-select').click(); await page.locator('.el-select-dropdown:visible [role=option]').getByText(category, { exact: true }).click();
    const created = page.waitForResponse(response => response.url() === `${base}/api/articles` && response.request().method() === 'POST');
    await click('保存草稿'); const saved = await (await created).json(); const id = saved.data.id;
    assert.ok(id > 0); assert.equal(saved.data.status, 'DRAFT'); assert.ok(!saved.data.content.includes('<script'));
    await page.locator('.el-table__body-wrapper').getByText(title, { exact: true }).waitFor(); check('真实草稿创建、服务端身份与正文清洗');
    await click('提交审核'); await click('确认提交审核'); await menu('审核队列'); await filterOwnArticle(); await page.getByRole('button', { name: '通过', exact: true }).waitFor();
    await click('通过'); await click('确认通过'); await page.getByText('没有符合条件的文章', { exact: true }).waitFor();
    await menu('文章管理'); await filterOwnArticle(); await click('发布'); await click('确认发布'); await page.getByRole('button', { name: '下架', exact: true }).waitFor(); check('真实提交、待审队列、审核与发布流程');
    const publicContext = await browser.newContext(); const reader = await publicContext.newPage(); reader.on('pageerror', error => errors.push(error.message));
    await reader.goto(`${base}/content/public.html`); await reader.getByRole('link', { name: title, exact: true }).click(); await reader.locator('iframe').waitFor();
    assert.ok(!(await reader.locator('iframe').getAttribute('srcdoc')).includes('<script'));
    assert.equal((await publicContext.cookies()).length, 0); check('无登录 Cookie 的独立浏览器只能读取真实公开投影');
    await page.screenshot({ path: path.join(out, `${prefix}-published.png`), fullPage: true });
    await click('下架'); await click('确认下架'); await page.getByRole('button', { name: '重开编辑', exact: true }).waitFor();
    await reader.reload(); await reader.getByText('文章不存在，或已停止公开。', { exact: true }).waitFor(); assert.equal(await reader.locator('iframe').count(), 0);
    await publicContext.close(); check('真实下架后公开详情 404 并清除正文');
    const history = await (await page.request.get(`${base}/api/articles/${id}/history`)).json(); assert.equal(history.data.total, 5);
    const logsResponse = await page.request.get(`${base}/api/operation-logs?module=ARTICLE&result=SUCCESS&operatorId=${actor}`);
    assert.equal(logsResponse.status(), 200); const logs = await logsResponse.json(); assert.equal(logs.data.total, 5);
    assert.ok(logs.data.records.every(record => record.targetId === id)); check('真实状态历史与 B 成功日志均为 5 条且目标 ID 正确');
    await click('退出登录'); await page.locator('.login-card').waitFor();
    assert.equal((await page.request.get(`${base}/api/articles`)).status(), 401); check('真实退出后旧会话不能访问后台');
    assert.equal(errors.length, 0, errors.join('\n'));
    fs.writeFileSync(path.join(out, `${prefix}-results.json`), JSON.stringify({ fixtureOnly: false, database: process.env.CMS_CONTENT_LIVE_DATABASE || 'H2 2.3.232', server: process.env.CMS_CONTENT_LIVE_SERVER || 'loopback Tomcat test container', schema: process.env.CMS_CONTENT_LIVE_SCHEMA || null, productionControllers: true, articleId: id, operatorId: actor, passed: checks.length, checks, uncaughtErrors: errors }, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
