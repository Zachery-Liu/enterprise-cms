<script setup lang="ts">
import { computed } from 'vue';
import articleContentCss from '../styles/article-content.css?inline';
import { cleanHtml, mediaBase } from '../content/html';
const props = defineProps<{ content: string }>();
const previewDocument = computed(() => {
  const root = new DOMParser().parseFromString(cleanHtml(props.content), 'text/html');
  // srcdoc runs with an opaque sandbox origin: use absolute URLs and a path-bound CSP.
  for (const img of root.querySelectorAll('img[src]')) img.setAttribute('src', new URL(img.getAttribute('src')!, location.origin).href);
  return '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">'
    + `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${mediaBase().href}; style-src 'unsafe-inline'; form-action 'none'; base-uri 'none'">`
    + '<style>body{margin:0;padding:16px;background:#fff}' + articleContentCss + '</style></head><body><div class="cms-article-content">' + root.body.innerHTML + '</div></body></html>';
});
</script>
<template><iframe class="preview-frame" title="正文预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="previewDocument" /></template>
