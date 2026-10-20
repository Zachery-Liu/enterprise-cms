<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef } from 'vue';
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElDialog, ElEmpty, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElPageHeader, ElResult, ElSelect, ElTag, ElTree } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { can, errorText, request, StaleResponse } from '../api';
import { availableCategories, type Category } from '../models';
type Mode = 'create' | 'rename' | 'move' | 'sort' | 'status' | 'delete';
const tree = ref<Category[]>([]), selected = ref<Category | null>(null), busy = ref(false), saving = ref(false), error = ref(''), dialogError = ref(''), open = ref(false), mode = ref<Mode>('create');
const form = reactive({ name: '', parentId: null as number | null, sortOrder: 0 });
const captured = shallowRef<Category | null>(null);
let live = true;
import { onUnmounted } from 'vue';
onUnmounted(() => { live = false; });
const options = computed(() => availableCategories(tree.value, mode.value === 'move' ? captured.value?.id : undefined));
const labels: Record<Mode, string> = { create: '新增分类', rename: '分类更名', move: '移动分类', sort: '调整排序', status: '分类启停', delete: '删除分类' };
const manage = computed(() => can('cms:category:manage'));
async function load() {
  busy.value = true; error.value = '';
  try { const result = await request<Category[]>('categories/tree'); if (live) { tree.value = result; selected.value = null; } }
  catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); } finally { busy.value = false; }
}
function edit(value: Mode) {
  mode.value = value; captured.value = value === 'create' ? null : selected.value;
  if (value !== 'create' && !captured.value) return;
  Object.assign(form, { name: captured.value?.name || '', parentId: captured.value?.parentId ?? null, sortOrder: captured.value?.sortOrder || 0 });
  dialogError.value = ''; open.value = true;
}
async function save() {
  if (saving.value) return;
  const value = mode.value;
  if (['create', 'rename'].includes(value) && !form.name.trim()) { dialogError.value = '分类名称不能为空。'; return; }
  saving.value = true; dialogError.value = '';
  const payload: Record<string, string | number | null> = captured.value ? { version: captured.value.version } : {};
  if (['create', 'rename'].includes(value)) payload.name = form.name.trim();
  if (['create', 'move'].includes(value)) payload.parentId = form.parentId;
  if (['create', 'sort'].includes(value)) payload.sortOrder = form.sortOrder;
  if (value === 'status') payload.status = captured.value?.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  const suffix = { move: '/parent', sort: '/sort', status: '/status' }[value as 'move' | 'sort' | 'status'] || '';
  try {
    await request(value === 'create' ? 'categories' : `categories/${captured.value!.id}${suffix}`, value === 'delete'
      ? { method: 'DELETE', query: { version: captured.value!.version } } : { method: value === 'create' ? 'POST' : value === 'rename' ? 'PUT' : 'PATCH', body: payload });
    open.value = false; await load();
  } catch (e) { if (!(e instanceof StaleResponse)) dialogError.value = errorText(e); } finally { saving.value = false; }
}
onMounted(() => { if (can('cms:category:read')) void load(); });
</script>
<template>
  <el-result v-if="!can('cms:category:read')" icon="warning" title="没有分类读取权限" />
  <template v-else>
    <el-page-header icon="" title="内容管理" content="分类管理" class="page-header" />
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
    <el-card>
      <div class="table-tools"><el-button v-if="manage" type="primary" :icon="Plus" @click="edit('create')">新增分类</el-button><el-button :icon="Refresh" :loading="busy" @click="load">刷新</el-button></div>
      <div class="category-layout">
        <div class="category-tree"><el-tree :data="tree" node-key="id" :props="{ label: 'name', children: 'children' }" highlight-current :expand-on-click-node="false" @node-click="(node: Category) => selected = node" /><el-empty v-if="!tree.length && !busy" description="暂无分类" :image-size="65" /></div>
        <div class="content-panel">
          <template v-if="selected">
            <el-descriptions title="分类详情" :column="1" border>
              <el-descriptions-item label="名称">{{ selected.name }}</el-descriptions-item><el-descriptions-item label="分类 ID">{{ selected.id }}</el-descriptions-item>
              <el-descriptions-item label="上级 ID">{{ selected.parentId ?? '根分类' }}</el-descriptions-item><el-descriptions-item label="排序">{{ selected.sortOrder }}</el-descriptions-item>
              <el-descriptions-item label="状态"><el-tag :type="selected.status === 'ENABLED' ? 'success' : 'info'">{{ selected.status === 'ENABLED' ? '启用' : '停用' }}</el-tag></el-descriptions-item><el-descriptions-item label="版本">{{ selected.version }}</el-descriptions-item>
            </el-descriptions>
            <el-alert title="分类可用性还取决于祖先是否启用。停用分类不会自动下架已发布内容。" type="info" :closable="false" class="page-alert" style="margin-top:16px" />
            <div v-if="manage"><el-button @click="edit('rename')">更名</el-button><el-button @click="edit('move')">移动</el-button><el-button @click="edit('sort')">排序</el-button><el-button @click="edit('status')">{{ selected.status === 'ENABLED' ? '停用' : '启用' }}</el-button><el-button type="danger" plain @click="edit('delete')">删除</el-button></div>
          </template><el-empty v-else description="请从左侧选择分类" />
        </div>
      </div>
    </el-card>
    <el-dialog v-model="open" :title="labels[mode]" width="520px" destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-alert v-if="dialogError" :title="dialogError" type="error" show-icon :closable="false" class="page-alert" />
      <el-form label-width="90px" @submit.prevent="save">
        <el-form-item v-if="['create','rename'].includes(mode)" label="分类名称" required><el-input v-model="form.name" name="categoryName" :maxlength="64" show-word-limit /></el-form-item>
        <el-form-item v-if="['create','move'].includes(mode)" label="上级分类"><el-select v-model="form.parentId" clearable placeholder="根分类" aria-label="上级分类" @clear="form.parentId = null"><el-option v-for="item in options" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
        <el-form-item v-if="['create','sort'].includes(mode)" label="排序值"><el-input-number v-model="form.sortOrder" :min="0" :max="2147483647" :precision="0" /></el-form-item>
        <el-alert v-if="mode === 'delete'" title="分类将被逻辑删除；存在子分类或有效文章引用时会拒绝删除。" type="warning" :closable="false" />
        <el-alert v-if="mode === 'status'" :title="captured?.status === 'ENABLED' ? '确认停用该分类？其后代也将不可用于新增和发布。' : '确认启用该分类？服务端会校验祖先可用性。'" type="warning" :closable="false" />
      </el-form>
      <template #footer><el-button :disabled="saving" @click="open = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确认保存</el-button></template>
    </el-dialog>
  </template>
</template>
