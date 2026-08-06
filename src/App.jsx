import { useCallback, useEffect, useRef, useState } from 'react';
import BootScreen from './components/BootScreen';
import LoginScreen from './components/LoginScreen';
import TitleScreen from './components/TitleScreen';
import TerminalScreen from './components/TerminalScreen';
import TerminalShell from './components/TerminalShell';
import WikiIndex from './pages/WikiIndex';
import WikiArticle from './pages/WikiArticle';
import DownloadPage from './pages/DownloadPage';
import AdminPage from './pages/AdminPage';
import TimedAccessGate from './components/TimedAccessGate';

function TerminalExperience() {
    const [stage, setStage] = useState('boot');
    const [fade, setFade] = useState('in');
    const transitionRef = useRef(null);

    useEffect(() => {
        if (localStorage.getItem('logged') === 'true') setStage('title');
        return () => clearTimeout(transitionRef.current);
    }, []);

    const go = useCallback((next) => {
        clearTimeout(transitionRef.current);
        setFade('out');
        transitionRef.current = setTimeout(() => {
            setStage(next);
            setFade('in');
        }, 180);
    }, []);

    const screen = {
        boot: <BootScreen onFinish={() => go('login')} />,
        login: <LoginScreen onSuccess={() => go('title')} />,
        title: <TitleScreen onEnter={() => go('terminal')} />,
        terminal: <TerminalScreen />
    }[stage];

    return (
        <TerminalShell stage={stage}>
            <div className={`screen-transition screen-transition--${fade}`}>{screen}</div>
        </TerminalShell>
    );
}

function TerminalRoute({ children, stage = 'terminal' }) {
    return (
        <TerminalShell stage={stage}>
            <div className="screen-transition screen-transition--in">{children}</div>
        </TerminalShell>
    );
}

export default function App() {
    const path = window.location.pathname.replace(/\/$/, '') || '/';
    if (path === '/') return <TerminalExperience />;
    if (path === '/wiki') return <TerminalRoute><TimedAccessGate area="wiki"><WikiIndex /></TimedAccessGate></TerminalRoute>;
    if (path.startsWith('/wiki/')) {
        return <TerminalRoute><TimedAccessGate area="wiki"><WikiArticle slug={decodeURIComponent(path.slice('/wiki/'.length))} /></TimedAccessGate></TerminalRoute>;
    }
    if (path === '/download') return <TerminalRoute><TimedAccessGate area="download"><DownloadPage /></TimedAccessGate></TerminalRoute>;
    if (path === '/admin' || path.startsWith('/admin/')) return <TerminalRoute stage="admin"><AdminPage /></TerminalRoute>;
    window.location.replace('/');
    return null;
}
