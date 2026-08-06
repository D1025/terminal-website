import { LockKeyhole } from 'lucide-react';
import Countdown from './Countdown';

export default function TrailerPanel({ unlockAt, youtubeUrl, now }) {
    if (now < unlockAt) {
        return (
            <div className="access-message" role="status">
                <LockKeyhole size={30} aria-hidden="true" />
                <span>TRAILER UNLOCKS IN</span>
                <Countdown target={unlockAt} />
            </div>
        );
    }

    const embedUrl = youtubeEmbedUrl(youtubeUrl);
    return (
        <article className="section-article trailer-article">
            <p className="content-path">PUBLIC / TRAILER</p>
            <h2>Trailer</h2>
            {embedUrl ? (
                <div className="trailer-player">
                    <iframe src={embedUrl} title="FOnline: New Dawn trailer" loading="lazy" referrerPolicy="strict-origin-when-cross-origin" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen />
                </div>
            ) : (
                <div className="module-empty">VIDEO TRANSMISSION HAS NOT BEEN ADDED YET.</div>
            )}
        </article>
    );
}

function youtubeEmbedUrl(value) {
    if (!value) return null;
    try {
        const url = new URL(value);
        let id = '';
        if (url.hostname === 'youtu.be') id = url.pathname.slice(1).split('/')[0];
        if (url.hostname === 'youtube.com' || url.hostname.endsWith('.youtube.com')) {
            id = url.searchParams.get('v') || url.pathname.match(/^\/(?:embed|shorts)\/([^/]+)/)?.[1] || '';
        }
        return /^[A-Za-z0-9_-]{6,20}$/.test(id) ? `https://www.youtube-nocookie.com/embed/${id}` : null;
    } catch {
        return null;
    }
}
