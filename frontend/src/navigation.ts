/** Shared route contract. Only fixed internal backend destinations may be restored after login. */
export const siteName = '内容中心';
export type BackendRoute = 'articles' | 'categories' | 'review';
export function backendRoute(value: string | null | undefined): BackendRoute | null {
  return value === 'articles' || value === 'categories' || value === 'review' ? value : null;
}
export function publicRoute(value: string): string | null {
  if (value === 'public') return value;
  const match = /^public\/([1-9]\d*)$/.exec(value);
  return match && Number.isSafeInteger(Number(match[1])) ? value : null;
}
export function publicEntry(href: string, route = 'public'): string {
  const current = new URL(href), target = new URL('public.html', current);
  // Copy public list state only; never forward credentials or login return parameters.
  for (const key of ['title', 'categoryId', 'page']) {
    const value = current.searchParams.get(key);
    if (value !== null) target.searchParams.set(key, value);
  }
  target.hash = publicRoute(route) || 'public';
  return target.href;
}
