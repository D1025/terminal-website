import {useEffect, useState} from 'react';
import BootScreen     from './components/BootScreen';
import LoginScreen    from './components/LoginScreen';
import TitleScreen    from './components/TitleScreen';
import TerminalScreen from './components/TerminalScreen';

export default function App() {
    const [stage, setStage] = useState('boot');
    const [fade,  setFade]  = useState('in');        // 'in' | 'out'

    useEffect(() => {
        if (localStorage.getItem('logged') === 'true') setStage('title');
    }, []);

    function go(next) {
        setFade('out');
        setTimeout(() => {
            setStage(next);
            setFade('in');
        }, 800);                                       // czas = czas animacji
    }

    const fadeClass = fade === 'in' ? 'glitch-fade-in' : 'glitch-fade-out';

    const screen = {
        boot:     <BootScreen     fadeClass={fadeClass} onFinish={() => go('login')} />,
        login:    <LoginScreen    fadeClass={fadeClass} onSuccess={() => go('title')} />,
        title:    <TitleScreen    fadeClass={fadeClass} onEnter={() => go('terminal')} />,
        terminal: <TerminalScreen fadeClass={fadeClass} />
    }[stage];

    return screen;
}
