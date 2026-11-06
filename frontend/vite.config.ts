import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath } from 'node:url';
import { relative } from 'node:path';

const workspace = fileURLToPath(new URL('../', import.meta.url));
const outDir = fileURLToPath(new URL('../src/main/webapp/content', import.meta.url));
// This directory contains generated content assets only, never application source.
if (relative(workspace, outDir).replaceAll('\\', '/') !== 'src/main/webapp/content') throw new Error('Unexpected frontend output directory');
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_CMS_');
  const context = env.VITE_CMS_CONTEXT ?? '/cms';
  if (!/^\/[A-Za-z0-9_-]+$/.test(context)) throw new Error('VITE_CMS_CONTEXT must be a single application context such as /cms');
  const target = env.VITE_CMS_TARGET || 'http://127.0.0.1:8080';
  if (!/^https?:$/.test(new URL(target).protocol)) throw new Error('VITE_CMS_TARGET must use HTTP(S)');
  return {
  plugins: [vue()], base: './',
  build: { outDir, emptyOutDir: true, license: { fileName: 'THIRD_PARTY_LICENSES.md' }, rolldownOptions: { input: {
    index: fileURLToPath(new URL('index.html', import.meta.url)),
    public: fileURLToPath(new URL('public.html', import.meta.url))
  } } },
  server: { proxy: {
    '/api': { target, rewrite: path => `${context}${path}`, cookiePathRewrite: '/' },
    '/media': { target, rewrite: path => `${context}${path}` },
    [`${context}/media`]: { target }
  } }
  };
});
