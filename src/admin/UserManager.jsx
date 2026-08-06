import { Fragment, useEffect, useState } from 'react';
import { Eye, EyeOff, KeyRound, UserPlus, X } from 'lucide-react';
import { api } from '../lib/api';

export default function UserManager() {
    const [users, setUsers] = useState([]);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [message, setMessage] = useState('');
    const [error, setError] = useState('');
    const [busy, setBusy] = useState(false);
    const [showInitialPassword, setShowInitialPassword] = useState(false);
    const [createOpen, setCreateOpen] = useState(false);
    const [resetUser, setResetUser] = useState(null);
    const [resetPassword, setResetPassword] = useState('');
    const [showResetPassword, setShowResetPassword] = useState(false);

    async function load() {
        setUsers(await api('/admin/users', { auth: true }));
    }

    useEffect(() => {
        load().catch(requestError => setError(requestError.message));
    }, []);

    async function create(event) {
        event.preventDefault();
        setError('');
        setMessage('');
        setBusy(true);
        try {
            const normalizedUsername = username.trim();
            await api('/admin/users', { method: 'POST', auth: true, sensitive: true, body: JSON.stringify({ username: normalizedUsername, password }) });
            setUsername('');
            setPassword('');
            setShowInitialPassword(false);
            setCreateOpen(false);
            setMessage(`${normalizedUsername} created.`);
            await load();
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function toggle(user) {
        setError('');
        setMessage('');
        setBusy(true);
        try {
            await api(`/admin/users/${user.id}/enabled`, { method: 'PATCH', auth: true, body: JSON.stringify({ enabled: !user.enabled }) });
            setMessage(`${user.username} ${user.enabled ? 'disabled' : 'enabled'}.`);
            await load();
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    async function saveResetPassword(event) {
        event.preventDefault();
        if (!resetUser) return;
        setError('');
        setMessage('');
        setBusy(true);
        try {
            await api(`/admin/users/${resetUser.id}/password`, {
                method: 'POST',
                auth: true,
                sensitive: true,
                body: JSON.stringify({ password: resetPassword })
            });
            setMessage(`Password for ${resetUser.username} changed.`);
            closeReset();
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    function startReset(user) {
        setResetUser(user);
        setResetPassword('');
        setShowResetPassword(false);
        setError('');
        setMessage('');
    }

    function closeReset() {
        setResetUser(null);
        setResetPassword('');
        setShowResetPassword(false);
    }

    const editors = users.filter(user => user.role === 'EDITOR' && !user.centralAdmin);

    return (
        <div className="simple-manager">
            <header><h2>Editors</h2></header>
            {message && <p className="terminal-alert" role="status">{message}</p>}
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}
            <details className="update-create compact-create" open={createOpen} onToggle={event => setCreateOpen(event.currentTarget.open)}>
                <summary><UserPlus size={17} /> Add editor</summary>
                <form className="inline-create-form" onSubmit={create}>
                    <label className="terminal-field"><span>Username</span><input required minLength="3" maxLength="80" autoCapitalize="none" autoCorrect="off" spellCheck="false" value={username} onChange={event => setUsername(event.target.value)} /></label>
                    <label className="terminal-field"><span>Initial password</span><span className="password-input-control"><input required type={showInitialPassword ? 'text' : 'password'} autoComplete="new-password" minLength="12" maxLength="128" value={password} onChange={event => setPassword(event.target.value)} /><button type="button" aria-label={showInitialPassword ? 'Hide initial password' : 'Show initial password'} title={showInitialPassword ? 'Hide password' : 'Show password'} onClick={() => setShowInitialPassword(current => !current)}>{showInitialPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></span></label>
                    <button className="terminal-action" type="submit" disabled={busy}><UserPlus size={18} /> Create editor</button>
                </form>
            </details>
            <div className="data-table user-table">
                <div className="data-table-row data-table-head"><span>User</span><span>Status</span><span>Actions</span></div>
                {editors.map(user => (
                    <Fragment key={user.id}>
                        <div className="data-table-row">
                            <span>{user.username}</span>
                            <span>{user.enabled ? 'Active' : 'Disabled'}</span>
                            <span className="user-actions"><button type="button" onClick={() => toggle(user)} disabled={busy}>{user.enabled ? 'Disable' : 'Enable'}</button><button type="button" onClick={() => startReset(user)} disabled={busy}><KeyRound size={15} /> Reset password</button></span>
                        </div>
                        {resetUser?.id === user.id && (
                            <form className="password-reset-row" onSubmit={saveResetPassword}>
                                <label className="terminal-field"><span>New password for {user.username}</span><span className="password-input-control"><input required type={showResetPassword ? 'text' : 'password'} autoComplete="new-password" minLength="12" maxLength="128" value={resetPassword} onChange={event => setResetPassword(event.target.value)} autoFocus /><button type="button" aria-label={showResetPassword ? 'Hide new password' : 'Show new password'} title={showResetPassword ? 'Hide password' : 'Show password'} onClick={() => setShowResetPassword(current => !current)}>{showResetPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></span></label>
                                <button type="submit" disabled={busy}><KeyRound size={16} /> Save password</button>
                                <button type="button" onClick={closeReset} disabled={busy}><X size={16} /> Cancel</button>
                            </form>
                        )}
                    </Fragment>
                ))}
                {!editors.length && <p className="update-empty">No editors yet.</p>}
            </div>
        </div>
    );
}
