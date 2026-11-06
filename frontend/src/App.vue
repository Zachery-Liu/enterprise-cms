<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { ElAlert, ElButton, ElConfigProvider, ElDrawer, ElForm, ElFormItem, ElInput, ElMenu, ElMenuItem } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { Menu, SwitchButton } from '@element-plus/icons-vue';
import { ApiError, currentUser, errorText, login, logout, permissionRevision, restore, StaleResponse, writesLocked } from './api';
import { backendRoute, publicEntry, publicRoute, siteName, type BackendRoute } from './navigation';
import type { LeaveGuard } from './models';
const ArticleManager = defineAsyncComponent(() => import('./components/ArticleManager.vue'));
const CategoryManager = defineAsyncComponent(() => import('./components/CategoryManager.vue'));
const PublicReader = defineAsyncComponent(() => import('./components/PublicReader.vue'));
const publicPage = location.pathname.endsWith('public.html'), initial = location.hash.slice(1);
const route = ref(publicPage ? publicRoute(initial) || 'public' : backendRoute(initial) || 'login');
const section = ref<BackendRoute>(backendRoute(initial) || backendRoute(new URL(location.href).searchParams.get('returnTo')) || 'articles');
const sections = { articles: '文章管理', categories: '分类管理', review: '审核队列' };
const title = computed(() => sections[section.value]);
const credentials = reactive({ username: '', password: '' });
const busy = ref(false), checking = ref(!publicPage), message = ref(''), drawer = ref(false);
const admitted = ref(false), suspended = ref(false), owner = ref<number | null>(null), generation = ref(0);
const manager = ref<LeaveGuard | null>(null);
const menuRevision = ref(0);
const loginVisible = computed(() => !publicPage && !checking.value && (route.value === 'login' || !currentUser.value || suspended.value));
const media = window.matchMedia('(max-width:760px)'), compact = ref(media.matches);
const resized = () => { compact.value = media.matches; if (!media.matches) drawer.value = false; };
let live = true, navigating = false, acceptedUrl = location.href;
function setUrl(value: string, replace = false) {
  const url = new URL(location.href); url.hash = value;
  if (value === 'login') url.searchParams.set('returnTo', section.value); else url.searchParams.delete('returnTo');
  history[replace ? 'replaceState' : 'pushState'](null, '', url);
  acceptedUrl = url.href; route.value = value;
}
async function mayLeave() {
  try { return (await manager.value?.beforeLeave()) ?? true; }
  catch { message.value = '无法确认编辑状态，已保留当前页面。'; return false; }
}
async function navigate(value: string) {
  if (navigating || busy.value || checking.value) { menuRevision.value++; return; }
  if (value === section.value && route.value !== 'login') { drawer.value = false; return; }
  navigating = true;
  try {
    if (!await mayLeave()) return;
    if (value === 'public') { location.assign(publicEntry(location.href)); return; }
    const next = backendRoute(value);
    if (next) { section.value = next; setUrl(currentUser.value && !suspended.value ? next : 'login'); }
    else if (value === 'login') setUrl('login');
    drawer.value = false;
  } finally { navigating = false; menuRevision.value++; }
}
async function changed() {
  if (location.href === acceptedUrl) return;
  const requested = location.href, value = new URL(requested).hash.slice(1);
  if (publicPage) { route.value = publicRoute(value) || 'public'; acceptedUrl = requested; return; }
  // Browser navigation changes the URL before a guard can run: restore while awaiting consent.
  history.replaceState(null, '', acceptedUrl);
  if (publicRoute(value)) {
    if (navigating || busy.value) return;
    navigating = true;
    try { if (await mayLeave()) location.assign(publicEntry(requested, value)); }
    finally { navigating = false; }
  } else await navigate(backendRoute(value) || 'login');
}
function expired() {
  if (publicPage || owner.value === null) return;
  suspended.value = true; writesLocked.value = true; drawer.value = false;
  message.value = '登录已失效，当前输入仅保留在本页内存中。使用原账号登录可继续；换账号将清除原草稿。';
  credentials.password = ''; setUrl('login', true);
}
watch(currentUser, user => {
  // Dispose another account's draft before the new identity can render it.
  if (user && owner.value !== null && user.userId !== owner.value) { admitted.value = false; generation.value++; }
}, { flush: 'sync' });
watch(loginVisible, value => document.body.classList.toggle('cms-auth-overlay', value), { flush: 'sync' });
watch([route, title], () => { if (!publicPage) document.title = `${route.value === 'login' ? '登录' : title.value} · ${siteName}`; }, { immediate: true });
async function identify() {
  if (publicPage) return;
  try { await restore(); if (!live) return; owner.value = currentUser.value!.userId; admitted.value = true; setUrl(section.value, true); }
  catch (error) {
    if (!(error instanceof StaleResponse)) {
      if (!(error instanceof ApiError && error.status === 401)) message.value = errorText(error);
      setUrl('login', true);
    }
  } finally { checking.value = false; }
}
async function signIn() {
  if (!credentials.username.trim() || !credentials.password || busy.value) return;
  busy.value = true; message.value = '';
  try {
    await login(credentials.username.trim(), credentials.password); if (!live) return;
    await nextTick();
    owner.value = currentUser.value!.userId; suspended.value = false; writesLocked.value = false;
    admitted.value = true; setUrl(section.value, true);
  } catch (error) { if (!(error instanceof StaleResponse)) message.value = errorText(error); }
  finally { credentials.password = ''; busy.value = false; }
}
async function signOut() {
  if (busy.value || navigating) return;
  navigating = true;
  try {
    if (!await mayLeave()) return;
    busy.value = true; await logout();
    admitted.value = false; owner.value = null; suspended.value = false; writesLocked.value = false;
    generation.value++; message.value = ''; drawer.value = false; setUrl('login');
  } catch (error) { if (!(error instanceof StaleResponse)) message.value = `退出未完成：${errorText(error)}`; }
  finally { busy.value = false; navigating = false; }
}
watch(permissionRevision, async () => {
  if (publicPage || checking.value || busy.value || !admitted.value) return;
  message.value = '请求被拒绝，正在核对权限。写操作不会自动重试。';
  try { await restore(); } catch (error) { if (!(error instanceof StaleResponse)) message.value = errorText(error); }
});
onMounted(() => {
  if (!publicPage && publicRoute(initial)) { location.replace(publicEntry(location.href, initial)); return; }
  if (publicPage) { document.title = siteName; if (!publicRoute(initial)) setUrl('public', true); }
  window.addEventListener('hashchange', changed); window.addEventListener('popstate', changed);
  window.addEventListener('cms:unauthenticated', expired); media.addEventListener('change', resized); void identify();
});
onUnmounted(() => {
  live = false; document.body.classList.remove('cms-auth-overlay'); writesLocked.value = false;
  window.removeEventListener('hashchange', changed); window.removeEventListener('popstate', changed);
  window.removeEventListener('cms:unauthenticated', expired); media.removeEventListener('change', resized);
});
</script>
<template>
  <el-config-provider :locale="zhCn">
    <div v-if="publicPage" class="public-shell">
      <header class="public-header"><a href="#public" class="site-brand">{{ siteName }}</a><span>发现值得阅读的内容</span></header>
      <main class="public-main"><PublicReader :route="route" /></main>
      <footer class="public-footer"><span>{{ siteName }}</span><a href="index.html#login">后台入口</a></footer>
    </div>
    <div v-else-if="checking" class="session-loading" role="status" aria-live="polite"><span class="loading-dot"></span>正在恢复会话…</div>
    <template v-else>
      <div v-if="admitted" v-show="!loginVisible" class="app-shell" :inert="loginVisible || suspended">
        <aside v-if="!compact" class="app-aside"><div class="brand">{{ siteName }}<small>内容管理工作台</small></div><el-menu :key="menuRevision" :default-active="section" @select="navigate"><el-menu-item v-for="(label, key) in sections" :key="key" :index="key">{{ label }}</el-menu-item><el-menu-item index="public">访问网站 ↗</el-menu-item></el-menu></aside>
        <div class="workspace"><header class="app-header"><el-button v-if="compact" :icon="Menu" aria-label="打开导航菜单" @click="drawer = true" /><span class="workspace-title">{{ title }}</span><span class="identity">{{ currentUser?.displayName || currentUser?.username }}</span><el-button :icon="SwitchButton" :loading="busy" @click="signOut">退出登录</el-button></header>
          <main class="workspace-main"><el-alert v-if="message" :title="message" type="warning" :closable="false" show-icon class="page-alert" /><CategoryManager v-if="section === 'categories'" :key="generation" /><ArticleManager v-else ref="manager" :key="`${generation}-${section}`" :review="section === 'review'" :suspended="suspended" /></main>
        </div>
      </div>
      <el-drawer v-model="drawer" title="导航菜单" direction="ltr" size="min(280px, 85vw)"><el-menu :key="menuRevision" :default-active="section" @select="navigate"><el-menu-item v-for="(label, key) in sections" :key="key" :index="key">{{ label }}</el-menu-item><el-menu-item index="public">访问网站 ↗</el-menu-item></el-menu></el-drawer>
      <main v-if="loginVisible" class="login-shell"><a class="login-brand" href="public.html#public" @click.prevent="navigate('public')">{{ siteName }}</a>
        <section class="login-card"><span class="login-eyebrow">内容管理工作台</span><h1>{{ suspended ? '重新登录' : '欢迎回来' }}</h1><p class="login-intro">登录后，继续你的内容创作与管理。</p><el-alert v-if="message" :title="message" type="warning" :closable="false" show-icon class="page-alert" />
          <el-form label-position="top" :model="credentials" :disabled="busy" @submit.prevent="signIn"><el-form-item label="账号" required><el-input v-model="credentials.username" name="username" autocomplete="username" :maxlength="64" placeholder="请输入账号" size="large" /></el-form-item><el-form-item label="密码" required><el-input v-model="credentials.password" name="password" type="password" show-password autocomplete="current-password" :maxlength="128" placeholder="请输入密码" size="large" /></el-form-item><el-button type="primary" native-type="submit" size="large" :loading="busy" class="login-submit">登录工作台</el-button></el-form>
        </section><span class="login-footer">专注内容，有序协作</span>
      </main>
    </template>
  </el-config-provider>
</template>
