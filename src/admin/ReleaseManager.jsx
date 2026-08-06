import { useEffect, useState } from 'react';
import { FileUp, Send, Trash2 } from 'lucide-react';
import { api, sha256, uploadToSignedUrl } from '../lib/api';

const emptyRelease = { version: '', platform: 'WINDOWS', channel: 'STABLE', releaseNotesMarkdown: '', minimumLauncherVersion: '', sha256: '' };

export default function ReleaseManager() {
    const [releases, setReleases] = useState([]);
    const [form, setForm] = useState(emptyRelease);
    const [file, setFile] = useState(null);
    const [readyRelease, setReadyRelease] = useState(null);
    const [createOpen, setCreateOpen] = useState(false);
    const [busy, setBusy] = useState(false);
    const [progress, setProgress] = useState('');
    const [error, setError] = useState('');

    async function load() {
        const data = await api('/admin/releases', { auth: true });
        setReleases(data);
        if (!data.length) setCreateOpen(true);
    }

    useEffect(() => { load().catch(requestError => setError(requestError.message)); }, []);

    async function upload(event) {
        event.preventDefault();
        if (!file) return;
        const publishAfterUpload = event.nativeEvent.submitter?.value === 'publish';
        if (publishAfterUpload && !window.confirm(`Upload and publish client ${form.version} to ${form.channel}?`)) return;
        setBusy(true);
        setError('');
        try {
            let checksum = form.sha256.trim().toLowerCase();
            if (!/^[a-f0-9]{64}$/.test(checksum)) {
                if (file.size > 256 * 1024 * 1024) {
                    throw new Error('For files larger than 256 MB, provide a precomputed SHA-256 checksum.');
                }
                setProgress('Checking the file...');
                checksum = await sha256(file);
            }
            setProgress('Uploading client...');
            const ticket = await api('/admin/releases', {
                method: 'POST', auth: true, body: JSON.stringify({
                    ...form,
                    fileName: file.name,
                    contentType: file.type || 'application/octet-stream',
                    sizeBytes: file.size,
                    sha256: checksum,
                    releaseNotesMarkdown: form.releaseNotesMarkdown || null,
                    minimumLauncherVersion: form.minimumLauncherVersion || null
                })
            });
            await uploadToSignedUrl(ticket, file);
            setProgress('Finishing upload...');
            const completed = await api(`/admin/releases/${ticket.release.id}/complete`, { method: 'POST', auth: true });
            if (publishAfterUpload) {
                try {
                    await api(`/admin/releases/${ticket.release.id}/publish`, { method: 'POST', auth: true });
                } catch (publishError) {
                    setReadyRelease(completed);
                    await load();
                    throw publishError;
                }
                setProgress(`Client ${completed.version} uploaded and published.`);
                setReadyRelease(null);
            } else {
                setProgress('Client uploaded and verified. Publish it when ready.');
                setReadyRelease(completed);
            }
            setForm(emptyRelease);
            setFile(null);
            setCreateOpen(false);
            event.target.reset();
            await load();
        } catch (requestError) {
            setError(requestError.message);
            setProgress('');
        } finally {
            setBusy(false);
        }
    }

    async function publish(id) {
        const release = releases.find(item => item.id === id) ?? (readyRelease?.id === id ? readyRelease : null);
        if (!window.confirm(`Publish client ${release?.version ?? ''} ${release?.platform ?? ''} to ${release?.channel ?? ''}?`)) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/releases/${id}/publish`, { method: 'POST', auth: true });
            setProgress('Client release published.');
            if (readyRelease?.id === id) setReadyRelease(null);
            await load();
        } catch (requestError) { setError(requestError.message); }
        finally { setBusy(false); }
    }

    async function discard(release) {
        const publishedWarning = release.status === 'PUBLISHED'
            ? ' This version is public now and its download will stop working.'
            : '';
        if (!window.confirm(`Permanently delete client version ${release.version} and its package from storage?${publishedWarning} This cannot be undone.`)) return;
        setBusy(true);
        setError('');
        try {
            await api(`/admin/releases/${release.id}`, { method: 'DELETE', auth: true });
            if (readyRelease?.id === release.id) setReadyRelease(null);
            setProgress(`Client version ${release.version} was permanently deleted.`);
            await load();
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    return (
        <div className="simple-manager">
            <header><h2>Client</h2></header>
            {progress && <p className="terminal-alert" role="status">{progress}</p>}
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}
            {readyRelease && (
                <section className="release-ready" aria-labelledby="release-ready-title">
                    <div><strong id="release-ready-title">Ready to publish</strong><span>{readyRelease.version} / {readyRelease.platform} / {readyRelease.channel}</span></div>
                    <button className="terminal-action" type="button" onClick={() => publish(readyRelease.id)} disabled={busy}><Send size={16} /> Publish</button>
                </section>
            )}
            <details className="update-create release-create" open={createOpen} onToggle={event => setCreateOpen(event.currentTarget.open)}>
                <summary><FileUp size={17} /> Add client version</summary>
                <form className="release-form" onSubmit={upload}>
                    <div className="editor-fields editor-fields--grid">
                        <label className="terminal-field"><span>Version</span><input required placeholder="0.9.0" value={form.version} onChange={event => setForm({ ...form, version: event.target.value })} /></label>
                        <label className="terminal-field"><span>Platform</span><select value={form.platform} onChange={event => setForm({ ...form, platform: event.target.value })}><option>WINDOWS</option><option>LINUX</option><option>MACOS</option></select></label>
                        <label className="terminal-field"><span>Channel</span><select value={form.channel} onChange={event => setForm({ ...form, channel: event.target.value })}><option>STABLE</option><option>TEST</option></select></label>
                    </div>
                    <label className="file-drop"><FileUp size={24} /><span>{file ? `${file.name} (${formatBytes(file.size)})` : 'Choose a client package'}</span><input type="file" required onChange={event => setFile(event.target.files?.[0] ?? null)} /></label>
                    <details className="form-options">
                        <summary>Release details</summary>
                        <div className="form-options-fields">
                            <label className="terminal-field"><span>Minimum launcher version</span><input value={form.minimumLauncherVersion} onChange={event => setForm({ ...form, minimumLauncherVersion: event.target.value })} /></label>
                            <label className="terminal-field"><span>Checksum for files over 256 MB</span><input pattern="[a-fA-F0-9]{64}" placeholder="Optional for smaller files" value={form.sha256} onChange={event => setForm({ ...form, sha256: event.target.value })} /></label>
                            <label className="terminal-field"><span>Release notes</span><textarea rows="4" value={form.releaseNotesMarkdown} onChange={event => setForm({ ...form, releaseNotesMarkdown: event.target.value })} /></label>
                        </div>
                    </details>
                    <div className="release-form-actions">
                        <button type="submit" value="upload" disabled={busy}>{busy ? 'Processing...' : 'Upload draft'}</button>
                        <button className="terminal-action" type="submit" value="publish" disabled={busy}><Send size={16} /> {busy ? 'Processing...' : 'Upload & publish'}</button>
                    </div>
                </form>
            </details>
            <div className="data-table release-admin-table">
                <div className="data-table-row data-table-head"><span>Version</span><span>Platform</span><span>Status</span><span>Action</span></div>
                {releases.map(release => (
                    <div className="data-table-row" key={release.id}>
                        <span>{release.version}<small> {release.channel}</small></span><span>{release.platform}</span><span>{statusLabel(release.status)}</span>
                        <span className="release-row-actions">
                            {release.status === 'UPLOADED' && <button type="button" onClick={() => publish(release.id)} disabled={busy}><Send size={15} /> Publish</button>}
                            <button type="button" onClick={() => discard(release)} disabled={busy}><Trash2 size={15} /> Delete version</button>
                        </span>
                    </div>
                ))}
                {!releases.length && <p className="update-empty">No client versions.</p>}
            </div>
        </div>
    );
}

function formatBytes(bytes) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function statusLabel(status) {
    return ({ UPLOADING: 'Uploading', UPLOADED: 'Draft', PUBLISHED: 'Published' })[status] || status;
}
