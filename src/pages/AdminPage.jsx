import { useEffect, useState } from 'react';
import { BookOpenText, DatabaseBackup, Download, Eye, EyeOff, LogOut, RefreshCw, Settings, Tags, Users } from 'lucide-react';
import { useAuth } from '../security/useAuth';
import WikiManager from '../admin/WikiManager';
import UserManager from '../admin/UserManager';
import ReleaseManager from '../admin/ReleaseManager';
import TaxonomyManager from '../admin/TaxonomyManager';
import UpdateManager from '../admin/UpdateManager';
import BackupManager from '../admin/BackupManager';
import SiteManager from '../admin/SiteManager';

export default function AdminPage() {
    const { user, loading, login, logout } = useAuth();
    const [tab, setTab] = useState(() => new URLSearchParams(window.location.search).get('tab') || 'wiki');

    useEffect(() => {
        const restoreTab = () => setTab(new URLSearchParams(window.location.search).get('tab') || 'wiki');
        window.addEventListener('popstate', restoreTab);
        return () => window.removeEventListener('popstate', restoreTab);
    }, []);

    function openTab(key) {
        setTab(key);
        const url = new URL(window.location.href);
        if (key === 'wiki') url.searchParams.delete('tab');
        else url.searchParams.set('tab', key);
        window.history.pushState({}, '', url);
    }

    if (loading) return <AdminStatus />;
    if (!user) return <AdminLogin onLogin={login} />;

    const admin = user.role === 'ADMIN';
    const tabs = [
        { key: 'wiki', label: 'Articles', icon: BookOpenText },
        ...(admin ? [
            { key: 'site', label: 'Public site', icon: Settings },
            { key: 'taxonomy', label: 'Categories', icon: Tags },
            { key: 'updates', label: 'Updates', icon: RefreshCw },
            { key: 'releases', label: 'Client', icon: Download },
            { key: 'users', label: 'Editors', icon: Users },
            { key: 'backup', label: 'Backup', icon: DatabaseBackup }
        ] : [])
    ];
    const activeTab = tabs.some(item => item.key === tab) ? tab : 'wiki';

    return (
        <section className="admin-module">
            <header className="admin-header">
                <div>
                    <h1>Admin</h1>
                </div>
                <div className="admin-identity">
                    <span>{user.username}</span>
                    <a href="/wiki">Public wiki</a>
                    <button type="button" onClick={logout}><LogOut size={17} /> Log out</button>
                </div>
            </header>
            <div className="admin-workspace">
                <nav className="admin-nav" aria-label="Administration console">
                    {tabs.map(item => {
                        const Icon = item.icon;
                        return (
                            <button key={item.key} type="button" onClick={() => openTab(item.key)} aria-current={activeTab === item.key ? 'page' : undefined}>
                                <Icon size={18} /> {item.label}
                            </button>
                        );
                    })}
                </nav>
                <main className="admin-content">
                    {activeTab === 'wiki' && <WikiManager />}
                    {admin && activeTab === 'site' && <SiteManager />}
                    {admin && activeTab === 'taxonomy' && <TaxonomyManager />}
                    {admin && activeTab === 'users' && <UserManager />}
                    {admin && activeTab === 'backup' && <BackupManager />}
                    {admin && activeTab === 'releases' && <ReleaseManager />}
                    {admin && activeTab === 'updates' && <UpdateManager />}
                </main>
            </div>
        </section>
    );
}

function AdminLogin({ onLogin }) {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [showPassword, setShowPassword] = useState(false);

    async function submit(event) {
        event.preventDefault();
        setBusy(true);
        setError('');
        try {
            await onLogin(username.trim(), password);
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    return (
        <section className="admin-login" aria-labelledby="admin-login-title">
            <div className="admin-login-box">
                <h1 id="admin-login-title">Admin login</h1>
                <form onSubmit={submit}>
                    <label className="terminal-field"><span>Username</span><input autoComplete="username" autoCapitalize="none" autoCorrect="off" spellCheck="false" value={username} onChange={event => setUsername(event.target.value)} required minLength="3" /></label>
                    <label className="terminal-field"><span>Password</span><span className="password-input-control"><input type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required minLength="12" /><button type="button" aria-label={showPassword ? 'Hide password' : 'Show password'} title={showPassword ? 'Hide password' : 'Show password'} onClick={() => setShowPassword(current => !current)}>{showPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></span></label>
                    {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}
                    <button className="terminal-action" type="submit" disabled={busy}>{busy ? 'Verifying...' : 'Log in'}</button>
                </form>
                <a className="back-link" href="/">Back to site</a>
            </div>
        </section>
    );
}

function AdminStatus() {
    return <div className="admin-login"><div className="module-empty">Loading...</div></div>;
}
