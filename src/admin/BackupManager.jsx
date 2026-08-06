import { useEffect, useRef, useState } from 'react';
import { ArchiveRestore, DatabaseBackup, FileArchive, ShieldAlert, Upload } from 'lucide-react';
import { api, apiDownload } from '../lib/api';

export default function BackupManager() {
    const [status, setStatus] = useState(null);
    const [file, setFile] = useState(null);
    const [confirmed, setConfirmed] = useState(false);
    const [busy, setBusy] = useState('');
    const [message, setMessage] = useState('');
    const [error, setError] = useState('');
    const fileInput = useRef(null);

    async function loadStatus() {
        setStatus(await api('/admin/backup/status', { auth: true }));
    }

    useEffect(() => {
        loadStatus().catch(requestError => setError(requestError.message));
    }, []);

    async function downloadBackup() {
        setBusy('export');
        setError('');
        setMessage('');
        try {
            const download = await apiDownload('/admin/backup/export', { auth: true });
            const url = URL.createObjectURL(download.blob);
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = download.fileName;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            URL.revokeObjectURL(url);
            setMessage(`${download.fileName} downloaded.`);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy('');
        }
    }

    async function importBackup(event) {
        event.preventDefault();
        if (!file || !confirmed || !status?.importAllowed) return;
        setBusy('import');
        setError('');
        setMessage('');
        try {
            const body = new FormData();
            body.append('file', file);
            body.append('confirmation', 'IMPORT');
            const result = await api('/admin/backup/import', {
                method: 'POST',
                auth: true,
                sensitive: true,
                body
            });
            setMessage(`Restored ${result.articles} articles, ${result.assets} files and ${result.editors} editors.`);
            setFile(null);
            setConfirmed(false);
            if (fileInput.current) fileInput.current.value = '';
            await loadStatus();
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy('');
        }
    }

    return (
        <div className="simple-manager backup-manager">
            <header><h2>Backup</h2></header>

            <p className="backup-security-note"><ShieldAlert size={18} /> Restored editors keep their current passwords. Store every backup securely.</p>
            {message && <p className="terminal-alert" role="status" aria-live="polite">{message}</p>}
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}

            <StatusGrid status={status} />

            <div className="backup-layout">
                <section className="backup-panel">
                    <div className="backup-panel-heading"><DatabaseBackup size={22} /><div><h3>Download</h3><p>Wiki, files and editor accounts.</p></div></div>
                    <button className="terminal-action" type="button" onClick={downloadBackup} disabled={Boolean(busy) || !status}>
                        <FileArchive size={18} /> {busy === 'export' ? 'Preparing...' : 'Download backup'}
                    </button>
                </section>

                <section className="backup-panel backup-panel--restore">
                    <div className="backup-panel-heading"><ArchiveRestore size={22} /><div><h3>Restore</h3><p>Available only when all counters are zero.</p></div></div>
                    <form onSubmit={importBackup}>
                        <label className="file-drop backup-file-drop">
                            <Upload size={20} />
                            <span>{file ? `${file.name} · ${formatBytes(file.size)}` : 'Select backup ZIP'}</span>
                            <input ref={fileInput} type="file" accept=".zip,application/zip" onChange={event => {
                                setFile(event.target.files?.[0] ?? null);
                                setConfirmed(false);
                                setError('');
                                setMessage('');
                            }} />
                        </label>
                        <label className="backup-confirmation">
                            <input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={!status?.importAllowed || Boolean(busy)} />
                            <span>I confirm this is an empty database.</span>
                        </label>
                        {!status?.importAllowed && status && <p className="backup-blocked">Database is not empty.</p>}
                        <button className="terminal-action" type="submit" disabled={!file || !confirmed || !status?.importAllowed || Boolean(busy)}>
                            <ArchiveRestore size={18} /> {busy === 'import' ? 'Restoring...' : 'Restore backup'}
                        </button>
                    </form>
                </section>
            </div>
        </div>
    );
}

function StatusGrid({ status }) {
    const values = status ? [
        ['Articles', status.articles],
        ['Revisions', status.revisions],
        ['Categories', status.categories],
        ['Assets', status.assets],
        ['Editors', status.editors]
    ] : [['Articles', '...'], ['Revisions', '...'], ['Categories', '...'], ['Assets', '...'], ['Editors', '...']];
    return (
        <section className="backup-status" aria-label="Current backup data counts">
            {values.map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}
            <div className={status?.importAllowed ? 'backup-ready' : 'backup-locked'}><span>Restore</span><strong>{status ? (status.importAllowed ? 'Ready' : 'Not empty') : '...'}</strong></div>
        </section>
    );
}

function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes < 1) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}
