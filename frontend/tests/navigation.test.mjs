import { test } from 'node:test';
import assert from 'node:assert/strict';
import { backendRoute, publicRoute, publicEntry } from '../src/navigation.ts';
test('login destinations are fixed internal routes', () => {
  for (const route of ['articles', 'categories', 'review']) assert.equal(backendRoute(route), route);
  for (const route of [null, '', 'https://example.com', '//example.com', 'articles?next=x', '../review', 'login']) assert.equal(backendRoute(route), null);
});
test('public details require a positive safe numeric ID', () => {
  assert.equal(publicRoute('public/12'), 'public/12');
  for (const route of ['public/0', 'public/-1', 'public/1/extra', 'public/9007199254740992', 'publicity']) assert.equal(publicRoute(route), null);
});
test('legacy public link retains list filters and context but drops private query parameters', () => {
  const result = new URL(publicEntry('https://example.com/another/content/index.html?title=中文&categoryId=3&page=4&returnTo=review&token=private#public/12', 'public/12'));
  assert.equal(result.pathname, '/another/content/public.html');
  assert.equal(result.hash, '#public/12');
  assert.equal(result.searchParams.get('title'), '中文');
  assert.equal(result.searchParams.get('page'), '4');
  assert.equal(result.searchParams.get('categoryId'), '3');
  assert.equal(result.searchParams.has('token'), false);
  assert.equal(result.searchParams.has('returnTo'), false);
});
