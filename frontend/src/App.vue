<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { ElAlert, ElAside, ElButton, ElCard, ElConfigProvider, ElContainer, ElForm, ElFormItem, ElHeader, ElIcon, ElInput, ElMain, ElMenu, ElMenuItem, ElResult } from 'element-plus';
import { clearDraft } from './content/editorDrafts';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { Document, Folder, Checked, Reading, SwitchButton, OfficeBuilding } from '@element-plus/icons-vue';
import { ApiError, currentUser, errorText, login, logout, permissionRevision, restore, StaleResponse } from './api';
const ArticleManager = defineAsyncComponent(() => import('./components/ArticleManager.vue'));
const CategoryManager = defineAsyncComponent(() => import('./components/CategoryManager.vue'));
const PublicReader = defineAsyncComponent(() => import('./components/PublicReader.vue'));
const PublicSiteLayout = defineAsyncComponent(() => import('./components/PublicSiteLayout.vue'));
const route = ref(location.hash.slice(1) || (location.pathname.endsWith('public.html') ? 'public' : 'articles'));
const isPublic = computed(() => route.value.startsWith('public'));
const section = computed(() => ['categories', 'review'].includes(route.value) ? route.value : 'articles');
const workspace = ref<{ beforeLeave: () => Promise<boolean>; discard: () => void }>();
let acceptedHref = location.href, navigation = 0;
const credentials = reactive({ username: '', password: '' }), busy = ref(false), checking = ref(false), message = ref(''), blocked = ref(false);
const media = window.matchMedia('(max-width:760px)'), compact = ref(media.matches);
const resized = () => { compact.value = media.matches; };
let attempted = false;
const navigate = (value: string) => { location.hash = value; };
const changed = async () => {
  const attempt = ++navigation, target = location.href;
  if (workspace.value && !(await workspace.value.beforeLeave())) {
    if (attempt === navigation) history.replaceState(null, '', acceptedHref);
    return;
  }
  if (attempt !== navigation) return;
  workspace.value?.discard();
  acceptedHref = target; route.value = new URL(target).hash.slice(1) || (location.pathname.endsWith('public.html') ? 'public' : 'articles');
};
const expired = () => { if (!checking.value) message.value = '登录已失效，请重新登录。'; blocked.value = false; credentials.password = ''; };
async function identify() {
  if (isPublic.value || attempted) return;
  attempted = true; checking.value = true;
  try { await restore(); } catch (error) { if (!(error instanceof ApiError && error.status === 401) && !(error instanceof StaleResponse)) message.value = errorText(error); }
  finally { checking.value = false; }
}
async function signIn() {
  if (!credentials.username.trim() || !credentials.password || busy.value) return;
  busy.value = true; message.value = '';
  try { await login(credentials.username.trim(), credentials.password); blocked.value = false; }
  catch (error) { message.value = errorText(error); } finally { credentials.password = ''; busy.value = false; }
}
async function signOut() {
  if (workspace.value && !(await workspace.value.beforeLeave())) return;
  const owner = currentUser.value?.userId;
  busy.value = true;
  try { await logout(); clearDraft(owner); message.value = ''; blocked.value = false; } catch (error) { message.value = errorText(error); } finally { busy.value = false; }
}
watch(route, () => { void identify(); });
watch(permissionRevision, async () => {
  blocked.value = true; message.value = '请求被拒绝，请核对权限后重新加载。写操作未自动重试。';
  try { await restore(); } catch (error) { if (!(error instanceof StaleResponse)) message.value = errorText(error); }
});
onMounted(() => { window.addEventListener('hashchange', changed); window.addEventListener('cms:unauthenticated', expired); media.addEventListener('change', resized); void identify(); });
onUnmounted(() => { window.removeEventListener('hashchange', changed); window.removeEventListener('cms:unauthenticated', expired); media.removeEventListener('change', resized); });
</script>
<template>
  <el-config-provider :locale="zhCn">
    <PublicSiteLayout v-if="isPublic"><PublicReader :route="route" /></PublicSiteLayout>
    <main v-else-if="!currentUser" class="admin-login">
      <div class="admin-login-panel">
        <div class="admin-brand"><el-icon><OfficeBuilding /></el-icon><span>CMS 内容管理</span></div>
        <el-result v-if="checking" title="正在检查登录状态" />
        <el-card v-else class="login-card" shadow="never">
            <h1>登录内容工作台</h1>
            <p class="login-description">使用你的 CMS 账号登录。</p>
            <el-form label-position="top" :model="credentials" :disabled="checking || busy" @submit.prevent="signIn">
              <el-form-item label="用户名" required><el-input v-model="credentials.username" name="username" autocomplete="username" :maxlength="64" /></el-form-item>
              <el-form-item label="密码" required><el-input v-model="credentials.password" name="password" type="password" show-password autocomplete="current-password" :maxlength="128" /></el-form-item>
              <el-alert v-if="message" :title="message" type="warning" show-icon class="page-alert" />
              <el-button type="primary" native-type="submit" :loading="busy || checking">登录</el-button>
            </el-form>
          </el-card>
      </div>
    </main>
    <el-container v-else class="app-shell admin-shell">
      <el-aside :width="compact ? '60px' : '208px'" class="app-aside">
        <div class="admin-brand">
          <el-icon><OfficeBuilding /></el-icon>
          <span v-if="!compact">CMS 内容管理</span>
        </div>
        <el-menu :default-active="section" :collapse="compact" @select="navigate" aria-label="后台导航">
          <el-menu-item index="articles"><el-icon><Document /></el-icon><template #title>文章管理</template></el-menu-item>
          <el-menu-item index="categories"><el-icon><Folder /></el-icon><template #title>分类管理</template></el-menu-item>
          <el-menu-item index="review"><el-icon><Checked /></el-icon><template #title>审核队列</template></el-menu-item>
          <el-menu-item index="public"><el-icon><Reading /></el-icon><template #title>公开门户</template></el-menu-item>
        </el-menu>
      </el-aside>
      <el-container class="admin-workspace">
        <el-header class="app-header">
          <span class="admin-location">内容工作台</span>
          <span class="identity">{{ currentUser.displayName || currentUser.username }}</span>
          <el-button :icon="SwitchButton" :loading="busy" @click="signOut">退出登录</el-button>
        </el-header>
        <el-main class="admin-main">
          <el-alert v-if="message" :title="message" type="warning" show-icon class="page-alert" @close="message = ''" />
          <el-result v-if="blocked" icon="warning" title="请求被拒绝" sub-title="权限已重新读取，请核对后继续。"><template #extra><el-button type="primary" @click="blocked = false; message = ''">重新加载</el-button></template></el-result>
          <CategoryManager v-else-if="section === 'categories'" />
          <ArticleManager v-else ref="workspace" :key="`${currentUser?.userId}:${section}`" :review="section === 'review'" />
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>
