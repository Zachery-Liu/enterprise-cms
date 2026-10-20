<script setup lang="ts">
import { computed } from 'vue';
const props = defineProps<{ content: string }>();
const document = computed(() => '<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">'
  + '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; form-action \'none\'">'
  + '<style>body{font:16px/1.8 "Microsoft YaHei",sans-serif;padding:16px;overflow-wrap:anywhere;color:#303133}</style></head><body>' + props.content + '</body></html>');
</script>
<template><iframe class="preview-frame" title="正文预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="document" /></template>
