<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { errorText, request, StaleResponse } from '../api';
import { states, type Article } from '../models';
const props = defineProps<{ action: string; articles: Article[] }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const labels: Record<string, string> = { submit: '提交审核', approve: '通过', reject: '退回', publish: '发布', offline: '下架', reopen: '重开编辑', delete: '删除', batch: '整批发布' };
const form = reactive({ comment: '' }), busy = ref(false), error = ref('');
async function submit() {
  if (busy.value) return;
  if (props.action === 'reject' && !form.comment.trim()) { error.value = '请填写退回原因。'; return; }
  if (props.action === 'batch' && (!props.articles.length || props.articles.length > 50)) { error.value = '每批选择 1～50 篇文章。'; return; }
  busy.value = true; error.value = '';
  const article = props.articles[0];
  try {
    if (props.action === 'batch') await request('articles/batch-publish', { method: 'POST', body: { items: props.articles.map(({ id, version }) => ({ id, version })) } });
    else await request(`articles/${article.id}${props.action === 'delete' ? '' : `/${props.action}`}`, props.action === 'delete'
      ? { method: 'DELETE', query: { version: article.version } } : { method: 'POST', body: { version: article.version, ...(['approve','reject'].includes(props.action) ? { comment: form.comment.trim() } : {}) } });
    emit('saved');
  } catch (e) { if (!(e instanceof StaleResponse)) error.value = errorText(e); } finally { busy.value = false; }
}
</script>
<template>
  <el-dialog :model-value="true" :title="`${labels[action]}文章`" width="560px" :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy" @close="emit('close')">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="page-alert" />
    <el-alert v-if="action === 'batch'" :title="`本次发布 ${articles.length} 篇文章；任一失败，整批状态与历史回滚。`" type="warning" :closable="false" class="page-alert" />
    <el-alert v-if="action === 'offline'" title="下架后文章将立即停止公开。" type="warning" :closable="false" class="page-alert" />
    <el-alert v-if="action === 'delete'" title="文章将被逻辑删除，状态历史保留。" type="warning" :closable="false" class="page-alert" />
    <el-table v-if="action === 'batch'" :data="articles" border :max-height="300"><el-table-column prop="id" label="文章 ID" width="110" /><el-table-column prop="title" label="待发布文章" min-width="220" /><el-table-column prop="version" label="版本" width="80" /></el-table>
    <p v-else v-for="article in articles" :key="article.id" class="article-title">{{ article.title }} <el-tag size="small" type="info">{{ states[article.status] }} · v{{ article.version }}</el-tag></p>
    <el-form v-if="['approve','reject'].includes(action)" :model="form" label-position="top"><el-form-item :label="action === 'reject' ? '退回原因（必填）' : '审核意见（可选）'" :required="action === 'reject'"><el-input v-model="form.comment" name="comment" type="textarea" :rows="3" :maxlength="500" show-word-limit /></el-form-item></el-form>
    <template #footer><el-button :disabled="busy" @click="emit('close')">取消</el-button><el-button type="primary" :loading="busy" @click="submit">确认{{ labels[action] }}</el-button></template>
  </el-dialog>
</template>
