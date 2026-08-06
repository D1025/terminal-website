import { useEffect, useState } from 'react';
import { Save } from 'lucide-react';
import { api } from '../lib/api';
import { SITE_ACCESS_DEFAULTS } from '../lib/site-access';

const fields = [
    { key: 'trailerUnlockAt', label: 'Trailer publication date' },
    { key: 'wikiUnlockAt', label: 'Wiki publication date' },
    { key: 'downloadUnlockAt', label: 'Client publication date' }
];

export default function SiteManager() {
    const [form, setForm] = useState({
        trailerUnlockAt: datePart(SITE_ACCESS_DEFAULTS.trailerUnlockAt),
        wikiUnlockAt: datePart(SITE_ACCESS_DEFAULTS.wikiUnlockAt),
        downloadUnlockAt: datePart(SITE_ACCESS_DEFAULTS.downloadUnlockAt),
        trailerYoutubeUrl: ''
    });
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const [error, setError] = useState('');

    useEffect(() => {
        api('/configuration')
            .then(configuration => setForm(current => ({
                ...current,
                trailerUnlockAt: datePart(configuration.trailerUnlockAt ?? SITE_ACCESS_DEFAULTS.trailerUnlockAt),
                wikiUnlockAt: datePart(configuration.wikiUnlockAt ?? SITE_ACCESS_DEFAULTS.wikiUnlockAt),
                downloadUnlockAt: datePart(configuration.downloadUnlockAt ?? SITE_ACCESS_DEFAULTS.downloadUnlockAt),
                trailerYoutubeUrl: configuration.trailerYoutubeUrl ?? ''
            })))
            .catch(requestError => setError(requestError.message));
    }, []);

    async function save(event) {
        event.preventDefault();
        setBusy(true);
        setMessage('');
        setError('');
        try {
            await Promise.all([
                ...fields.map(field => api(`/admin/configuration/${field.key}`, {
                    method: 'PUT', auth: true, body: JSON.stringify({ value: `${form[field.key]}T00:00:00+02:00` })
                })),
                api('/admin/configuration/trailerYoutubeUrl', {
                    method: 'PUT', auth: true, body: JSON.stringify({ value: form.trailerYoutubeUrl.trim() })
                })
            ]);
            setMessage('Public dates and trailer settings saved.');
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    return (
        <div className="simple-manager site-manager">
            <header><h2>Public site</h2></header>
            {message && <p className="terminal-alert" role="status">{message}</p>}
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}
            <form className="stack-form" onSubmit={save}>
                <div className="editor-fields editor-fields--grid">
                    {fields.map(field => (
                        <label className="terminal-field" key={field.key}>
                            <span>{field.label}</span>
                            <input type="date" required value={form[field.key]} onChange={event => setForm(current => ({ ...current, [field.key]: event.target.value }))} />
                        </label>
                    ))}
                </div>
                <label className="terminal-field">
                    <span>YouTube trailer URL</span>
                    <input type="url" placeholder="Add when the trailer is ready" value={form.trailerYoutubeUrl} onChange={event => setForm(current => ({ ...current, trailerYoutubeUrl: event.target.value }))} />
                </label>
                <button className="terminal-action" type="submit" disabled={busy}><Save size={17} /> {busy ? 'Saving...' : 'Save public site'}</button>
            </form>
        </div>
    );
}

function datePart(value) {
    return String(value).slice(0, 10);
}
