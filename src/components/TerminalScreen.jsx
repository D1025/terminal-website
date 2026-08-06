import { useEffect, useState } from 'react';
import { ExternalLink, LockKeyhole, LogIn, LogOut, ShieldCheck } from 'lucide-react';
import Countdown from './Countdown';
import PatchNotesPanel from './PatchNotesPanel';
import SectionContent from './SectionContent';
import TrailerPanel from './TrailerPanel';
import { api } from '../lib/api';
import { SITE_ACCESS_DEFAULTS, timestamp, useClock, useSiteConfiguration } from '../lib/site-access';
import { useAuth } from '../security/useAuth';

const DISCORD_URL = 'https://discord.gg/sk9Fcx9Jqr';

export default function TerminalScreen() {
    const { user, loading: authLoading, logout } = useAuth();
    const configuration = useSiteConfiguration();
    const now = useClock();
    const [active, setActive] = useState('overview');
    const [patchNotes, setPatchNotes] = useState([]);
    const [patchNotesLoading, setPatchNotesLoading] = useState(true);

    useEffect(() => {
        api('/wiki/patch-notes')
            .then(setPatchNotes)
            .catch(() => setPatchNotes([]))
            .finally(() => setPatchNotesLoading(false));
    }, []);

    const launchAt = timestamp(configuration.launchAt, SITE_ACCESS_DEFAULTS.launchAt);
    const wikiUnlockAt = timestamp(configuration.wikiUnlockAt, SITE_ACCESS_DEFAULTS.wikiUnlockAt);
    const downloadUnlockAt = timestamp(configuration.downloadUnlockAt, SITE_ACCESS_DEFAULTS.downloadUnlockAt);
    const trailerUnlockAt = timestamp(configuration.trailerUnlockAt, SITE_ACCESS_DEFAULTS.trailerUnlockAt);
    const staffPreview = Boolean(user);
    const sections = [
        { key: 'overview', label: 'Overview' },
        { key: 'trailer', label: 'Trailer', locked: now < trailerUnlockAt },
        { key: 'patches', label: 'Patch notes', locked: patchNotesLoading || patchNotes.length === 0, unavailable: true },
        { key: 'discord', label: 'Discord', href: DISCORD_URL },
        { key: 'wiki', label: 'Wiki', route: '/wiki', locked: !staffPreview && now < wikiUnlockAt },
        { key: 'download', label: 'Download', route: '/download', locked: !staffPreview && now < downloadUnlockAt }
    ];

    return (
        <section className="terminal-home" aria-labelledby="terminal-title">
            <header className="terminal-home-header">
                <div>
                    <p className="terminal-kicker">Public information network // Node ND-01</p>
                    <h1 id="terminal-title">FOnline: New Dawn</h1>
                </div>
                <div className="terminal-meta">
                    <div className="terminal-countdown">
                        <Countdown target={launchAt} />
                    </div>
                    <div className="system-status" role="status">
                        <span className="status-indicator" aria-hidden="true" />
                        System online
                    </div>
                    {!authLoading && (user ? (
                        <div className="staff-session">
                            <span><ShieldCheck size={16} /> {user.username}</span>
                            <a href="/admin">Control panel</a>
                            <button type="button" onClick={logout} aria-label="Log out"><LogOut size={17} /></button>
                        </div>
                    ) : (
                        <a className="staff-login-trigger" href="/admin"><LogIn size={17} /> Staff login</a>
                    ))}
                </div>
            </header>

            <div className="terminal-workspace">
                <nav className="terminal-nav" aria-label="Terminal sections">
                    <div className="nav-label">DIR / PUBLIC</div>
                    <ul className="terminal-nav-list">
                        {sections.map(section => (
                            <li key={section.key}>
                                {section.href ? (
                                    <a className="terminal-nav-button" href={section.href} target="_blank" rel="noreferrer">
                                        <span className="nav-marker" aria-hidden="true"><ExternalLink size={16} /></span>
                                        <span>{section.label}</span>
                                    </a>
                                ) : (
                                    <button
                                        type="button"
                                        disabled={section.unavailable && section.locked}
                                        onClick={() => section.route ? window.location.assign(section.route) : setActive(section.key)}
                                        className={section.locked ? 'terminal-nav-button terminal-nav-button--locked' : 'terminal-nav-button'}
                                        aria-current={active === section.key ? 'page' : undefined}
                                        title={section.unavailable && section.locked ? 'No patch notes have been published yet' : section.locked ? `${section.label} opens on the scheduled date` : undefined}
                                    >
                                        <span className="nav-marker" aria-hidden="true">
                                            {section.locked ? <LockKeyhole size={17} strokeWidth={1.8} /> : section.route ? <ExternalLink size={16} /> : '>'}
                                        </span>
                                        <span>{section.label}</span>
                                        {section.locked && <span className="sr-only"> — locked</span>}
                                    </button>
                                )}
                            </li>
                        ))}
                    </ul>
                </nav>

                <div className="terminal-panel" aria-label="Selected terminal content">
                    {active === 'overview' && <SectionContent section="overview" />}
                    {active === 'trailer' && <TrailerPanel unlockAt={trailerUnlockAt} youtubeUrl={configuration.trailerYoutubeUrl} now={now} />}
                    {active === 'patches' && patchNotes.length > 0 && <PatchNotesPanel notes={patchNotes} />}
                </div>
            </div>

            <footer className="terminal-footer">
                <span>TECH TERMLINK 6.4.0</span>
                <span>SECURITY: {user ? user.role : 'PUBLIC'}</span>
            </footer>
        </section>
    );
}
