<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElTabPane, ElTabs, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { can, currentUser, errorText, request, StaleResponse } from '../api';
import { availableCategories, type Category, type Detail } from '../models';
import SafePreview from './SafePreview.vue';
import RichTextEditor from './RichTextEditor.vue';
import { readDraft, keepDraft, clearDraft } from '../content/editorDrafts';
import { CONTENT_LIMIT, hasVisibleText } from '../content/html';
const props = defineProps<{ article?: Detail }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const form = reactive({ title: props.article?.title || '', summary: props.article?.summary || '', content: props.article?.content || '', categoryId: props.article?.categoryId as number | undefined });
const formRef = ref<FormInstance>(), saving = ref(false), error = ref(''), tab = ref('edit'), categories = ref<Category[]>([]);
const uploading = ref(false), compatibility = ref('');
const owner = currentUser.value?.userId;
const disabled = computed(() => saving.value || currentUser.value?.userId !== owner);
const snapshot = () => JSON.stringify(form);
const baseline = ref(snapshot());
let live = true, discarded = false;
const draft = readDraft(owner);
if (draft && draft.article?.id === props.article?.id) Object.assign(form, draft.form);
const dirty = computed(() => snapshot() !== baseline.value);
let confirming: Promise<boolean> | undefined;
async function beforeLeave(): Promise<boolean> {
  if (saving.value) return false;
  if (!dirty.value && !uploading.value) return true;
  if (!confirming) confirming = ElMessageBox.confirm('尚有未保存的修改或正在上传的图片，离开后将丢弃。', '离开编辑？', { confirmButtonText: '丢弃并离开', cancelButtonText: '继续编辑', type: 'warning', distinguishCancelAndClose: true }).then(() => true).catch(() => false).finally(() => { confirming = undefined; });
  return confirming;
}
async function close() { if (await beforeLeave()) { discarded = true; clearDraft(owner); emit('close'); } }
function closeDialog(done: () => void) { void beforeLeave().then(ok => { if (ok) { discarded = true; clearDraft(owner); done(); emit('close'); } }); }
function unloading(event: BeforeUnloadEvent) { if (dirty.value || uploading.value || saving.value) { event.preventDefault(); event.returnValue = ''; } }
onMounted(() => window.addEventListener('beforeunload', unloading));
onUnmounted(() => { if (!discarded && dirty.value) keepDraft(owner, props.article, form); live = false; window.removeEventListener('beforeunload', unloading); });
defineExpose({ beforeLeave, discard: () => { discarded = true; clearDraft(owner); } });
const options = computed(() => {
  const result = availableCategories(categories.value);
  if (props.article && !result.some(item => item.value === props.article!.categoryId)) result.unshift({ value: props.article.categoryId, label: `当前分类 #${props.article.categoryId}（不可用）` });
  return result;
});
const rules: FormRules = {
  title: [{ required: true, whitespace: true, message: '请输入标题', trigger: 'blur' }, { max: 200, message: '标题最多 200 字符' }],
  content: [{ validator: (_rule, value: string, callback) => { if (!hasVisibleText(value)) callback(new Error('正文必须包含可见文字，不能只有空段落或图片。')); else if (value.length > CONTENT_LIMIT) callback(new Error('正文 HTML 最多 100000 字符。')); else callback(); }, trigger: 'blur' }],
  summary: [{ max: 500, message: '摘要最多 500 字符' }],
  categoryId: [{ required: true, message: '请选择有效分类', trigger: 'change' }]
};
onMounted(async () => {
  if (!can('cms:category:read')) return;
  try { categories.value = await request<Category[]>('categories/tree'); } catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); }
});
async function save() {
  if (disabled.value || uploading.value || compatibility.value || !formRef.value) return;
  saving.value = true; error.value = '';
  try {
    const valid = await formRef.value.validate().catch(() => false); if (!valid) return;
    const payload = { title: form.title.trim(), summary: form.summary.trim() || null, content: form.content, categoryId: form.categoryId,
      ...(props.article ? { version: props.article.version } : {}) };
    await request(props.article ? `articles/${props.article.id}` : 'articles', { method: props.article ? 'PUT' : 'POST', body: payload }); if (live && currentUser.value?.userId === owner) { baseline.value = snapshot(); clearDraft(owner); emit('saved'); }
  }
  catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); } finally { saving.value = false; }
}
</script>
<template>
  <el-dialog :model-value="true" :title="article ? '编辑文章' : '新建草稿'" width="860px" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" :before-close="closeDialog" class="article-editor-dialog">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="page-alert" />
    <el-alert v-if="compatibility" :title="compatibility" type="warning" :closable="false" class="page-alert" />
    <el-form ref="formRef" :disabled="disabled" :model="form" :rules="rules" label-position="top">
      <el-form-item label="标题" prop="title"><el-input v-model="form.title" name="articleTitle" :maxlength="200" show-word-limit /></el-form-item>
      <el-form-item label="摘要" prop="summary"><el-input v-model="form.summary" name="summary" type="textarea" :rows="2" :maxlength="500" show-word-limit /></el-form-item>
      <el-form-item label="分类" prop="categoryId"><el-select v-if="can('cms:category:read')" v-model="form.categoryId" filterable placeholder="请选择可用分类" aria-label="文章分类"><el-option v-for="item in options" :key="item.value" :value="item.value" :label="item.label" /></el-select><el-input-number v-else v-model="form.categoryId" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" aria-label="分类 ID" /></el-form-item>
      <el-tabs v-model="tab"><el-tab-pane label="正文编辑" name="edit"><el-form-item label="正文" prop="content"><RichTextEditor v-model="form.content" :disabled="disabled || Boolean(compatibility)" @uploading-change="uploading = $event" @compatibility-change="compatibility = $event" /></el-form-item></el-tab-pane><el-tab-pane label="正文预览" name="preview"><SafePreview v-if="tab === 'preview'" :content="form.content" /></el-tab-pane></el-tabs>
      <el-alert v-if="article" :title="`当前版本 ${article.version}；版本冲突时保留输入，请核对后再操作。`" type="info" :closable="false" />
    </el-form>
    <template #footer><el-button :disabled="saving" @click="close">取消</el-button><el-button type="primary" :loading="saving" :disabled="disabled || uploading || Boolean(compatibility)" @click="save">{{ article ? '保存修改' : '保存草稿' }}</el-button></template>
  </el-dialog>
</template>

<style>
.article-editor-dialog .el-dialog__body{max-height:calc(100dvh - 220px);overflow-y:auto}.article-editor-dialog .el-select{width:100%}@media(max-width:600px){.article-editor-dialog{margin:3vh auto!important}.article-editor-dialog .el-dialog__body{max-height:calc(94dvh - 140px)}}
</style>
