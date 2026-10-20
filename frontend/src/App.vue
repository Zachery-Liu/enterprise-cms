<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { ElAlert, ElAside, ElBreadcrumb, ElBreadcrumbItem, ElButton, ElCard, ElConfigProvider, ElContainer, ElForm, ElFormItem, ElHeader, ElIcon, ElInput, ElMain, ElMenu, ElMenuItem, ElResult } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { Document, Folder, Checked, Reading, SwitchButton } from '@element-plus/icons-vue';
import { ApiError, currentUser, errorText, login, logout, permissionRevision, restore, StaleResponse } from './api';
const ArticleManager = defineAsyncComponent(() => import('./components/ArticleManager.vue'));
const CategoryManager = defineAsyncComponent(() => import('./components/CategoryManager.vue'));
const PublicReader = defineAsyncComponent(() => import('./components/PublicReader.vue'));
const route = ref(location.hash.slice(1) || (location.pathname.endsWith('public.html') ? 'public' : 'articles'));
const isPublic = computed(() => route.value.startsWith('public'));
const section = computed(() => ['categories', 'review'].includes(route.value) ? route.value : 'articles');
const title = computed(() => isPublic.value ? '公开内容' : ({ articles: '文章管理', categories: '分类管理', review: '审核队列' })[section.value]);
const credentials = reactive({ username: '', password: '' }), busy = ref(false), checking = ref(false), message = ref(''), blocked = ref(false);
const media = window.matchMedia('(max-width:760px)'), compact = ref(media.matches);
const resized = () => { compact.value = media.matches; };
let attempted = false;
const navigate = (value: string) => { location.hash = value; };
const changed = () => { route.value = location.hash.slice(1) || 'articles'; };
const expired = () => { message.value = '登录已失效，请重新登录。'; blocked.value = false; credentials.password = ''; };
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
  busy.value = true;
  try { await logout(); message.value = ''; blocked.value = false; } catch (error) { message.value = errorText(error); } finally { busy.value = false; }
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
    <el-container class="app-shell">
      <el-aside v-if="!isPublic" :width="compact ? '64px' : '200px'" class="app-aside">
        <div class="brand">{{ compact ? 'CMS' : 'CMS 内容管理' }}</div>
        <el-menu :default-active="section" :collapse="compact" @select="navigate">
          <el-menu-item index="articles"><el-icon><Document /></el-icon><span>文章管理</span></el-menu-item>
          <el-menu-item index="categories"><el-icon><Folder /></el-icon><span>分类管理</span></el-menu-item>
          <el-menu-item index="review"><el-icon><Checked /></el-icon><span>审核队列</span></el-menu-item>
          <el-menu-item index="public"><el-icon><Reading /></el-icon><span>公开内容</span></el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="app-header">
          <el-breadcrumb separator="/"><el-breadcrumb-item>CMS</el-breadcrumb-item><el-breadcrumb-item>{{ title }}</el-breadcrumb-item></el-breadcrumb>
          <span class="identity">{{ currentUser?.displayName || currentUser?.username || '访客' }}</span>
          <el-button v-if="isPublic" @click="navigate('articles')">进入工作台</el-button>
          <el-button v-if="currentUser" :icon="SwitchButton" :loading="busy" @click="signOut">退出登录</el-button>
        </el-header>
        <el-main>
          <el-alert v-if="message" :title="message" type="warning" show-icon class="page-alert" @close="message = ''" />
          <PublicReader v-if="isPublic" :route="route" />
          <el-card v-else-if="!currentUser" class="login-card">
            <template #header>登录内容工作台</template>
            <el-form label-position="top" :model="credentials" :disabled="checking || busy" @submit.prevent="signIn">
              <el-form-item label="用户名" required><el-input v-model="credentials.username" name="username" autocomplete="username" :maxlength="64" /></el-form-item>
              <el-form-item label="密码" required><el-input v-model="credentials.password" name="password" type="password" show-password autocomplete="current-password" :maxlength="128" /></el-form-item>
              <el-button type="primary" native-type="submit" :loading="busy || checking">登录</el-button>
            </el-form>
          </el-card>
          <el-result v-else-if="blocked" icon="warning" title="请求被拒绝" sub-title="权限已重新读取，请核对后继续。"><template #extra><el-button type="primary" @click="blocked = false; message = ''">重新加载</el-button></template></el-result>
          <CategoryManager v-else-if="section === 'categories'" />
          <ArticleManager v-else :key="section" :review="section === 'review'" />
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>
