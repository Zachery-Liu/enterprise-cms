<script setup lang="ts">
import { ElIcon, ElMenu, ElMenuItem } from 'element-plus';
import { OfficeBuilding } from '@element-plus/icons-vue';
import { siteName, publicEntry, workbenchEntry } from '../content/site';
const home = () => publicEntry().href + '#public';
const archiveUrl = publicEntry();
archiveUrl.searchParams.set('view', 'all');
archiveUrl.hash = 'public';

function select(value: string) {
  location.href = value === 'home' ? home() : archiveUrl.href;
}
</script>
<template>
  <div class="public-site">
    <header class="portal-header portal-shell"><a class="portal-brand" :href="home()"><el-icon class="portal-brand-mark"><OfficeBuilding /></el-icon><span>{{ siteName }}<small>ENTERPRISE PORTAL</small></span></a><el-menu class="portal-menu" mode="horizontal" :ellipsis="false" aria-label="网站导航" @select="select"><el-menu-item index="home">首页</el-menu-item><el-menu-item index="portal-news">新闻资讯</el-menu-item></el-menu></header>
    <main class="public-site-main"><slot /></main>
    <footer class="portal-footer"><div class="portal-shell portal-footer-inner"><div><span class="portal-footer-brand">{{ siteName }}</span><p>信息发布 · 栏目浏览 · 资讯查询</p></div><a class="portal-admin-link" :href="workbenchEntry()">管理后台</a></div></footer>
  </div>
</template>
<style>
.public-site{--portal-ink:#182b45;--portal-muted:#6c7b8f;--portal-line:#e6ebf2;--el-color-primary:#155bc0;--el-color-primary-light-3:#5b8dd3;--el-color-primary-light-5:#8aaddf;--el-color-primary-light-7:#b8ceec;--el-color-primary-light-9:#eef4fc;--el-color-primary-dark-2:#11499a;--el-border-radius-base:4px;min-height:100vh;background:#fff;color:var(--portal-ink);display:flex;flex-direction:column;font-family:"PingFang SC","Microsoft YaHei",system-ui,sans-serif}
.public-site a{color:inherit;text-decoration:none}.public-site a:hover{color:var(--el-color-primary)}.public-site a:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:4px}.portal-shell{width:min(1160px,calc(100% - 80px));margin-inline:auto}.portal-header{display:flex;align-items:center;justify-content:space-between;gap:32px;min-height:94px}.portal-brand{display:flex;align-items:center;gap:12px;flex-shrink:0;font-size:25px;font-weight:650;letter-spacing:.02em}.portal-brand small{display:block;font-size:9px;font-weight:400;letter-spacing:.16em;margin-top:4px;color:#8390a1}.portal-brand-mark.el-icon{font-size:31px;color:#fff;background:#155bc0;width:48px;height:48px;border-radius:7px}.portal-menu.el-menu{border-bottom:0;--el-menu-horizontal-height:94px;min-width:0}.portal-menu .el-menu-item{padding-inline:24px;font-size:14px}.public-site-main{flex:1;min-width:0}.portal-footer{background:#122842;color:#fff}.portal-footer-inner{display:flex;justify-content:space-between;gap:24px;padding-block:36px}.portal-footer-brand{font-size:22px;font-weight:600}.portal-footer p{color:#a1b1c4;font-size:12px;margin:12px 0 0}.portal-footer .portal-admin-link{align-self:center;font-size:12px;color:#c1ccda}@media(max-width:760px){.portal-shell{width:calc(100% - 40px)}.portal-header{gap:0;align-items:start;flex-direction:column;padding-top:18px}.portal-brand{font-size:23px}.portal-brand-mark.el-icon{width:42px;height:42px;font-size:27px}.portal-menu.el-menu{width:100%;--el-menu-horizontal-height:52px;margin-top:10px}.portal-menu .el-menu-item{padding-inline:20px;flex:1;justify-content:center}.portal-footer-inner{flex-direction:column;padding-block:28px}.portal-footer .portal-admin-link{align-self:flex-start}}
</style>
