import { useEffect, useMemo, useRef, useState } from 'react';
import { Columns2, Eye, ImagePlus, Link2, Plus, Save, Send, Table2, Trash2, X } from 'lucide-react';
import WikiMarkdown from '../components/WikiMarkdown';
import { api, sha256, uploadToSignedUrl } from '../lib/api';

const emptyForm = {
    id: null,
    slug: '',
    categoryId: '',
    locale: 'en',
    title: '',
    summary: '',
    contentMarkdown: '',
    properties: {},
    changeNote: '',
    relations: [],
    assets: [],
    expectedLockVersion: null,
    revisionId: null
};

export default function WikiManager() {
    const [pages, setPages] = useState([]);
    const [categories, setCategories] = useState([]);
    const [form, setForm] = useState(emptyForm);
    const [revisions, setRevisions] = useState([]);
    const [filter, setFilter] = useState('');
    const [message, setMessage] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [tableBuilderOpen, setTableBuilderOpen] = useState(false);
    const [tableDraft, setTableDraft] = useState(createTableDraft);
    const [tableEditRange, setTableEditRange] = useState(null);
    const markdownRef = useRef(null);

    async function loadIndex() {
        const [pageData, categoryData] = await Promise.all([
            api('/admin/wiki/pages', { auth: true }),
            api('/wiki/categories', { auth: true })
        ]);
        setPages(pageData);
        setCategories(categoryData);
    }

    useEffect(() => {
        loadIndex().catch(requestError => setError(requestError.message));
    }, []);

    async function openPage(id) {
        setBusy(true);
        setError('');
        try {
            const [page, history] = await Promise.all([
                api(`/admin/wiki/pages/${id}`, { auth: true }),
                api(`/admin/wiki/pages/${id}/revisions`, { auth: true })
            ]);
            setForm({
                id: page.id,
                slug: page.slug,
                categoryId: page.categoryId ?? '',
                locale: page.locale,
                title: page.title,
                summary: page.summary ?? '',
                contentMarkdown: page.contentMarkdown,
                properties: page.properties ?? {},
                changeNote: '',
                relations: page.relations.map(item => ({
                    targetPageId: item.targetPageId,
                    relationType: item.relationType,
                    label: item.label ?? '',
                    sortOrder: item.sortOrder,
                    metadata: item.metadata ?? {}
                })),
                assets: page.assets.map(item => ({
                    assetId: item.id,
                    usage: item.usage ?? (item.kind === 'WIKI_IMAGE' ? 'INLINE' : 'ATTACHMENT'),
                    caption: item.caption ?? '',
                    sortOrder: item.sortOrder,
                    fileName: item.fileName,
                    kind: item.kind,
                    url: item.url,
                    altText: item.altText ?? item.fileName
                })),
                expectedLockVersion: page.lockVersion,
                revisionId: page.revisionId
            });
            setRevisions(history);
            setTableBuilderOpen(false);
            setTableEditRange(null);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    function newPage() {
        setForm(emptyForm);
        setRevisions([]);
        setTableBuilderOpen(false);
        setTableEditRange(null);
        setMessage('');
        setError('');
    }

    function updateTitle(title) {
        setForm(current => {
            const generatedFromCurrentTitle = slugify(current.title);
            const updateSlug = !current.id && (!current.slug || current.slug === generatedFromCurrentTitle);
            return { ...current, title, slug: updateSlug ? slugify(title) : current.slug };
        });
    }

    async function save(event) {
        event.preventDefault();
        setBusy(true);
        setError('');
        setMessage('');
        try {
            const body = {
                slug: form.slug,
                categoryId: form.categoryId || null,
                locale: form.locale,
                title: form.title,
                summary: form.summary || null,
                contentMarkdown: form.contentMarkdown,
                properties: form.properties,
                changeNote: form.changeNote || null,
                relations: form.relations,
                assets: form.assets.map(({ assetId, usage, caption, sortOrder }) => ({ assetId, usage, caption, sortOrder })),
                expectedLockVersion: form.id ? form.expectedLockVersion : null
            };
            const saved = await api(form.id ? `/admin/wiki/pages/${form.id}` : '/admin/wiki/pages', {
                method: form.id ? 'PUT' : 'POST', auth: true, body: JSON.stringify(body)
            });
            await loadIndex();
            await openPage(saved.id);
            setMessage(`Revision ${saved.revisionNumber} saved.`);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function publish(revisionId = form.revisionId) {
        if (!form.id || !revisionId) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/wiki/pages/${form.id}/publish/${revisionId}`, { method: 'POST', auth: true });
            await loadIndex();
            await openPage(form.id);
            setMessage('Article published.');
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function deleteArticle() {
        if (!form.id) return;
        const publicWarning = pages.find(page => page.id === form.id)?.status === 'PUBLISHED'
            ? ' This article is currently public and its URL will stop working immediately.'
            : '';
        if (!window.confirm(`Permanently delete “${form.title}”, every revision and its unshared attachments?${publicWarning} This cannot be undone.`)) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/wiki/pages/${form.id}`, { method: 'DELETE', auth: true });
            const deletedTitle = form.title;
            setForm(emptyForm);
            setRevisions([]);
            setTableBuilderOpen(false);
            setTableEditRange(null);
            await loadIndex();
            setMessage(`Article ${deletedTitle} was permanently deleted.`);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function uploadAsset(event) {
        const file = event.target.files?.[0];
        if (!file) return;
        setBusy(true);
        setError('');
        try {
            const checksum = await sha256(file);
            const image = file.type.startsWith('image/');
            const ticket = await api('/admin/wiki/assets/initiate', {
                method: 'POST',
                auth: true,
                body: JSON.stringify({
                    kind: image ? 'WIKI_IMAGE' : 'WIKI_FILE',
                    fileName: file.name,
                    contentType: file.type || 'application/octet-stream',
                    sizeBytes: file.size,
                    sha256: checksum,
                    altText: image ? file.name.replace(/\.[^.]+$/, '') : null
                })
            });
            await uploadToSignedUrl(ticket, file);
            const asset = await api(`/admin/wiki/assets/${ticket.assetId}/complete`, { method: 'POST', auth: true });
            const attached = {
                assetId: asset.id,
                usage: image ? 'INLINE' : 'ATTACHMENT',
                caption: '',
                sortOrder: form.assets.length,
                fileName: asset.fileName,
                kind: asset.kind,
                url: asset.url,
                altText: asset.altText ?? asset.fileName
            };
            setForm(current => ({ ...current, assets: [...current.assets, attached] }));
            if (image) insertMarkdown(`![${asset.altText || asset.fileName}](${asset.url})\n`);
            setMessage(`${file.name} attached.`);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
            event.target.value = '';
        }
    }

    function insertMarkdown(snippet, wrap = '') {
        const field = markdownRef.current;
        const start = field?.selectionStart ?? form.contentMarkdown.length;
        const end = field?.selectionEnd ?? start;
        const selected = form.contentMarkdown.slice(start, end);
        const replacement = wrap ? `${snippet}${selected || 'tekst'}${wrap}` : snippet;
        setForm(current => ({
            ...current,
            contentMarkdown: current.contentMarkdown.slice(0, start) + replacement + current.contentMarkdown.slice(end)
        }));
        requestAnimationFrame(() => {
            field?.focus();
            field?.setSelectionRange(start + replacement.length, start + replacement.length);
        });
    }

    function insertMarkdownBlock(snippet) {
        const field = markdownRef.current;
        const start = field?.selectionStart ?? form.contentMarkdown.length;
        const end = field?.selectionEnd ?? start;
        const before = form.contentMarkdown.slice(0, start);
        const after = form.contentMarkdown.slice(end);
        const prefix = !before ? '' : before.endsWith('\n\n') ? '' : before.endsWith('\n') ? '\n' : '\n\n';
        const suffix = !after ? '\n' : after.startsWith('\n\n') ? '' : after.startsWith('\n') ? '\n' : '\n\n';
        const replacement = `${prefix}${snippet.trim()}${suffix}`;

        setForm(current => ({
            ...current,
            contentMarkdown: current.contentMarkdown.slice(0, start) + replacement + current.contentMarkdown.slice(end)
        }));
        requestAnimationFrame(() => {
            field?.focus();
            field?.setSelectionRange(start + replacement.length, start + replacement.length);
        });
    }

    function openTableBuilder() {
        const cursor = markdownRef.current?.selectionStart ?? form.contentMarkdown.length;
        const existing = findAdvancedTable(form.contentMarkdown, cursor);
        setTableDraft(existing?.definition ?? createTableDraft());
        setTableEditRange(existing ? { start: existing.start, end: existing.end } : null);
        setTableBuilderOpen(true);
    }

    function saveTable() {
        const normalized = normalizeTableDraft(tableDraft);
        const snippet = `:::table\n${JSON.stringify(normalized, null, 2)}\n:::endtable`;
        if (!tableEditRange) {
            insertMarkdownBlock(snippet);
        } else {
            const { start, end } = tableEditRange;
            setForm(current => ({
                ...current,
                contentMarkdown: current.contentMarkdown.slice(0, start) + snippet + current.contentMarkdown.slice(end)
            }));
            requestAnimationFrame(() => {
                markdownRef.current?.focus();
                markdownRef.current?.setSelectionRange(start + snippet.length, start + snippet.length);
            });
        }
        setTableBuilderOpen(false);
        setTableEditRange(null);
    }

    function updateTableCell(rowIndex, cellIndex, key, value) {
        setTableDraft(current => ({
            ...current,
            rows: current.rows.map((row, currentRowIndex) => currentRowIndex !== rowIndex ? row : {
                ...row,
                cells: row.cells.map((cell, currentCellIndex) => currentCellIndex === cellIndex
                    ? { ...cell, [key]: value }
                    : cell)
            })
        }));
    }

    function addTableRow() {
        setTableDraft(current => ({ ...current, rows: [...current.rows, { cells: [createTableCell()] }] }));
    }

    function removeTableRow(rowIndex) {
        setTableDraft(current => current.rows.length === 1 ? current : {
            ...current,
            rows: current.rows.filter((_, index) => index !== rowIndex)
        });
    }

    function addTableCell(rowIndex) {
        setTableDraft(current => ({
            ...current,
            rows: current.rows.map((row, index) => index === rowIndex && row.cells.length < 20
                ? { ...row, cells: [...row.cells, createTableCell()] }
                : row)
        }));
    }

    function removeTableCell(rowIndex, cellIndex) {
        setTableDraft(current => ({
            ...current,
            rows: current.rows.map((row, index) => index === rowIndex && row.cells.length > 1
                ? { ...row, cells: row.cells.filter((_, currentCellIndex) => currentCellIndex !== cellIndex) }
                : row)
        }));
    }

    function insertImageIntoCell(rowIndex, cellIndex, assetId) {
        const asset = form.assets.find(item => item.assetId === assetId && item.kind === 'WIKI_IMAGE');
        if (!asset) return;
        const imageMarkdown = `![${asset.altText || asset.fileName}](${asset.url})`;
        const currentContent = tableDraft.rows[rowIndex].cells[cellIndex].content;
        updateTableCell(rowIndex, cellIndex, 'content', currentContent ? `${currentContent}\n\n${imageMarkdown}` : imageMarkdown);
    }

    function addRelation() {
        const target = pages.find(page => page.id !== form.id);
        if (!target) return;
        setForm(current => ({
            ...current,
            relations: [...current.relations, { targetPageId: target.id, relationType: 'RELATED_TO', label: '', sortOrder: current.relations.length, metadata: {} }]
        }));
    }

    const visiblePages = useMemo(() => {
        const needle = filter.trim().toLowerCase();
        return needle ? pages.filter(page => `${page.title} ${page.slug}`.toLowerCase().includes(needle)) : pages;
    }, [filter, pages]);
    const selectedPage = pages.find(page => page.id === form.id);

    return (
        <div className="manager-layout">
            <aside className="manager-index">
                <div className="manager-title-row"><h2>Articles</h2><button type="button" onClick={newPage}><Plus size={18} /> New article</button></div>
                <input className="compact-input" aria-label="Search articles" placeholder="Search articles..." value={filter} onChange={event => setFilter(event.target.value)} />
                <ul>
                    {visiblePages.map(page => (
                        <li key={page.id}>
                            <button type="button" aria-current={form.id === page.id ? 'page' : undefined} onClick={() => openPage(page.id)}>
                                <span>{page.title}</span><small>{statusLabel(page.status)}</small>
                            </button>
                        </li>
                    ))}
                </ul>
            </aside>

            <form className="wiki-editor" onSubmit={save}>
                <div className="editor-heading">
                    <div><span>{selectedPage ? statusLabel(selectedPage.status) : 'New article'}</span><h2>{form.title || 'Untitled article'}</h2></div>
                    <div className="editor-actions">
                        <button className="terminal-action" type="submit" disabled={busy}><Save size={17} /> Save revision</button>
                        {form.id && <button className="terminal-action" type="button" onClick={() => publish()} disabled={busy}><Send size={17} /> Publish</button>}
                        {form.id && <button type="button" onClick={deleteArticle} disabled={busy}><Trash2 size={17} /> Delete article</button>}
                    </div>
                </div>
                {message && <p className="terminal-alert" role="status">{message}</p>}
                {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}

                <div className="editor-fields editor-fields--grid">
                    <label className="terminal-field"><span>Title</span><input required maxLength="220" value={form.title} onChange={event => updateTitle(event.target.value)} /></label>
                    <label className="terminal-field"><span>Subcategory</span><select value={form.categoryId} onChange={event => setForm({ ...form, categoryId: event.target.value })}><option value="">Uncategorized</option>{categories.filter(category => !category.parentId).map(parent => <optgroup label={parent.name} key={parent.id}>{categories.filter(category => category.parentId === parent.id).map(category => <option value={category.id} key={category.id}>{category.name}</option>)}</optgroup>)}</select></label>
                </div>
                <label className="terminal-field"><span>Summary</span><textarea rows="3" value={form.summary} onChange={event => setForm({ ...form, summary: event.target.value })} /></label>
                <label className="editor-toggle">
                    <input
                        type="checkbox"
                        checked={form.properties?.featuredAsPatchNote === true}
                        onChange={event => setForm(current => ({
                            ...current,
                            properties: { ...current.properties, featuredAsPatchNote: event.target.checked }
                        }))}
                    />
                    <span><strong>Show as patch note</strong><small>After publication, this article appears in the separate Patch notes section on the homepage.</small></span>
                </label>
                <details className="form-options">
                    <summary>Article settings</summary>
                    <div className="editor-fields editor-fields--grid">
                        <label className="terminal-field"><span>URL</span><input required pattern="[a-z0-9]+(?:-[a-z0-9]+)*" value={form.slug} onChange={event => setForm({ ...form, slug: event.target.value.toLowerCase() })} /></label>
                        <label className="terminal-field"><span>Language</span><input required pattern="[a-z]{2}(?:-[A-Z]{2})?" value={form.locale} onChange={event => setForm({ ...form, locale: event.target.value })} /></label>
                        <label className="terminal-field"><span>Revision note</span><input maxLength="500" placeholder="Optional" value={form.changeNote} onChange={event => setForm({ ...form, changeNote: event.target.value })} /></label>
                    </div>
                </details>

                <section className="markdown-editor">
                    <div className="markdown-toolbar" aria-label="Formatting tools">
                        <strong>Article content</strong>
                        <button type="button" onClick={() => insertMarkdown('## ')}>H2</button>
                        <button type="button" onClick={() => insertMarkdown('**', '**')}>B</button>
                        <button type="button" onClick={() => insertMarkdown('- ')}>List</button>
                        <button type="button" onClick={() => insertMarkdown('[', '](https://)')}>Link</button>
                        <button
                            type="button"
                            aria-expanded={tableBuilderOpen}
                            aria-controls="wiki-table-builder"
                            onClick={openTableBuilder}
                        >
                            <Table2 size={16} /> Table
                        </button>
                        <button type="button" onClick={() => insertMarkdownBlock(':::columns\n:::left\nMain content\n:::right\nSide content\n:::end')}><Columns2 size={16} /> Columns</button>
                        <label className="upload-button"><ImagePlus size={16} /> Image / file<input type="file" onChange={uploadAsset} disabled={busy} /></label>
                    </div>
                    {tableBuilderOpen && (
                        <fieldset className="advanced-table-builder" id="wiki-table-builder">
                            <legend>{tableEditRange ? 'Edit table' : 'New table'}</legend>
                            <label className="terminal-field">
                                <span>Caption (optional)</span>
                                <input maxLength="500" value={tableDraft.caption} onChange={event => setTableDraft(current => ({ ...current, caption: event.target.value }))} />
                            </label>

                            <div className="advanced-table-rows">
                                {tableDraft.rows.map((row, rowIndex) => (
                                    <section className="advanced-table-row" key={`table-row-${rowIndex}`}>
                                        <div className="advanced-table-row-heading">
                                            <h4>Row {rowIndex + 1} / {row.cells.length} cell{row.cells.length === 1 ? '' : 's'}</h4>
                                            <div>
                                                <button type="button" onClick={() => addTableCell(rowIndex)} disabled={row.cells.length >= 20}><Plus size={15} /> Add cell</button>
                                                <button type="button" onClick={() => removeTableRow(rowIndex)} disabled={tableDraft.rows.length === 1}><X size={15} /> Remove row</button>
                                            </div>
                                        </div>
                                        <div className="advanced-table-cells">
                                            {row.cells.map((cell, cellIndex) => (
                                                <fieldset className="advanced-table-cell" key={`table-cell-${cellIndex}`}>
                                                    <legend>Cell {cellIndex + 1}</legend>
                                                    <label className="terminal-field">
                                                        <span>Cell content</span>
                                                        <textarea rows="4" value={cell.content} onChange={event => updateTableCell(rowIndex, cellIndex, 'content', event.target.value)} />
                                                    </label>
                                                    <div className="advanced-table-cell-options">
                                                        <label><input type="checkbox" checked={cell.header} onChange={event => updateTableCell(rowIndex, cellIndex, 'header', event.target.checked)} /> Header</label>
                                                        <label className="terminal-field"><span>Span columns</span><input type="number" min="1" max="12" value={cell.colSpan} onChange={event => updateTableCell(rowIndex, cellIndex, 'colSpan', event.target.value)} /></label>
                                                        <label className="terminal-field"><span>Span rows</span><input type="number" min="1" max="50" value={cell.rowSpan} onChange={event => updateTableCell(rowIndex, cellIndex, 'rowSpan', event.target.value)} /></label>
                                                        <label className="terminal-field"><span>Align</span><select value={cell.align} onChange={event => updateTableCell(rowIndex, cellIndex, 'align', event.target.value)}><option value="left">Left</option><option value="center">Center</option><option value="right">Right</option></select></label>
                                                    </div>
                                                    {form.assets.some(asset => asset.kind === 'WIKI_IMAGE') && (
                                                        <label className="terminal-field"><span>Insert attached image</span><select value="" onChange={event => insertImageIntoCell(rowIndex, cellIndex, event.target.value)}><option value="">Select image...</option>{form.assets.filter(asset => asset.kind === 'WIKI_IMAGE').map(asset => <option value={asset.assetId} key={asset.assetId}>{asset.fileName}</option>)}</select></label>
                                                    )}
                                                    <button className="advanced-table-remove-cell" type="button" onClick={() => removeTableCell(rowIndex, cellIndex)} disabled={row.cells.length === 1}><X size={15} /> Remove cell</button>
                                                </fieldset>
                                            ))}
                                        </div>
                                    </section>
                                ))}
                            </div>

                            <div className="advanced-table-builder-actions">
                                <button type="button" onClick={addTableRow} disabled={tableDraft.rows.length >= 50}><Plus size={16} /> Add row</button>
                                <button className="terminal-action" type="button" onClick={saveTable}><Table2 size={17} /> {tableEditRange ? 'Update table' : 'Insert table'}</button>
                                <button className="table-builder-cancel" type="button" onClick={() => { setTableBuilderOpen(false); setTableEditRange(null); }}>Cancel</button>
                            </div>
                        </fieldset>
                    )}
                    <div className="markdown-columns">
                        <textarea ref={markdownRef} required value={form.contentMarkdown} onChange={event => setForm({ ...form, contentMarkdown: event.target.value })} aria-label="Article content" />
                        <div className="markdown-preview"><span><Eye size={15} /> PREVIEW</span><WikiMarkdown>{form.contentMarkdown || '*The preview will appear here.*'}</WikiMarkdown></div>
                    </div>
                </section>

                <section className="relation-editor">
                    <div className="manager-title-row"><h3><Link2 size={17} /> Related articles</h3><button type="button" onClick={addRelation}><Plus size={16} /> Add link</button></div>
                    {form.relations.map((relation, index) => (
                        <div className="relation-row" key={`${relation.targetPageId}-${index}`}>
                            <select value={relation.targetPageId} onChange={event => updateRelation(index, 'targetPageId', event.target.value)}>{pages.filter(page => page.id !== form.id).map(page => <option value={page.id} key={page.id}>{page.title}</option>)}</select>
                            <input value={relation.label} onChange={event => updateRelation(index, 'label', event.target.value)} placeholder="Optional label" />
                            <button type="button" aria-label="Remove dependency" title="Remove dependency" onClick={() => setForm(current => ({ ...current, relations: current.relations.filter((_, itemIndex) => itemIndex !== index) }))}><X size={17} /></button>
                        </div>
                    ))}
                </section>

                {form.assets.length > 0 && (
                    <section className="attached-assets"><h3>Attached files</h3>{form.assets.map((asset, index) => <div key={asset.assetId}><span>{asset.fileName}</span><select aria-label={`Usage for ${asset.fileName}`} value={asset.usage} onChange={event => updateAsset(index, 'usage', event.target.value)}><option value="INLINE">Inline</option><option value="HERO">Cover</option><option value="GALLERY">Gallery</option><option value="ATTACHMENT">Download</option></select><button type="button" aria-label={`Remove ${asset.fileName}`} title={`Remove ${asset.fileName}`} onClick={() => setForm(current => ({ ...current, assets: current.assets.filter((_, itemIndex) => itemIndex !== index) }))}><X size={16} /></button></div>)}</section>
                )}

                {revisions.length > 0 && (
                    <section className="revision-history"><h3>Revision history</h3><ul>{revisions.map(revision => <li key={revision.id}><span>Revision {revision.revisionNumber} — {revision.changeNote || revision.title} {revision.published && '· Published'}</span><button type="button" onClick={() => publish(revision.id)} disabled={revision.published || busy}>Publish</button></li>)}</ul></section>
                )}
            </form>
        </div>
    );

    function updateRelation(index, key, value) {
        setForm(current => ({ ...current, relations: current.relations.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: value } : item) }));
    }

    function updateAsset(index, key, value) {
        setForm(current => ({ ...current, assets: current.assets.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: value } : item) }));
    }
}

function clampInteger(value, minimum, maximum) {
    const number = Number.parseInt(value, 10);
    if (!Number.isFinite(number)) return minimum;
    return Math.min(maximum, Math.max(minimum, number));
}

function slugify(value) {
    return value
        .toLowerCase()
        .replaceAll('ł', 'l')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
}

function statusLabel(status) {
    return ({ DRAFT: 'Draft', PUBLISHED: 'Published' })[status] || status;
}

function createTableCell(header = false, content = '') {
    return { content, header, colSpan: 1, rowSpan: 1, align: 'left' };
}

function createTableDraft() {
    return {
        caption: '',
        rows: [
            { cells: [createTableCell(true, 'Column 1'), createTableCell(true, 'Column 2')] },
            { cells: [createTableCell(false, 'Value'), createTableCell(false, 'Value')] }
        ]
    };
}

function normalizeTableDraft(value) {
    const rows = (Array.isArray(value?.rows) ? value.rows : []).slice(0, 50).map(row => ({
        cells: (Array.isArray(row?.cells) ? row.cells : []).slice(0, 20).map(cell => ({
            content: String(cell?.content ?? '').slice(0, 20_000),
            header: Boolean(cell?.header),
            colSpan: clampInteger(cell?.colSpan, 1, 12),
            rowSpan: clampInteger(cell?.rowSpan, 1, 50),
            align: ['left', 'center', 'right'].includes(cell?.align) ? cell.align : 'left'
        }))
    })).filter(row => row.cells.length);
    return { caption: String(value?.caption ?? '').slice(0, 500), rows: rows.length ? rows : createTableDraft().rows };
}

function findAdvancedTable(markdown, cursor) {
    const pattern = /^:::table[ \t]*\r?\n([\s\S]*?)\r?\n:::endtable[ \t]*$/gm;
    for (const match of markdown.matchAll(pattern)) {
        const start = match.index;
        const end = start + match[0].length;
        if (cursor < start || cursor > end) continue;
        try {
            return { start, end, definition: normalizeTableDraft(JSON.parse(match[1])) };
        } catch {
            return null;
        }
    }
    return null;
}
