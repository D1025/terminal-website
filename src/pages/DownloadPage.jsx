import { useEffect, useState } from 'react';
import { Download } from 'lucide-react';
import PublicFrame from '../components/PublicFrame';
import { api } from '../lib/api';

export default function DownloadPage() {
    const [releases, setReleases] = useState([]);
    const [error, setError] = useState('');
    const [downloading, setDownloading] = useState(null);

    useEffect(() => {
        api('/releases?channel=STABLE', { auth: true })
            .then(setReleases)
            .catch(() => setError('The client release list is temporarily unavailable.'));
    }, []);

    async function download(release) {
        setDownloading(release.id);
        setError('');
        try {
            const link = await api(`/releases/${release.id}/download-link`, { auth: true });
            window.location.assign(link.url);
        } catch (requestError) {
            setError(requestError.message);
            setDownloading(null);
        }
    }

    return (
        <PublicFrame eyebrow="Verified distribution node" title="Download the client">
            {error && <p className="terminal-alert terminal-alert--error">{error}</p>}
            {!error && !releases.length && <div className="module-empty">NO CLIENT BUILD HAS BEEN PUBLISHED.</div>}
            <div className="release-list">
                {releases.map((release) => (
                    <article className="release-card" key={release.id}>
                        <div className="release-heading">
                            <h2>New Dawn {release.version}</h2>
                            <span className="release-platform">{release.platform}</span>
                        </div>
                        <dl className="release-facts">
                            <div><dt>Size</dt><dd>{formatBytes(release.sizeBytes)}</dd></div>
                        </dl>
                        <button className="terminal-action release-download" type="button" onClick={() => download(release)} disabled={downloading === release.id}>
                            <Download size={19} /> {downloading === release.id ? 'Preparing download...' : 'Download client'}
                        </button>
                    </article>
                ))}
            </div>
        </PublicFrame>
    );
}

function formatBytes(bytes) {
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}
