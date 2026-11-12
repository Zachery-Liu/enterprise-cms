/** The shared wire format is semantic HTML, never Quill's editing DOM. */
export const CONTENT_LIMIT = 100000;
const tags = new Set(['p','br','h1','h2','h3','h4','h5','h6','strong','b','em','i','u','ul','ol','li','blockquote','code','pre','a','img']);
export function mediaBase(): URL {
  return import.meta.env.DEV ? new URL('/media/images/', location.origin) : new URL('../media/images/', location.href);
}
export function safeImageUrl(value: string): string | null {
  try {
    const url = new URL(value, location.href), base = mediaBase();
    const id = url.pathname.slice(base.pathname.length);
    if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname) || !/^[a-zA-Z0-9_-]+$/.test(id) || url.search || url.hash || url.username || url.password) return null;
    return url.pathname;
  } catch { return null; }
}
export function safeLink(value: string): string | null {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null; } catch { return null; }
}
export function cleanHtml(html: string): string {
  const source = new DOMParser().parseFromString(html, 'text/html');
  const output = document.createElement('div');
  function copy(node: Node, parent: Node) {
    if (node.nodeType === Node.TEXT_NODE) { parent.appendChild(document.createTextNode(node.textContent || '')); return; }
    if (!(node instanceof Element)) return;
    const name = node.tagName.toLowerCase();
    if (['script','style','iframe','object','embed','svg','math','form','input','button'].includes(name)) return;
    if (!tags.has(name)) { node.childNodes.forEach(child => copy(child, parent)); return; }
    const el = document.createElement(name);
    if (name === 'img') {
      const src = safeImageUrl(node.getAttribute('src') || ''); if (!src) return;
      el.setAttribute('src', src); el.setAttribute('alt', (node.getAttribute('alt') || '').slice(0, 500));
      for (const attr of ['width','height']) { const value = node.getAttribute(attr); if (value && /^[1-9]\d{0,3}$/.test(value) && Number(value) <= 8192) el.setAttribute(attr, value); }
    }
    if (name === 'a') {
      const href = safeLink(node.getAttribute('href') || ''); if (href) el.setAttribute('href', href);
      el.setAttribute('rel', 'nofollow noopener noreferrer');
      if (node.hasAttribute('title')) el.setAttribute('title', node.getAttribute('title')!);
    }
    parent.appendChild(el); node.childNodes.forEach(child => copy(child, el));
  }
  source.body.childNodes.forEach(node => copy(node, output));
  return output.innerHTML;
}
export function visibleText(html: string): string {
  return new DOMParser().parseFromString(html, 'text/html').body.textContent || '';
}
export function hasVisibleText(html: string): boolean { return /[^\s\p{Cf}]/u.test(visibleText(html)); }
/** Ignore serialization differences, but reject silent loss of content/format on old articles. */
export function contentSignature(html: string): string {
  const root = new DOMParser().parseFromString(html, 'text/html').body;
  const counts: Record<string, number> = {};
  for (const el of root.querySelectorAll('*')) {
    let tag = el.tagName.toLowerCase();
    if (tag === 'b') tag = 'strong'; if (tag === 'i') tag = 'em';
    if (['p','br'].includes(tag)) continue;
    let key = tag;
    if (tag === 'a') key += ':' + (safeLink(el.getAttribute('href') || '') || '') + ':' + (el.getAttribute('title') || '');
    if (tag === 'img') key += ':' + (safeImageUrl(el.getAttribute('src') || '') || '') + ':' + (el.getAttribute('alt') || '') + ':' + (el.getAttribute('width') || '') + ':' + (el.getAttribute('height') || '');
    counts[key] = (counts[key] || 0) + 1;
  }
  return JSON.stringify([root.textContent?.replace(/\s+/g, ' ').trim(), Object.entries(counts).sort()]);
}
/** Convert Quill 2's semantic code markup into the server's pre/code format. */
export function semanticHtml(html: string): string {
  const root = new DOMParser().parseFromString(html, 'text/html').body;
  for (const container of root.querySelectorAll('.ql-code-block-container')) {
    const pre = document.createElement('pre'), code = document.createElement('code');
    code.textContent = Array.from(container.querySelectorAll('.ql-code-block')).map(line => line.textContent || '').join('\n');
    pre.append(code); container.replaceWith(pre);
  }
  for (const pre of root.querySelectorAll('pre')) {
    if (!pre.querySelector('code')) { const code = document.createElement('code'); code.textContent = (pre.textContent || '').replace(/^\n|\n$/g, ''); pre.replaceChildren(code); }
  }
  return cleanHtml(root.innerHTML);
}
