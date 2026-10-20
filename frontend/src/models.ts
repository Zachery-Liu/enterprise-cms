export type Status = 'DRAFT' | 'PENDING' | 'REJECTED' | 'APPROVED' | 'PUBLISHED' | 'OFFLINE';
export const states: Record<Status, string> = { DRAFT: '草稿', PENDING: '待审核', REJECTED: '已退回', APPROVED: '审核通过', PUBLISHED: '已发布', OFFLINE: '已下架' };
export const tagType = (status: Status) => ({ DRAFT: 'info', PENDING: 'warning', REJECTED: 'danger', APPROVED: 'success', PUBLISHED: 'success', OFFLINE: 'info' })[status] as 'info' | 'warning' | 'danger' | 'success';
export interface User { userId: number; username: string; displayName: string; permissionCodes: string[]; roleCodes: string[] }
export interface Page<T> { records: T[]; total: number; page: number; size: number }
export interface Category { id: number; parentId: number | null; name: string; status: 'ENABLED' | 'DISABLED'; version: number; sortOrder: number; children: Category[] }
export interface Article { id: number; title: string; summary: string | null; categoryId: number; categoryName: string; authorId: number; authorDisplayName: string; status: Status; version: number; createdAt: string; updatedAt: string; publishedAt: string | null; submittedAt?: string }
export interface Detail extends Article { content: string; reviewComment?: string | null; reviewerDisplayName?: string | null }
export interface PublicArticle { id: number; title: string; summary: string | null; categoryName: string; authorDisplayName: string; publishedAt: string }
export interface PublicDetail extends PublicArticle { content: string }
export interface History { id: number; action: string; fromStatus: Status | null; toStatus: Status; operatorDisplayName: string; reason: string | null; createdAt: string }
export interface Transition { id: number; version: number; status: Status }
export const displayDate = (date?: string | null) => date ? new Date(date).toLocaleString('zh-CN', { hour12: false }) : '—';
export function availableCategories(tree: Category[], exclude?: number): { value: number; label: string }[] {
  const result: { value: number; label: string }[] = [];
  const stack = [...tree].reverse().map(node => ({ node, prefix: '', available: true }));
  while (stack.length) {
    const { node, prefix, available } = stack.pop()!;
    const enabled = available && node.status === 'ENABLED';
    if (node.id === exclude) continue;
    if (enabled) result.push({ value: node.id, label: prefix + node.name });
    for (const child of [...node.children].reverse()) stack.push({ node: child, prefix: `${prefix}${node.name} / `, available: enabled });
  }
  return result;
}
