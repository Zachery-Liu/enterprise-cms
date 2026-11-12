<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import Quill, { Delta } from 'quill';
import { ElAlert, ElButton, ElInput } from 'element-plus';
import { currentUser, errorText, StaleResponse } from '../api';
import { CONTENT_LIMIT, cleanHtml, contentSignature, safeImageUrl, semanticHtml } from '../content/html';
import { uploadImage } from '../content/uploads';
import 'quill/dist/quill.snow.css';
const props = defineProps<{ modelValue: string; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [html: string]; 'uploading-change': [busy: boolean]; 'compatibility-change': [message: string] }>();
const host = ref<HTMLElement>(), toolbar = ref<HTMLElement>(), input = ref<HTMLInputElement>();
const uploading = ref(false), uploadError = ref(''), alt = ref(''), selectedImage = ref<number | null>(null), count = ref(0), htmlLength = ref(0);
const locked = computed(() => props.disabled || uploading.value);
let quill: Quill | undefined, alive = true, controller: AbortController | undefined, lastFile: File | undefined, lastHtml = props.modelValue;
let range = { index: 0, length: 0 }, change: (() => void) | undefined;
const selectionChanged = (value: { index: number; length: number } | null) => { if (value) range = { ...value }; };
function output() {
  if (!quill) return;
  lastHtml = semanticHtml(quill.getSemanticHTML());
  count.value = Array.from(quill.getText().replace(/\n$/, '')).length; htmlLength.value = lastHtml.length;
  emit('update:modelValue', lastHtml);
}
function load(html: string) {
  if (!quill) return;
  const safe = cleanHtml(html);
  quill.setContents(quill.clipboard.convert({ html: safe }), 'silent');
  const exported = semanticHtml(quill.getSemanticHTML());
  const incompatible = contentSignature(html) !== contentSignature(safe) || contentSignature(safe) !== contentSignature(exported);
  emit('compatibility-change', incompatible ? '这篇文章包含当前编辑器无法完整保留的格式或内容，已阻止保存。请取消编辑，以免覆盖原文。' : '');
  count.value = Array.from(quill.getText().replace(/\n$/, '')).length; htmlLength.value = html.length;
  lastHtml = html; quill.history.clear();
}
function remember() { if (quill) range = quill.getSelection() || range; }
function chooseFile() { remember(); input.value?.click(); }
async function upload(file: File) {
  if (!quill || locked.value) return;
  const owner = currentUser.value?.userId; lastFile = file; uploadError.value = ''; uploading.value = true; emit('uploading-change', true);
  controller = new AbortController(); const signal = controller.signal;
  try {
    const image = await uploadImage(file, { signal });
    if (!alive || signal.aborted || currentUser.value?.userId !== owner) return;
    const index = Math.min(range.index, quill.getLength() - 1);
    quill.history.cutoff();
    quill.updateContents(new Delta().retain(index).insert({ image: image.url }, { alt: alt.value.trim().slice(0, 500) }), 'api');
    quill.setSelection(index + 1, 0, 'silent'); quill.history.cutoff(); output(); lastFile = undefined;
  } catch (e) { if (alive && !(e instanceof StaleResponse) && !(e instanceof DOMException && e.name === 'AbortError')) uploadError.value = errorText(e); }
  finally { if (alive) { uploading.value = false; emit('uploading-change', false); } }
}
function fileChanged(event: Event) { const target = event.target as HTMLInputElement; const file = target.files?.[0]; target.value = ''; if (file) void upload(file); }
function cancelUpload() { controller?.abort(); }
function retry() { if (lastFile) void upload(lastFile); }
function clipboard(event: ClipboardEvent) {
  if (locked.value || !quill) { event.preventDefault(); return; }
  const files = Array.from(event.clipboardData?.files || []);
  if (files.length) { event.preventDefault(); event.stopImmediatePropagation(); remember(); void upload(files[0]); return; }
  const html = event.clipboardData?.getData('text/html');
  if (html) {
    event.preventDefault(); event.stopImmediatePropagation(); remember();
    const delta = quill.clipboard.convert({ html: cleanHtml(html) });
    quill.updateContents(new Delta().retain(range.index).delete(range.length).concat(delta), 'user');
    quill.setSelection(range.index + delta.length(), 0, 'silent');
    if (/<img/i.test(html)) uploadError.value = '外部或 Base64 图片不会保留，请使用图片上传。';
  }
}
function dropped(event: DragEvent) {
  if (!event.dataTransfer?.files.length) return;
  event.preventDefault(); event.stopImmediatePropagation(); if (locked.value) return;
  remember(); void upload(event.dataTransfer.files[0]);
}
function imageClicked(event: MouseEvent) {
  if (!quill) return;
  if (event.target instanceof HTMLImageElement) {
    const blot = Quill.find(event.target);
    if (blot) { selectedImage.value = quill.getIndex(blot as Parameters<Quill['getIndex']>[0]); alt.value = event.target.alt; }
  } else selectedImage.value = null;
}
function updateAlt() {
  if (!quill || selectedImage.value === null) return;
  const op = quill.getContents(selectedImage.value, 1).ops[0];
  if (!op || typeof op.insert !== 'object' || !op.insert.image) { selectedImage.value = null; return; }
  quill.formatText(selectedImage.value, 1, 'alt', alt.value.trim().slice(0, 500), 'user');
}
onMounted(() => {
  quill = new Quill(host.value!, {
    theme: 'snow', placeholder: '开始撰写正文…',
    formats: ['header','bold','italic','underline','list','indent','blockquote','code','code-block','link','image'],
    modules: { toolbar: toolbar.value!, history: { delay: 800, maxStack: 100, userOnly: false }, uploader: { mimetypes: [], handler: () => {} } }
  });
  quill.clipboard.addMatcher('IMG', (node, delta) => safeImageUrl((node as Element).getAttribute('src') || '') ? delta : new Delta());
  load(props.modelValue); quill.enable(!props.disabled);
  quill.root.setAttribute('aria-label', '文章正文'); quill.root.setAttribute('role', 'textbox'); quill.root.setAttribute('aria-multiline', 'true');
  change = () => { selectedImage.value = null; output(); }; quill.on('text-change', change);
  quill.on('selection-change', selectionChanged);
  quill.root.addEventListener('paste', clipboard, true); quill.root.addEventListener('drop', dropped, true); quill.root.addEventListener('click', imageClicked);
});
watch(() => props.modelValue, html => { if (html !== lastHtml) load(html); });
watch(locked, value => quill?.enable(!value));
onUnmounted(() => {
  alive = false; controller?.abort();
  if (quill) { if (change) quill.off('text-change', change); quill.off('selection-change', selectionChanged); quill.root.removeEventListener('paste', clipboard, true); quill.root.removeEventListener('drop', dropped, true); quill.root.removeEventListener('click', imageClicked); quill.disable(); }
  quill = undefined;
});
</script>
<template>
  <div class="rich-text-editor" :class="{ 'rich-text-disabled': locked }">
    <div ref="toolbar" class="rich-text-toolbar" role="toolbar" aria-label="正文格式工具">
      <span class="ql-formats"><select class="ql-header" aria-label="标题级别" :disabled="locked"><option value="">正文</option><option value="1">标题一</option><option value="2">标题二</option><option value="3">标题三</option></select></span>
      <span class="ql-formats"><button type="button" class="ql-bold" aria-label="加粗" title="加粗" :disabled="locked" /><button type="button" class="ql-italic" aria-label="斜体" title="斜体" :disabled="locked" /><button type="button" class="ql-underline" aria-label="下划线" title="下划线" :disabled="locked" /></span>
      <span class="ql-formats"><button type="button" class="ql-list" value="ordered" aria-label="有序列表" title="有序列表" :disabled="locked" /><button type="button" class="ql-list" value="bullet" aria-label="无序列表" title="无序列表" :disabled="locked" /><button type="button" class="ql-blockquote" aria-label="引用" title="引用" :disabled="locked" /><button type="button" class="ql-code-block" aria-label="代码块" title="代码块" :disabled="locked" /></span>
      <span class="ql-formats"><button type="button" class="ql-link" aria-label="链接" title="链接" :disabled="locked" /><button type="button" class="ql-clean" aria-label="清除格式" title="清除格式" :disabled="locked" /></span>
      <span class="rich-text-history"><el-button size="small" :disabled="locked" @click="quill?.history.undo()">撤销</el-button><el-button size="small" :disabled="locked" @click="quill?.history.redo()">重做</el-button></span>
    </div>
    <div ref="host" class="rich-text-host" />
    <div class="rich-text-status" :class="{ 'rich-text-overflow': htmlLength > CONTENT_LIMIT }"><span>{{ count }} 字</span><span>HTML {{ htmlLength }} / {{ CONTENT_LIMIT }} 字符</span></div>
    <div class="rich-text-images"><el-input v-model="alt" aria-label="图片说明" placeholder="图片说明（可选）" :maxlength="500" :disabled="locked" /><el-button :disabled="locked" @click="chooseFile">上传图片</el-button><el-button v-if="selectedImage !== null" :disabled="locked" @click="updateAlt">更新图片说明</el-button><input ref="input" type="file" accept="image/jpeg,image/png,image/webp" hidden @change="fileChanged" /></div>
    <p class="rich-text-hint">支持 JPEG、PNG、WebP，可连续上传多张图片，单张不超过 5 MiB。上传图片可通过公开链接访问。</p>
    <div v-if="uploading" class="rich-text-upload" role="status">正在上传图片… <el-button link @click="cancelUpload">取消上传</el-button></div>
    <div v-if="uploadError" class="rich-text-upload-error"><el-alert :title="uploadError" type="warning" :closable="false" /><el-button v-if="lastFile" :disabled="locked" @click="retry">重试上传</el-button></div>
  </div>
</template>
<style>
.rich-text-editor{width:100%;min-width:0}.rich-text-toolbar.ql-toolbar{border-color:#dce3eb;border-radius:6px 6px 0 0;display:flex;align-items:center;flex-wrap:wrap;gap:4px;background:#f8fafc}.rich-text-toolbar .ql-formats{margin-right:6px!important}.rich-text-history{display:inline-flex;gap:4px}.rich-text-history .el-button+.el-button{margin-left:0}.rich-text-host.ql-container{border-color:#dce3eb;border-radius:0 0 6px 6px;font:16px/1.85 "PingFang SC","Microsoft YaHei",sans-serif}.rich-text-host .ql-editor{min-height:300px;max-height:60vh;padding:18px}.rich-text-host .ql-editor img{max-width:100%;height:auto}.rich-text-host .ql-editor .ql-code-block-container{overflow-x:auto;white-space:pre}.rich-text-host .ql-editor p{margin-bottom:.7em}.rich-text-status{display:flex;justify-content:space-between;gap:8px;font-size:12px;color:#65748a;padding:6px 0}.rich-text-overflow{color:#bd3030}.rich-text-images{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}.rich-text-images .el-input{flex:1;min-width:160px}.rich-text-images .el-button+.el-button{margin-left:0}.rich-text-hint{font-size:12px;color:#65748a;line-height:1.6;margin:8px 0}.rich-text-upload-error{display:grid;justify-items:start;gap:8px}.rich-text-disabled .ql-toolbar{opacity:.6}.rich-text-upload{font-size:13px}@media(max-width:600px){.rich-text-host .ql-editor{min-height:240px;padding:12px}.rich-text-status{flex-wrap:wrap}}
</style>
