<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElTabPane, ElTabs, type FormInstance, type FormRules } from 'element-plus';
import { can, errorText, request, StaleResponse } from '../api';
import { availableCategories, type Category, type Detail } from '../models';
import SafePreview from './SafePreview.vue';
const props = defineProps<{ article?: Detail }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const form = reactive({ title: props.article?.title || '', summary: props.article?.summary || '', content: props.article?.content || '', categoryId: props.article?.categoryId as number | undefined });
const formRef = ref<FormInstance>(), saving = ref(false), error = ref(''), tab = ref('edit'), categories = ref<Category[]>([]);
const options = computed(() => {
  const result = availableCategories(categories.value);
  if (props.article && !result.some(item => item.value === props.article!.categoryId)) result.unshift({ value: props.article.categoryId, label: `当前分类 #${props.article.categoryId}（不可用）` });
  return result;
});
const rules: FormRules = {
  title: [{ required: true, whitespace: true, message: '请输入标题', trigger: 'blur' }, { max: 200, message: '标题最多 200 字符' }],
  content: [{ required: true, whitespace: true, message: '请输入正文', trigger: 'blur' }, { max: 100000, message: '正文最多 100000 字符' }],
  categoryId: [{ required: true, message: '请选择有效分类', trigger: 'change' }]
};
onMounted(async () => {
  if (!can('cms:category:read')) return;
  try { categories.value = await request<Category[]>('categories/tree'); } catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); }
});
async function save() {
  if (saving.value || !formRef.value) return;
  saving.value = true; error.value = '';
  try {
    const valid = await formRef.value.validate().catch(() => false); if (!valid) return;
    const payload = { title: form.title.trim(), summary: form.summary.trim() || null, content: form.content, categoryId: form.categoryId,
      ...(props.article ? { version: props.article.version } : {}) };
    await request(props.article ? `articles/${props.article.id}` : 'articles', { method: props.article ? 'PUT' : 'POST', body: payload }); emit('saved');
  }
  catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); } finally { saving.value = false; }
}
</script>
<template>
  <el-dialog :model-value="true" :title="article ? '编辑文章' : '新建草稿'" width="860px" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @close="emit('close')">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="page-alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="标题" prop="title"><el-input v-model="form.title" name="articleTitle" :maxlength="200" show-word-limit /></el-form-item>
      <el-form-item label="摘要" prop="summary"><el-input v-model="form.summary" name="summary" type="textarea" :rows="2" :maxlength="500" show-word-limit /></el-form-item>
      <el-form-item label="分类" prop="categoryId"><el-select v-if="can('cms:category:read')" v-model="form.categoryId" filterable placeholder="请选择可用分类" aria-label="文章分类"><el-option v-for="item in options" :key="item.value" :value="item.value" :label="item.label" /></el-select><el-input-number v-else v-model="form.categoryId" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" aria-label="分类 ID" /></el-form-item>
      <el-tabs v-model="tab"><el-tab-pane label="正文编辑" name="edit"><el-form-item label="正文（支持基础 HTML，保存时安全清洗）" prop="content"><el-input v-model="form.content" name="content" type="textarea" :rows="12" :maxlength="100000" show-word-limit /></el-form-item></el-tab-pane><el-tab-pane label="正文预览" name="preview"><SafePreview v-if="tab === 'preview'" :content="form.content" /></el-tab-pane></el-tabs>
      <el-alert v-if="article" :title="`当前版本 ${article.version}；版本冲突时保留输入，请核对后再操作。`" type="info" :closable="false" />
    </el-form>
    <template #footer><el-button :disabled="saving" @click="emit('close')">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ article ? '保存修改' : '保存草稿' }}</el-button></template>
  </el-dialog>
</template>
