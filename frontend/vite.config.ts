import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';
import { relative } from 'node:path';

const workspace = fileURLToPath(new URL('../', import.meta.url));
const outDir = fileURLToPath(new URL('../src/main/webapp/content', import.meta.url));
// This directory contains generated content assets only, never application source.
if (relative(workspace, outDir).replaceAll('\\', '/') !== 'src/main/webapp/content') throw new Error('Unexpected frontend output directory');
export default defineConfig({
  plugins: [vue()], base: './',
  build: { outDir, emptyOutDir: true, license: { fileName: 'THIRD_PARTY_LICENSES.md' }, rolldownOptions: { input: {
    index: fileURLToPath(new URL('index.html', import.meta.url)),
    public: fileURLToPath(new URL('public.html', import.meta.url))
  } } },
  server: { proxy: { '/api': { target: 'http://127.0.0.1:8080', rewrite: path => `/cms${path}`, cookiePathRewrite: '/' } } }
});
