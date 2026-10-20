<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElAlert, ElButton, ElCard, ElDatePicker, ElDescriptions, ElDescriptionsItem, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElPageHeader, ElPagination, ElResult, ElSelect, ElTable, ElTableColumn, ElTag, type TableInstance } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import { can, errorText, request, StaleResponse } from '../api';
import { displayDate, states, tagType, type Article, type Detail, type History, type Page } from '../models';
import ArticleEditor from './ArticleEditor.vue';
import ArticleAction from './ArticleAction.vue';
import SafePreview from './SafePreview.vue';
const props = defineProps<{ review: boolean }>();
const permitted = computed(() => can(`cms:article:${props.review ? 'audit' : 'read'}`));
const page = ref<Page<Article>>({ records: [], total: 0, page: 1, size: 20 }), busy = ref(false), error = ref(''), selection = ref<Article[]>([]), tableRef = ref<TableInstance>();
const query = reactive({ title: '', categoryId: undefined as number | undefined, authorId: undefined as number | undefined, status: '', sort: 'updatedAt', direction: 'DESC', page: 1, size: 20 });
const range = ref<[Date, Date] | null>(null), editor = ref(false), editing = ref<Detail>(), detail = ref<Detail | null>(null), action = ref(''), targets = ref<Article[]>([]);
const histories = ref<Page<History> | null>(null), historyId = ref<number | null>(null);
let revision = 0, live = true;
onUnmounted(() => { live = false; revision++; });
const allowed = (scope: string) => can(`cms:article:${scope}`);
const transitions = [
  { action: 'submit', label: '提交审核', scope: 'submit', from: ['DRAFT', 'REJECTED'] }, { action: 'approve', label: '通过', scope: 'audit', from: ['PENDING'] },
  { action: 'reject', label: '退回', scope: 'audit', from: ['PENDING'] }, { action: 'publish', label: '发布', scope: 'publish', from: ['APPROVED'] },
  { action: 'offline', label: '下架', scope: 'offline', from: ['PUBLISHED'] }, { action: 'reopen', label: '重开编辑', scope: 'edit', from: ['OFFLINE'] },
  { action: 'delete', label: '删除', scope: 'delete', from: ['DRAFT','REJECTED','OFFLINE'] }
];
function actions(article: Article) { return transitions.filter(item => allowed(item.scope) && item.from.includes(article.status)); }
async function load() {
  const id = ++revision; busy.value = true; error.value = ''; selection.value = []; tableRef.value?.clearSelection();
  const filter: Record<string, string | number | undefined> = props.review ? { page: query.page, size: query.size, title: query.title, categoryId: query.categoryId, authorId: query.authorId } : { ...query };
  if (range.value) { const prefix = props.review ? 'submitted' : 'updated'; filter[`${prefix}From`] = range.value[0].toISOString(); filter[`${prefix}To`] = range.value[1].toISOString(); }
  try { const result = await request<Page<Article>>(props.review ? 'articles/review-queue' : 'articles', { query: filter }); if (live && id === revision) page.value = result; }
  catch (e) { if (!(e instanceof StaleResponse) && id === revision) { error.value = errorText(e); page.value = { records: [], total: 0, page: query.page, size: query.size }; } }
  finally { if (id === revision) busy.value = false; }
}
function filter() {
  if (range.value && range.value[0] >= range.value[1]) { error.value = '起始时间必须早于截止时间。'; return; }
  query.page = 1; void load();
}
function reset() { Object.assign(query, { title: '', categoryId: undefined, authorId: undefined, status: '', sort: 'updatedAt', direction: 'DESC', page: 1, size: 20 }); range.value = null; void load(); }
async function inspect(article: Article, edit = false) {
  try { const value = await request<Detail>(`articles/${article.id}`); if (!live) return; if (edit) { editing.value = value; editor.value = true; } else detail.value = value; }
  catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); }
}
function choose(actionName: string, articles: Article[]) { targets.value = [...articles]; action.value = actionName; }
function selected(rows: Article[]) {
  if (rows.length > 50) { const extra = rows[rows.length - 1]; tableRef.value?.toggleRowSelection(extra, false); error.value = '每批最多选择 50 篇文章。'; return; }
  selection.value = rows;
}
async function history(id: number, number = 1) {
  try { const result = await request<Page<History>>(`articles/${id}/history`, { query: { page: number, size: 20 } }); if (live) { histories.value = result; historyId.value = id; } }
  catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); }
}
function saved() { editor.value = false; editing.value = undefined; action.value = ''; void load(); }
onMounted(() => { if (permitted.value) void load(); });
</script>
<template>
  <el-result v-if="!permitted" icon="warning" title="没有此页面的读取权限" />
  <template v-else>
    <el-page-header icon="" title="内容管理" :content="review ? '审核队列' : '文章管理'" class="page-header" />
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
    <el-card>
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent="filter">
        <el-form-item label="标题"><el-input v-model="query.title" name="filterTitle" placeholder="标题关键词" clearable :maxlength="200" style="width:190px" /></el-form-item>
        <el-form-item label="分类 ID"><el-input-number v-model="query.categoryId" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" style="width:110px" /></el-form-item>
        <el-form-item label="作者 ID"><el-input-number v-model="query.authorId" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" style="width:110px" /></el-form-item>
        <el-form-item v-if="!review" label="状态"><el-select v-model="query.status" clearable placeholder="全部" style="width:130px" aria-label="状态筛选"><el-option v-for="(label,value) in states" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item v-if="!review" label="排序"><el-select v-model="query.sort" style="width:130px" aria-label="排序字段"><el-option v-for="(label,value) in { updatedAt:'更新时间',createdAt:'创建时间',publishedAt:'发布时间',title:'标题' }" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item v-if="!review"><el-select v-model="query.direction" style="width:90px" aria-label="排序方向"><el-option label="降序" value="DESC" /><el-option label="升序" value="ASC" /></el-select></el-form-item>
        <el-form-item :label="review ? '提交时间' : '更新时间'"><el-date-picker v-model="range" type="datetimerange" start-placeholder="开始（本地时间）" end-placeholder="截止（不含）" style="width:345px" /></el-form-item>
        <el-form-item><el-button type="primary" :icon="Search" native-type="submit" :loading="busy">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <div class="table-tools"><div><el-button v-if="!review && allowed('create')" type="primary" :icon="Plus" @click="editing = undefined; editor = true">新建文章</el-button><el-button v-if="allowed('publish')" :disabled="selection.length === 0" @click="choose('batch',selection)">批量发布（{{ selection.length }}/50）</el-button></div><el-button :icon="Refresh" :loading="busy" @click="load">刷新</el-button></div>
      <el-table ref="tableRef" :data="page.records" row-key="id" border stripe empty-text="没有符合条件的文章" @selection-change="selected">
        <el-table-column v-if="allowed('publish')" type="selection" width="48" :selectable="(row: Article) => row.status === 'APPROVED'" />
        <!-- @vue-generic {Article} -->
        <el-table-column label="文章标题" min-width="240"><template #default="{row}"><el-button v-if="allowed('read')" link type="primary" class="article-title" @click="inspect(row)">{{ row.title }}</el-button><span v-else>{{ row.title }}</span></template></el-table-column>
        <el-table-column prop="categoryName" label="分类" min-width="110" /><el-table-column prop="authorDisplayName" label="作者" min-width="110" />
        <el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="tagType(row.status)">{{ states[row.status as keyof typeof states] }}</el-tag></template></el-table-column>
        <el-table-column :label="review ? '提交时间' : '更新时间'" width="180"><template #default="{row}">{{ displayDate(review ? row.submittedAt : row.updatedAt) }}</template></el-table-column>
        <!-- @vue-generic {Article} -->
        <el-table-column label="操作" min-width="250"><template #default="{row}"><el-button v-if="allowed('edit') && ['DRAFT','REJECTED'].includes(row.status)" link type="primary" @click="inspect(row,true)">编辑</el-button><el-button v-for="item in actions(row)" :key="item.action" link :type="item.action === 'delete' ? 'danger' : 'primary'" @click="choose(item.action,[row])">{{ item.label }}</el-button><el-button v-if="allowed('read')" link type="primary" @click="history(row.id)">历史</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[10,20,50,100]" :total="page.total" layout="total, sizes, prev, pager, next" class="page-pagination" @current-change="load" @size-change="query.page = 1; load()" />
    </el-card>
    <ArticleEditor v-if="editor" :article="editing" @close="editor = false; editing = undefined" @saved="saved" />
    <ArticleAction v-if="action" :action="action" :articles="targets" @close="action = ''" @saved="saved" />
    <el-dialog :model-value="Boolean(detail)" title="文章详情" width="860px" destroy-on-close @close="detail = null"><template v-if="detail"><h3>{{ detail.title }}</h3><el-descriptions :column="2" border><el-descriptions-item label="分类">{{ detail.categoryName }}</el-descriptions-item><el-descriptions-item label="作者">{{ detail.authorDisplayName }}</el-descriptions-item><el-descriptions-item label="状态">{{ states[detail.status] }}</el-descriptions-item><el-descriptions-item label="版本">{{ detail.version }}</el-descriptions-item><el-descriptions-item label="更新时间">{{ displayDate(detail.updatedAt) }}</el-descriptions-item><el-descriptions-item label="审核意见">{{ detail.reviewComment || '—' }}</el-descriptions-item></el-descriptions><p>{{ detail.summary }}</p><SafePreview :content="detail.content" /></template></el-dialog>
    <el-dialog :model-value="Boolean(histories)" title="状态历史" width="900px" destroy-on-close @close="histories = null; historyId = null"><template v-if="histories"><el-table :data="histories.records" border><el-table-column prop="action" label="动作" width="110" /><el-table-column label="原状态" width="100"><template #default="{row}">{{ row.fromStatus ? states[row.fromStatus as keyof typeof states] : '—' }}</template></el-table-column><el-table-column label="目标状态" width="100"><template #default="{row}">{{ states[row.toStatus as keyof typeof states] }}</template></el-table-column><el-table-column prop="operatorDisplayName" label="操作者" width="110" /><el-table-column prop="reason" label="原因" min-width="180" /><el-table-column label="时间" width="180"><template #default="{row}">{{ displayDate(row.createdAt) }}</template></el-table-column></el-table><el-pagination :current-page="histories.page" :page-size="20" :total="histories.total" layout="total, prev, pager, next" class="page-pagination" @current-change="number => historyId && history(historyId,number)" /></template></el-dialog>
  </template>
</template>
