/**
 * Rendu Markdown minimal et sûr pour les réponses de l'assistant : titres (#), gras (**x**), code (`x`), listes à
 * puces / numérotées et tableaux (| a | b |). Le texte est d'abord échappé (aucun HTML brut de la réponse n'est
 * jamais interprété), puis seules les balises ci-dessous sont produites par ce code lui-même.
 */

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function inline(s: string): string {
  return escapeHtml(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

const TABLE_ROW = /^\s*\|.*\|\s*$/;
const TABLE_SEPARATOR = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;
const BULLET = /^\s*[-*•]\s+(.*)$/;
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/;
const HEADING = /^\s*(#{1,4})\s+(.*)$/;

function cells(row: string): string[] {
  return row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim());
}

export function renderMarkdown(text: string | null | undefined): string {
  if (!text) return '';
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const out: string[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) { i++; continue; }

    const heading = HEADING.exec(line);
    if (heading) {
      const level = Math.min(heading[1].length + 2, 6);
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      i++;
      continue;
    }

    if (TABLE_ROW.test(line) && i + 1 < lines.length && TABLE_SEPARATOR.test(lines[i + 1])) {
      const head = cells(line);
      i += 2;
      const body: string[][] = [];
      while (i < lines.length && TABLE_ROW.test(lines[i])) { body.push(cells(lines[i])); i++; }
      out.push('<div class="md-table-wrap"><table><thead><tr>'
        + head.map(h => `<th>${inline(h)}</th>`).join('')
        + '</tr></thead><tbody>'
        + body.map(r => '<tr>' + r.map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('')
        + '</tbody></table></div>');
      continue;
    }

    if (BULLET.test(line) || NUMBERED.test(line)) {
      const ordered = NUMBERED.test(line) && !BULLET.test(line);
      const re = ordered ? NUMBERED : BULLET;
      const items: string[] = [];
      while (i < lines.length && re.test(lines[i])) {
        items.push(`<li>${inline(re.exec(lines[i])![1])}</li>`);
        i++;
      }
      out.push(ordered ? `<ol>${items.join('')}</ol>` : `<ul>${items.join('')}</ul>`);
      continue;
    }

    const para: string[] = [];
    while (i < lines.length && lines[i].trim() && !HEADING.test(lines[i]) && !BULLET.test(lines[i])
      && !NUMBERED.test(lines[i]) && !(TABLE_ROW.test(lines[i]) && i + 1 < lines.length && TABLE_SEPARATOR.test(lines[i + 1]))) {
      para.push(inline(lines[i]));
      i++;
    }
    out.push(`<p>${para.join('<br>')}</p>`);
  }
  return out.join('');
}
