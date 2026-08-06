import { LockKeyhole, LogIn } from 'lucide-react';
import Countdown from './Countdown';
import PublicFrame from './PublicFrame';
import { SITE_ACCESS_DEFAULTS, timestamp, useClock, useSiteConfiguration } from '../lib/site-access';
import { useAuth } from '../security/useAuth';

export default function TimedAccessGate({ area, children }) {
    const { user, loading } = useAuth();
    const configuration = useSiteConfiguration();
    const now = useClock();
    const settings = area === 'wiki'
        ? { title: 'Wiki access pending', eyebrow: 'Public archive // sealed', key: 'wikiUnlockAt' }
        : { title: 'Client access pending', eyebrow: 'Distribution node // sealed', key: 'downloadUnlockAt' };
    const unlockAt = timestamp(configuration[settings.key], SITE_ACCESS_DEFAULTS[settings.key]);

    if (loading) {
        return <PublicFrame eyebrow="Checking access" title="Please wait"><div className="module-empty">VERIFYING SESSION<span className="cursor">_</span></div></PublicFrame>;
    }
    if (user || now >= unlockAt) return children;

    return (
        <PublicFrame eyebrow={settings.eyebrow} title={settings.title}>
            <div className="timed-access-card">
                <LockKeyhole size={34} aria-hidden="true" />
                <span>PUBLIC ACCESS OPENS IN</span>
                <Countdown target={unlockAt} />
                <p>Editors and administrators can sign in to preview this section before publication.</p>
                <a className="terminal-action" href="/admin"><LogIn size={18} /> Staff login</a>
            </div>
        </PublicFrame>
    );
}
