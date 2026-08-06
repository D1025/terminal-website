import { useEffect, useMemo, useState } from 'react';
import { ArchiveRestore, FilePlus2, FolderUp, Pencil, RefreshCw, Rocket, RotateCcw, Trash2, UploadCloud, X } from 'lucide-react';
import { api, uploadToSignedUrl } from '../lib/api';

const emptyRelease = {
    version: '',
    channel: 'STABLE',
    minimumLauncherVersion: '',
    gameServerHost: 'server.fonline-nd.com',
    gameServerPort: '2238',
    releaseNotesMarkdown: ''
};

export default function UpdateManager() {
    const [releases, setReleases] = useState([]);
    const [selectedId, setSelectedId] = useState('');
    const [detail, setDetail] = useState(null);
    const [form, setForm] = useState(emptyRelease);
    const [targetDirectory, setTargetDirectory] = useState('');
    const [overwritePolicy, setOverwritePolicy] = useState('REPLACE');
    const [deletePath, setDeletePath] = useState('');
    const [createOpen, setCreateOpen] = useState(false);
    const [includeInherited, setIncludeInherited] = useState(false);
    const [filter, setFilter] = useState('');
    const [editingFile, setEditingFile] = useState(null);
    const [busy, setBusy] = useState(false);
    const [progress, setProgress] = useState('');
    const [error, setError] = useState('');

    async function load(preferredId = selectedId, inherited = includeInherited, query = filter) {
        const releaseData = await api('/admin/updates', { auth: true });
        setReleases(releaseData);
        const nextId = preferredId || releaseData[0]?.id || '';
        setSelectedId(nextId);
        if (!nextId) {
            setDetail(null);
            setCreateOpen(true);
            return;
        }
        const search = new URLSearchParams({ includeInherited: String(inherited), q: query });
        setDetail(await api(`/admin/updates/${nextId}?${search}`, { auth: true }));
    }

    useEffect(() => {
        load().catch(requestError => setError(requestError.message));
        // Initial load only; subsequent refreshes preserve explicit local filters.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    async function openRelease(id) {
        setBusy(true);
        setError('');
        setEditingFile(null);
        try {
            await load(id);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function createRelease(event) {
        event.preventDefault();
        setBusy(true);
        setError('');
        setProgress('Creating update draft...');
        try {
            const created = await api('/admin/updates', {
                method: 'POST', auth: true, body: JSON.stringify({
                    ...form,
                    minimumLauncherVersion: form.minimumLauncherVersion || null,
                    gameServerHost: form.gameServerHost || null,
                    gameServerPort: form.gameServerPort ? Number(form.gameServerPort) : null,
                    releaseNotesMarkdown: form.releaseNotesMarkdown || null
                })
            });
            setForm(emptyRelease);
            setCreateOpen(false);
            setProgress(`Draft ${created.release.version} created.`);
            await load(created.release.id);
        } catch (requestError) {
            setError(requestError.message);
            setProgress('');
        } finally {
            setBusy(false);
        }
    }

    async function uploadSelection(event, folderSelection) {
        const input = event.target;
        const files = Array.from(input.files ?? []);
        if (!files.length || !detail) return;
        setBusy(true);
        setError('');
        try {
            for (let index = 0; index < files.length; index += 1) {
                const file = files[index];
                const relativePath = selectedPath(file, folderSelection);
                const targetPath = joinTargetPath(targetDirectory, relativePath);
                setProgress(`Uploading ${index + 1}/${files.length}: ${targetPath}`);
                const ticket = await api(`/admin/updates/${detail.release.id}/files`, {
                    method: 'POST', auth: true, body: JSON.stringify({
                        targetPath,
                        contentType: file.type || 'application/octet-stream',
                        sizeBytes: file.size,
                        overwritePolicy
                    })
                });
                await uploadToSignedUrl(ticket, file);
                setProgress(`Finishing ${index + 1}/${files.length}: ${targetPath}`);
                await api(`/admin/updates/${detail.release.id}/files/${ticket.file.id}/complete`, {
                    method: 'POST', auth: true
                });
            }
            setProgress(`${files.length} file${files.length === 1 ? '' : 's'} added.`);
            await load(detail.release.id);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            input.value = '';
            setBusy(false);
        }
    }

    async function addDeletion(event) {
        event.preventDefault();
        if (!detail) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/updates/${detail.release.id}/deletions`, {
                method: 'POST', auth: true, body: JSON.stringify({ targetPath: deletePath })
            });
            setProgress(`${deletePath} will be removed by the launcher.`);
            setDeletePath('');
            await load(detail.release.id);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function revertFile(file) {
        const message = file.action === 'DELETE'
            ? `Undo deletion of ${file.path}? The previous version will be restored when available.`
            : `Remove ${file.path} from this draft? The base version will be restored when available.`;
        if (!window.confirm(message)) return;
        await mutate(`/admin/updates/${detail.release.id}/files/${file.id}`, 'DELETE',
            file.action === 'DELETE' ? 'The deletion was undone.' : 'The patch change was removed.');
    }

    async function markFileDeleted(file) {
        if (!window.confirm(`Delete ${file.path} from clients when this update is installed?`)) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/updates/${detail.release.id}/deletions`, {
                method: 'POST', auth: true, body: JSON.stringify({ targetPath: file.path })
            });
            setProgress(`${file.path} will be deleted from clients.`);
            setEditingFile(null);
            await load(detail.release.id);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    function beginFileEdit(file) {
        setEditingFile({
            id: file.id,
            action: file.action,
            targetPath: file.path,
            overwritePolicy: file.overwritePolicy
        });
    }

    async function saveFileEdit(event) {
        event.preventDefault();
        if (!editingFile || !detail) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/updates/${detail.release.id}/files/${editingFile.id}`, {
                method: 'PATCH', auth: true, body: JSON.stringify({
                    targetPath: editingFile.targetPath,
                    overwritePolicy: editingFile.action === 'UPSERT' ? editingFile.overwritePolicy : null
                })
            });
            setProgress(`${editingFile.targetPath} updated.`);
            setEditingFile(null);
            await load(detail.release.id);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function publish() {
        const release = detail?.release;
        if (!release) return;
        const rollback = release.status === 'RETIRED';
        const question = rollback
            ? `Activate ${release.version} again? It will replace the currently published ${release.channel} update.`
            : `Publish ${release.version} to the ${release.channel} channel? Launchers will see it immediately.`;
        if (!window.confirm(question)) return;
        await mutate(`/admin/updates/${release.id}/publish`, 'POST', rollback ? 'Rollback completed.' : 'Update published.');
    }

    async function discard() {
        const release = detail?.release;
        if (!release) return;
        const activeWarning = release.status === 'PUBLISHED'
            ? ` This is the active ${release.channel} update; launchers will have no update on this channel until another version is published.`
            : '';
        const dependencyWarning = ' Delete newer updates based on this version first.';
        if (!window.confirm(`Permanently delete update ${release.version} and all its files?${activeWarning}${dependencyWarning} This cannot be undone.`)) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/updates/${release.id}`, { method: 'DELETE', auth: true });
            setSelectedId('');
            setProgress(`Update ${release.version} and its files were deleted.`);
            await load('');
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function mutate(path, method, successMessage) {
        setBusy(true);
        setError('');
        try {
            await api(path, { method, auth: true });
            setProgress(successMessage);
            await load(detail.release.id);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function applyFileFilter(event) {
        event.preventDefault();
        if (!selectedId) return;
        setBusy(true);
        try {
            await load(selectedId, includeInherited, filter);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function toggleInherited(event) {
        const checked = event.target.checked;
        setIncludeInherited(checked);
        if (!selectedId) return;
        setBusy(true);
        try {
            await load(selectedId, checked, filter);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    const release = detail?.release;
    const editable = release && (release.status === 'DRAFT' || release.status === 'UPLOADING');
    const visibleFiles = useMemo(() => detail?.files ?? [], [detail]);

    return (
        <div className="simple-manager update-manager">
            <header><h2>Updates</h2></header>

            {progress && <p className="terminal-alert" role="status">{progress}</p>}
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}

            <details className="update-create" open={createOpen} onToggle={event => setCreateOpen(event.currentTarget.open)}>
                <summary><FilePlus2 size={17} /> New update</summary>
                <form onSubmit={createRelease}>
                    <div className="editor-fields update-release-fields">
                        <label className="terminal-field"><span>Version</span><input required placeholder="0.2.2" value={form.version} onChange={event => setForm({ ...form, version: event.target.value })} /></label>
                        <label className="terminal-field"><span>Channel</span><select value={form.channel} onChange={event => setForm({ ...form, channel: event.target.value })}><option>STABLE</option><option>TEST</option></select></label>
                    </div>
                    <label className="terminal-field"><span>Release notes</span><textarea rows="3" value={form.releaseNotesMarkdown} onChange={event => setForm({ ...form, releaseNotesMarkdown: event.target.value })} /></label>
                    <details className="form-options">
                        <summary>Connection settings</summary>
                        <div className="form-options-fields">
                            <label className="terminal-field"><span>Minimum launcher version</span><input placeholder="1.0.0" value={form.minimumLauncherVersion} onChange={event => setForm({ ...form, minimumLauncherVersion: event.target.value })} /></label>
                            <label className="terminal-field"><span>Game server address</span><input placeholder="server.fonline-nd.com" value={form.gameServerHost} onChange={event => setForm({ ...form, gameServerHost: event.target.value })} /></label>
                            <label className="terminal-field"><span>Game server port</span><input type="number" min="1" max="65535" value={form.gameServerPort} onChange={event => setForm({ ...form, gameServerPort: event.target.value })} /></label>
                        </div>
                    </details>
                    <button className="terminal-action" type="submit" disabled={busy}><RefreshCw size={17} /> Create draft</button>
                </form>
            </details>

            <div className="update-workspace">
                <aside className="update-release-index" aria-label="Update releases">
                    <h3>Versions</h3>
                    {releases.length ? releases.map(item => (
                        <button type="button" key={item.id} aria-current={selectedId === item.id ? 'page' : undefined} onClick={() => openRelease(item.id)} disabled={busy}>
                            <span>{item.version} / {item.channel}</span>
                            <small>{statusLabel(item.status)} · {item.changedCount} changes · {item.fileCount} files</small>
                        </button>
                    )) : <p>No updates yet.</p>}
                </aside>

                <section className="update-detail" aria-live="polite">
                    {!release ? <div className="module-empty">Choose or create an update.</div> : (
                        <>
                            <div className="update-detail-heading">
                                <div>
                                    <span className={`update-status update-status--${release.status.toLowerCase()}`}>{statusLabel(release.status)}</span>
                                    <h3>{release.version} / {release.channel}</h3>
                                    <p>Based on {release.baseVersion || 'an empty version'} · {release.changedCount} changes · {release.fileCount} files</p>
                                </div>
                                <div className="update-heading-actions">
                                    <button type="button" onClick={discard} disabled={busy}><Trash2 size={16} /> Delete version</button>
                                    {editable && <button className="terminal-action" type="button" onClick={publish} disabled={busy || release.pendingCount > 0 || release.changedCount === 0}><Rocket size={17} /> Publish</button>}
                                    {release.status === 'RETIRED' && <button className="terminal-action" type="button" onClick={publish} disabled={busy}><ArchiveRestore size={17} /> Roll back</button>}
                                </div>
                            </div>

                            {editable && (
                                <section className="update-edit-panel">
                                    <div className="update-upload-options">
                                        <label className="terminal-field"><span>Upload to folder (optional)</span><input placeholder="data" value={targetDirectory} onChange={event => setTargetDirectory(event.target.value)} /></label>
                                        <label className="terminal-field"><span>If file exists</span><select value={overwritePolicy} onChange={event => setOverwritePolicy(event.target.value)}><option value="REPLACE">Replace changed file</option><option value="PRESERVE">Keep existing file</option></select></label>
                                    </div>
                                    <div className="update-upload-actions">
                                        <label className="file-drop update-file-drop"><UploadCloud size={21} /><span>Add files</span><input type="file" multiple disabled={busy} onChange={event => uploadSelection(event, false)} /></label>
                                        <label className="file-drop update-file-drop"><FolderUp size={21} /><span>Add folder</span><input type="file" multiple webkitdirectory="" disabled={busy} onChange={event => uploadSelection(event, true)} /></label>
                                    </div>
                                    <form className="update-delete-form" onSubmit={addDeletion}>
                                        <label className="terminal-field"><span>Delete file or folder</span><input required placeholder="data/obsolete.zip" value={deletePath} onChange={event => setDeletePath(event.target.value)} /></label>
                                        <button type="submit" disabled={busy}><X size={16} /> Add deletion</button>
                                    </form>
                                </section>
                            )}

                            <form className="update-file-filter" onSubmit={applyFileFilter}>
                                <input aria-label="Search files" placeholder="Search files..." value={filter} onChange={event => setFilter(event.target.value)} />
                                <label><input type="checkbox" checked={includeInherited} onChange={toggleInherited} /> Include unchanged files</label>
                                <button type="submit" disabled={busy}>Search</button>
                            </form>

                            <div className="update-file-table" role="region" aria-label="Update files" tabIndex="0">
                                <div className="update-file-row update-file-head"><span>Path</span><span>Change</span><span>Size</span><span>Version</span><span>Actions</span></div>
                                {visibleFiles.map(file => (
                                    <div className="update-file-entry" key={file.id}>
                                        <div className="update-file-row">
                                            <span>{file.path}</span>
                                            <span>{changeLabel(file)}</span>
                                            <span>{file.action === 'DELETE' ? '—' : <>{formatBytes(file.sizeBytes)}{file.uploadStatus && file.uploadStatus !== 'COMPLETE' && <small> {statusLabel(file.uploadStatus)}</small>}</>}</span>
                                            <span>{file.inherited ? 'Previous' : 'This update'}</span>
                                            <span className="update-file-actions">
                                                {editable && !file.inherited && <button type="button" aria-expanded={editingFile?.id === file.id} onClick={() => beginFileEdit(file)} disabled={busy}><Pencil size={15} /> Edit</button>}
                                                {editable && !file.inherited && <button type="button" onClick={() => revertFile(file)} disabled={busy}>{file.action === 'DELETE' ? <RotateCcw size={15} /> : <Trash2 size={15} />}{file.action === 'DELETE' ? 'Undo' : 'Remove'}</button>}
                                                {editable && file.inherited && <button type="button" onClick={() => markFileDeleted(file)} disabled={busy}><Trash2 size={15} /> Delete</button>}
                                                {!editable && '—'}
                                            </span>
                                        </div>
                                        {editingFile?.id === file.id && (
                                            <form className="update-file-editor" onSubmit={saveFileEdit}>
                                                <label className="terminal-field"><span>Target path</span><input required value={editingFile.targetPath} onChange={event => setEditingFile({ ...editingFile, targetPath: event.target.value })} /></label>
                                                {editingFile.action === 'UPSERT' && <label className="terminal-field"><span>If file exists</span><select value={editingFile.overwritePolicy} onChange={event => setEditingFile({ ...editingFile, overwritePolicy: event.target.value })}><option value="REPLACE">Replace changed file</option><option value="PRESERVE">Keep existing file</option></select></label>}
                                                <div className="update-file-editor-actions">
                                                    <button className="terminal-action" type="submit" disabled={busy}>Save</button>
                                                    <button type="button" onClick={() => setEditingFile(null)} disabled={busy}>Cancel</button>
                                                </div>
                                            </form>
                                        )}
                                    </div>
                                ))}
                                {!visibleFiles.length && <p className="update-empty">No files found.</p>}
                            </div>
                            {visibleFiles.length === 1000 && <p className="markdown-help">Showing the first 1000 files. Refine the search.</p>}
                        </>
                    )}
                </section>
            </div>
        </div>
    );
}

function changeLabel(file) {
    if (file.action === 'DELETE') return 'Delete';
    return file.overwritePolicy === 'PRESERVE' ? 'Add, keep local' : 'Add or replace';
}

function statusLabel(status) {
    return ({
        DRAFT: 'Draft',
        UPLOADING: 'Uploading',
        PUBLISHED: 'Published',
        RETIRED: 'Retired',
        PENDING: 'Pending',
        UPLOADED: 'Uploaded',
        COMPLETE: 'Ready',
        FAILED: 'Failed'
    })[status] || status;
}

function selectedPath(file, folderSelection) {
    const browserPath = String(file.webkitRelativePath || file.name).replaceAll('\\', '/');
    if (!folderSelection || !browserPath.includes('/')) return browserPath;
    return browserPath.slice(browserPath.indexOf('/') + 1);
}

function joinTargetPath(directory, relativePath) {
    const normalizedDirectory = directory.trim().replaceAll('\\', '/').replace(/^\/+|\/+$/g, '');
    return normalizedDirectory ? `${normalizedDirectory}/${relativePath}` : relativePath;
}

function formatBytes(bytes) {
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}
