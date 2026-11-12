<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ElAlert, ElButton, ElEmpty, ElForm, ElInput, ElPagination, ElSkeleton, ElCard, ElIcon } from 'element-plus';
import { ArrowRight, OfficeBuilding } from '@element-plus/icons-vue';
import { ApiError, errorText, request, StaleResponse } from '../api';
import { displayDate, type Page, type PublicArticle, type PublicCategory, type PublicDetail } from '../models';
import { cleanHtml } from '../content/html';
import { siteName } from '../content/site';
import '../styles/article-content.css';
const props = defineProps<{ route: string }>();
const page = ref<Page<PublicArticle>>({ records: [], total: 0, page: 1, size: 10 }), detail = ref<PublicDetail | null>(null);
const title = ref(''), categoryId = ref<number>(), number = ref(1), busy = ref(false), error = ref('');
const categories = ref<PublicCategory[]>([]), categoriesBusy = ref(false), categoryError = ref('');
const articleId = computed(() => props.route.startsWith('public/') ? props.route.slice(7) : '');
const content = computed(() => cleanHtml(detail.value?.content || ''));
const appliedTitle = ref(''), appliedCategory = ref<number>();
const archive = ref(false);
const isHome = computed(() => !archive.value && !appliedTitle.value && !appliedCategory.value && number.value === 1);
function showArchive() { archive.value = true; search(); }
function publishedDate(value?: string) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
}
function clearFilters() {
  archive.value = false;
  title.value = '';
  categoryId.value = undefined;
  search(true, false);
}
const selectedCategory = computed(() => categories.value.find(item => item.id === appliedCategory.value)?.name);
let generation = 0, alive = true;
function readQuery() {
  const q = new URL(location.href).searchParams;
  archive.value = q.get('view') === 'all';
  title.value = (q.get('title') || '').slice(0, 200);
  const cat = Number(q.get('categoryId')), n = Number(q.get('page'));
  categoryId.value = Number.isSafeInteger(cat) && cat > 0 ? cat : undefined;
  number.value = Number.isSafeInteger(n) && n > 0 ? n : 1;
}
function listUrl(category?: number) {
  const url = new URL(location.href); url.hash = 'public';
  if (category !== undefined) { url.searchParams.set('categoryId', String(category)); url.searchParams.delete('page'); url.searchParams.delete('title'); url.searchParams.delete('view'); }
  return url.href;
}
function detailUrl(id: number) { const url = new URL(location.href); url.hash = `public/${id}`; return url.href; }
async function load() {
  appliedTitle.value = title.value.trim(); appliedCategory.value = categoryId.value;
  const id = ++generation; busy.value = true; error.value = ''; detail.value = null; page.value.records = [];
  document.title = `${articleId.value ? '资讯详情' : '新闻资讯'} · ${siteName}`;
  try {
    if (articleId.value) {
      if (!/^[1-9]\d*$/.test(articleId.value) || !Number.isSafeInteger(Number(articleId.value))) throw new Error('文章不存在，或已停止公开。');
      const result = await request<PublicDetail>(`public/articles/${articleId.value}`);
      if (alive && id === generation) { detail.value = result; document.title = `${result.title} · ${siteName}`; }
    } else {
      const result = await request<Page<PublicArticle>>('public/articles', { query: { page: number.value, size: 10, title: title.value.trim(), categoryId: categoryId.value } });
      if (alive && id === generation) page.value = result;
    }
  } catch (e) { if (alive && id === generation && !(e instanceof StaleResponse)) error.value = e instanceof ApiError && e.status === 404 ? '文章不存在，或已停止公开。' : errorText(e); }
  finally { if (alive && id === generation) busy.value = false; }
}
async function loadCategories() {
  if (categoriesBusy.value) return;
  categoriesBusy.value = true; categoryError.value = '';
  try { const result = await request<PublicCategory[]>('public/categories'); if (alive) { if (!Array.isArray(result) || result.some(item => !Number.isSafeInteger(item.id) || item.id < 1 || typeof item.name !== 'string')) throw new Error('分类数据格式无效。'); categories.value = result; } }
  catch (e) { if (alive && !(e instanceof StaleResponse)) categoryError.value = '栏目暂时不可用，仍可查询和阅读资讯。'; }
  finally { if (alive) categoriesBusy.value = false; }
}
function search(resetPage = true, scrollToResults = true) {
  if (resetPage) number.value = 1;
  const url = new URL(location.href); url.hash = 'public';
  for (const [key,value] of Object.entries({ title: title.value.trim(), categoryId: categoryId.value, view: archive.value ? 'all' : undefined, page: number.value > 1 ? number.value : undefined })) {
    if (value === undefined || value === '') url.searchParams.delete(key); else url.searchParams.set(key, String(value));
  }
  history.pushState(null, '', url); void load();
  void nextTick(() => {
    if (scrollToResults) document.getElementById('portal-news')?.scrollIntoView({ block: 'start' });
    else window.scrollTo({ top: 0 });
  });
}
function popped() { readQuery(); void load(); }
readQuery();
watch(() => props.route, () => { readQuery(); void load(); }, { immediate: true });
onMounted(() => { void loadCategories(); window.addEventListener('popstate', popped); });
onUnmounted(() => { alive = false; generation++; window.removeEventListener('popstate', popped); });
</script>
<template>
  <section class="public-reader" :aria-busy="busy">
    <template v-if="!articleId">
      <section v-if="isHome" class="portal-hero"><div class="portal-shell portal-hero-inner"><div class="portal-hero-copy"><p class="portal-eyebrow">ENTERPRISE INFORMATION</p><h1>企业动态与公告</h1><p class="portal-hero-description">查看近期发布的企业新闻、通知公告和行业资讯。</p><el-button type="primary" size="large" @click="showArchive">查看全部资讯 <el-icon><ArrowRight /></el-icon></el-button></div><div class="portal-hero-visual" aria-hidden="true"><div class="portal-orbit" /><div class="portal-building portal-building-back" /><div class="portal-building portal-building-front" /><div class="portal-visual-base" /><span class="portal-node portal-node-one" /><span class="portal-node portal-node-two" /></div></div></section>
      <div v-else class="portal-page-banner"><div class="portal-shell"><p>NEWS & INFORMATION</p><h1>{{ appliedTitle ? '搜索结果' : selectedCategory || '新闻资讯' }}</h1><div>首页 / 新闻资讯<span v-if="selectedCategory"> / {{ selectedCategory }}</span></div></div></div>
      <section id="portal-news" class="portal-news portal-shell"><header class="portal-section-heading"><div><p>NEWS & UPDATES</p><h2>{{ appliedTitle ? '搜索结果' : selectedCategory || '新闻资讯' }}</h2></div><el-form class="portal-search" @submit.prevent="search()"><el-input v-model="title" name="publicTitle" aria-label="搜索资讯标题" placeholder="搜索资讯" clearable :maxlength="200" /><el-button type="primary" native-type="submit" :loading="busy">搜索</el-button></el-form></header>
        <div class="portal-filter-row"><nav class="portal-category-tabs" aria-label="资讯栏目"><el-button :type="!appliedCategory ? 'primary' : 'default'" :plain="Boolean(appliedCategory)" @click="categoryId = undefined; search()">全部资讯</el-button><el-button v-for="category in categories" :key="category.id" :type="appliedCategory === category.id ? 'primary' : 'default'" @click="categoryId = category.id; search()">{{ category.name }}</el-button></nav><el-button v-if="isHome" link type="primary" @click="showArchive">更多资讯 <el-icon><ArrowRight /></el-icon></el-button><el-button v-else link type="primary" @click="clearFilters">返回首页</el-button></div>
        <div v-if="categoryError" class="portal-data-note" role="status">{{ categoryError }} <el-button link type="primary" :loading="categoriesBusy" @click="loadCategories">重试栏目</el-button></div>
        <p v-if="appliedTitle" class="portal-query-note">关键词「{{ appliedTitle }}」 · {{ page.total }} 条结果</p>
        <el-skeleton v-if="busy" :rows="6" animated />
        <div v-else-if="error" class="public-error"><el-alert :title="error" type="error" :closable="false" show-icon /><el-button @click="load">重新加载</el-button></div>
        <template v-else>
          <div v-if="isHome && page.records.length" class="portal-news-grid" :class="{ 'portal-news-single': page.records.length === 1 }"><el-card shadow="never" class="portal-lead-card"><div class="portal-lead-graphic" aria-hidden="true"><el-icon><OfficeBuilding /></el-icon><span>NEWS</span><div /></div><div class="portal-lead-copy"><div class="portal-article-meta"><a :href="listUrl(page.records[0].categoryId)">{{ page.records[0].categoryName }}</a><time :datetime="page.records[0].publishedAt">{{ publishedDate(page.records[0].publishedAt) }}</time></div><h3><a :href="detailUrl(page.records[0].id)">{{ page.records[0].title }}</a></h3><p v-if="page.records[0].summary">{{ page.records[0].summary }}</p><a class="portal-text-link" :href="detailUrl(page.records[0].id)" :aria-label="`查看详情：${page.records[0].title}`">查看详情 <el-icon><ArrowRight /></el-icon></a></div></el-card><div v-if="page.records.length > 1" class="portal-headlines"><article v-for="article in page.records.slice(1,5)" :key="article.id" class="portal-headline"><time :datetime="article.publishedAt">{{ publishedDate(article.publishedAt) }}</time><h3><a :href="detailUrl(article.id)">{{ article.title }}</a></h3><p v-if="article.summary">{{ article.summary }}</p><a class="portal-text-link" :href="detailUrl(article.id)" :aria-label="`查看详情：${article.title}`">{{ article.categoryName }} <el-icon><ArrowRight /></el-icon></a></article></div></div>
          <div v-else class="portal-archive"><article v-for="article in page.records" :key="article.id" class="portal-archive-item"><div class="portal-article-meta"><a :href="listUrl(article.categoryId)">{{ article.categoryName }}</a><time :datetime="article.publishedAt">{{ publishedDate(article.publishedAt) }}</time></div><h3><a :href="detailUrl(article.id)">{{ article.title }}</a></h3><p v-if="article.summary">{{ article.summary }}</p><a class="portal-text-link" :href="detailUrl(article.id)" :aria-label="`查看详情：${article.title}`">查看详情 <el-icon><ArrowRight /></el-icon></a></article></div>
          <el-empty v-if="!page.records.length" :description="appliedTitle || appliedCategory ? '没有找到符合条件的资讯，请调整关键词或栏目。' : '暂无公开资讯。'" />
          <el-pagination v-if="!isHome && page.total > 10" v-model:current-page="number" :page-size="10" :pager-count="5" :total="page.total" layout="prev, pager, next" class="public-pagination" @current-change="search(false)" /><el-button v-if="!page.records.length && number > 1" @click="search()">返回第一页</el-button>
        </template>
      </section>
    </template>
    <template v-else><div class="portal-page-banner"><div class="portal-shell"><p>NEWS & INFORMATION</p><h1>资讯详情</h1><div>首页 / 新闻资讯 / 资讯详情</div></div></div><div class="portal-detail portal-shell"><a class="public-back" :href="listUrl()">← 返回资讯列表</a><el-skeleton v-if="busy" :rows="8" animated /><div v-else-if="error" class="public-error"><el-alert :title="error" type="error" :closable="false" show-icon /><el-button @click="load">重新加载</el-button><a :href="listUrl()">返回列表</a></div><template v-else-if="detail"><header class="public-article-header"><h1>{{ detail.title }}</h1><div class="public-meta"><a :href="listUrl(detail.categoryId)">{{ detail.categoryName }}</a><span>{{ detail.authorDisplayName }}</span><time :datetime="detail.publishedAt">{{ displayDate(detail.publishedAt) }}</time></div><p v-if="detail.summary" class="public-summary">{{ detail.summary }}</p></header><article class="cms-article-content" aria-label="资讯正文" v-html="content" /></template></div></template>
  </section>
</template>
<style scoped>
.public-reader{min-width:0}.portal-hero{overflow:hidden;background:radial-gradient(ellipse at 85% 25%,#195d88 0,transparent 55%),linear-gradient(110deg,#09233f,#113c64);color:#fff}.portal-hero-inner{min-height:260px;display:flex;align-items:center;position:relative}.portal-hero-copy{position:relative;z-index:1;padding:28px 0;width:62%}.portal-eyebrow{font-size:11px;letter-spacing:.18em;color:#91bbdf;margin:0 0 20px}.portal-hero h1{font-size:44px;line-height:1.5;letter-spacing:.03em;margin:0 0 18px;font-weight:600}.portal-hero-description{color:#b4cbe0;font-size:14px;line-height:1.9;margin:0 0 26px}.portal-hero .el-button{gap:24px;border-color:#fff;background:#fff;color:#114c99;padding-inline:24px}.portal-hero-visual{position:absolute;right:0;top:4px;width:440px;height:360px;opacity:.85;transform:scale(.74);transform-origin:top right}.portal-orbit{position:absolute;border:1px solid #7cbee85c;width:340px;height:340px;border-radius:50%;right:-20px;top:0;transform:rotateX(45deg) rotate(-28deg)}.portal-building{position:absolute;border:1px solid #94c8ef66;background:repeating-linear-gradient(0deg,transparent 0 22px,#b4dbf322 23px 24px),repeating-linear-gradient(90deg,#73b3df22 0 25px,#a9ddff44 26px 27px);transform:skewY(-16deg);box-shadow:18px 12px 0 #071f3e33}.portal-building-back{width:115px;height:180px;right:200px;top:92px}.portal-building-front{width:140px;height:245px;right:48px;top:55px;background-color:#286b9955}.portal-visual-base{position:absolute;width:340px;height:80px;right:15px;bottom:16px;border:1px solid #79c9e63d;transform:skewY(-16deg) skewX(40deg);background:#7bc6ff0a}.portal-node{width:9px;height:9px;position:absolute;background:#7de2f6;border-radius:50%;box-shadow:0 0 25px #6ccff5}.portal-node-one{right:60px;top:40px}.portal-node-two{right:320px;top:265px}.portal-news{padding-block:32px 48px}.portal-section-heading{display:flex;align-items:center;justify-content:space-between;gap:24px;margin-bottom:22px}.portal-section-heading p{color:#8c9cb0;font-size:10px;letter-spacing:.15em;margin:0 0 8px}.portal-section-heading h2{font-size:29px;font-weight:600;margin:0;line-height:1.4}.portal-section-heading>span{font-size:13px;color:var(--portal-muted)}.portal-search{display:flex;gap:8px;width:300px;min-width:0}.portal-search .el-input{min-width:0}.portal-filter-row{display:flex;justify-content:space-between;align-items:start;gap:16px;padding-bottom:24px}.portal-category-tabs{display:flex;flex-wrap:wrap;gap:8px;min-width:0}.portal-category-tabs .el-button+.el-button{margin-left:0}.portal-category-tabs .el-button{font-size:12px}.portal-news-grid{display:grid;grid-template-columns:1fr 1fr;gap:36px;align-items:start}.portal-lead-card.el-card{border-color:var(--portal-line);border-radius:5px}.portal-lead-card :deep(.el-card__body){padding:0}.portal-lead-graphic{height:96px;background:linear-gradient(120deg,#edf4fc,#d4e5f7);display:flex;justify-content:space-between;align-items:center;padding:16px 28px;color:#a5bfdc;position:relative;overflow:hidden}.portal-lead-graphic>.el-icon{font-size:52px;color:#87a9ce}.portal-lead-graphic>span{font-size:29px;letter-spacing:.12em;font-weight:700;color:#b0c8e4}.portal-lead-graphic>div{position:absolute;border:1px solid #c2d7ed;width:220px;height:220px;right:-70px;top:-100px;transform:rotate(35deg)}.portal-lead-copy{padding:20px 28px}.portal-article-meta{display:flex;gap:16px;justify-content:space-between;flex-wrap:wrap;font-size:11px;color:#8592a4}.portal-article-meta a{color:var(--el-color-primary)}.portal-lead-copy h3{font-size:22px;line-height:1.65;margin:15px 0 12px;overflow-wrap:anywhere}.portal-lead-copy>p,.portal-headline>p,.portal-archive-item>p{font-size:13px;color:var(--portal-muted);line-height:1.9;margin:0 0 18px;overflow-wrap:anywhere}.portal-text-link{display:inline-flex;align-items:center;gap:16px;font-size:12px;color:var(--el-color-primary)!important}.portal-headline{padding:0 0 19px;margin-bottom:20px;border-bottom:1px solid var(--portal-line)}.portal-headline:last-child{margin:0}.portal-headline>time{font-size:11px;color:#8392a6}.portal-headline h3{font-size:17px;line-height:1.6;margin:8px 0;overflow-wrap:anywhere}.portal-headline>p{margin-bottom:10px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.portal-news-single{grid-template-columns:1fr}.portal-news-single .portal-lead-card :deep(.el-card__body){display:grid;grid-template-columns:1fr 1fr}.portal-news-single .portal-lead-graphic{height:100%;min-height:220px}.portal-data-note,.portal-query-note{font-size:12px;color:var(--portal-muted);line-height:1.8;margin-bottom:22px}.portal-page-banner{background:linear-gradient(110deg,#eef4fc,#f7f9fc);padding-block:32px;border-top:1px solid #eef1f7}.portal-page-banner p{font-size:10px;color:#8193aa;letter-spacing:.12em;margin:0 0 8px}.portal-page-banner h1{font-size:28px;margin:0 0 18px}.portal-page-banner .portal-shell>div{font-size:11px;color:#8290a3}.portal-archive-item{padding:26px 0;border-bottom:1px solid var(--portal-line)}.portal-archive-item:first-child{padding-top:0}.portal-archive-item h3{font-size:21px;line-height:1.6;margin:12px 0;overflow-wrap:anywhere}.public-pagination{justify-content:center;margin-top:30px}.portal-detail{max-width:860px;padding-block:38px 64px}.public-back{font-size:12px;color:var(--portal-muted)!important;display:inline-block;margin-bottom:30px}.public-article-header{border-bottom:1px solid var(--portal-line);padding-bottom:28px;margin-bottom:30px}.public-article-header h1{font-size:30px;line-height:1.6;margin:0 0 20px;overflow-wrap:anywhere}.public-meta{display:flex;flex-wrap:wrap;gap:18px;font-size:12px;color:var(--portal-muted)}.public-meta a{color:var(--el-color-primary)}.public-summary{font-size:15px;color:var(--portal-muted);line-height:1.9;margin:22px 0 0}.public-error{display:grid;justify-items:start;gap:18px}#portal-news{scroll-margin-top:20px}@media(max-width:960px){.portal-hero-visual{right:-40px;opacity:.5}.portal-hero-copy{width:75%}.portal-news-grid{gap:24px}.portal-lead-graphic{padding:24px}}@media(max-width:760px){.portal-hero-inner{min-height:232px}.portal-hero-copy{width:100%;padding-block:24px}.portal-hero h1{font-size:28px}.portal-hero-description{font-size:12px}.portal-hero-visual{right:-210px;opacity:.25}.portal-news{padding-block:32px}.portal-section-heading{align-items:start;flex-direction:column;gap:20px}.portal-section-heading h2{font-size:25px}.portal-search{width:100%;max-width:none}.portal-filter-row{gap:10px}.portal-filter-row>.el-button{flex-shrink:0}.portal-news-grid{grid-template-columns:1fr;gap:28px}.portal-lead-copy{padding:22px}.portal-lead-copy h3{font-size:20px}.portal-lead-graphic{height:88px}.portal-news-single .portal-lead-card :deep(.el-card__body){display:block}.portal-news-single .portal-lead-graphic{min-height:0;height:88px}.portal-section-heading>span{font-size:12px}.public-article-header h1{font-size:25px}.portal-detail{padding-block:28px 44px}}
</style>
