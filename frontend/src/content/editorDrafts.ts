import type { Detail } from '../models';
export interface EditorForm { title: string; summary: string; content: string; categoryId: number | undefined }
interface Draft { article?: Detail; form: EditorForm }
// Memory only. No browser storage, and a draft is never offered to another account.
const drafts = new Map<number, Draft>();
export const readDraft = (owner?: number) => owner === undefined ? undefined : drafts.get(owner);
export function keepDraft(owner: number | undefined, article: Detail | undefined, form: EditorForm) {
  if (owner !== undefined) drafts.set(owner, { article: article ? { ...article } : undefined, form: { ...form } });
}
export function clearDraft(owner?: number) { if (owner !== undefined) drafts.delete(owner); }
