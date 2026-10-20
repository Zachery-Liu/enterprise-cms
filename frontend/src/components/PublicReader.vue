<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElAlert, ElButton, ElCard, ElEmpty, ElForm, ElFormItem, ElInput, ElLink, ElPageHeader, ElPagination } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { ApiError, errorText, request, StaleResponse } from '../api';
import { displayDate, type Page, type PublicArticle, type PublicDetail } from '../models';
import SafePreview from './SafePreview.vue';
const props = defineProps<{ route: string }>();
const page = ref<Page<PublicArticle>>({ records: [], total: 0, page: 1, size: 10 }), detail = ref<PublicDetail | null>(null), title = ref(''), number = ref(1), busy = ref(false), error = ref('');
const articleId = computed(() => props.route.startsWith('public/') ? props.route.split('/')[1] : '');
let generation = 0;
import { onUnmounted } from 'vue';
onUnmounted(() => { generation++; });
async function load() {
  const id = ++generation; busy.value = true; error.value = ''; detail.value = null; page.value.records = [];
  try {
    if (articleId.value) {
      if (!/^[1-9]\d*$/.test(articleId.value)) throw new Error('文章编号无效。');
      const result = await request<PublicDetail>(`public/articles/${articleId.value}`); if (id === generation) detail.value = result;
    } else { const result = await request<Page<PublicArticle>>('public/articles', { query: { page: number.value, size: 10, title: title.value.trim() } }); if (id === generation) page.value = result; }
  } catch (e) { if (id === generation && !(e instanceof StaleResponse)) error.value = e instanceof ApiError && e.status === 404 ? '文章不存在，或已停止公开。' : errorText(e); }
  finally { if (id === generation) busy.value = false; }
}
function search() { number.value = 1; if (articleId.value) location.hash = 'public'; else void load(); }
function back() { location.hash = 'public'; }
watch(() => props.route, load, { immediate: true });
</script>
<template>
  <el-card class="reading-card">
    <el-page-header icon="" title="CMS" content="公开内容" class="page-header" />
    <el-form v-if="!articleId" :inline="true" @submit.prevent="search"><el-form-item label="搜索标题"><el-input v-model="title" name="publicTitle" placeholder="请输入标题关键词" clearable :maxlength="200" /></el-form-item><el-form-item><el-button type="primary" native-type="submit" :icon="Search" :loading="busy">搜索</el-button></el-form-item></el-form>
    <el-button v-if="articleId" @click="back">返回列表</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-alert" /><el-button v-if="error" @click="load">重试</el-button>
    <template v-if="detail"><h2>{{ detail.title }}</h2><div class="metadata">{{ detail.categoryName }} / {{ detail.authorDisplayName }} · {{ displayDate(detail.publishedAt) }}</div><p>{{ detail.summary }}</p><SafePreview :content="detail.content" /></template>
    <template v-else-if="!articleId && !error"><article v-for="article in page.records" :key="article.id" class="reading-item"><el-link type="primary" :href="`#public/${article.id}`"><h3>{{ article.title }}</h3></el-link><p>{{ article.summary || '点击标题阅读全文。' }}</p><div class="metadata">{{ article.categoryName }} / {{ article.authorDisplayName }} · {{ displayDate(article.publishedAt) }}</div></article><el-empty v-if="!busy && !page.records.length" description="暂无符合条件的已发布文章" /><el-pagination v-model:current-page="number" :page-size="10" :total="page.total" layout="total, prev, pager, next" class="page-pagination" @current-change="load" /></template>
  </el-card>
</template>
