import { uploadImage as sendImage } from '../api';
export async function validateImage(file: File): Promise<void> {
  if (!['image/jpeg','image/png','image/webp'].includes(file.type)) throw new Error('请选择 JPEG、PNG 或 WebP 图片。');
  if (!file.size || file.size > 5 * 1024 * 1024) throw new Error('图片大小须大于 0 且不超过 5 MiB。');
  const bitmap = await createImageBitmap(file).catch(() => { throw new Error('无法读取图片，请选择有效的图片文件。'); });
  try { if (bitmap.width > 8192 || bitmap.height > 8192 || bitmap.width * bitmap.height > 20000000) throw new Error('图片宽高不能超过 8192px，且总像素不能超过 2000 万。'); }
  finally { bitmap.close(); }
}
export async function uploadImage(file: File, options?: { signal?: AbortSignal }) {
  options?.signal?.throwIfAborted();
  await validateImage(file);
  return sendImage(file, options);
}
