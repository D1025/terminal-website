import { useEffect, useRef } from 'react';

const stageLabels = {
    boot: 'Boot sequence',
    login: 'Security login',
    title: 'Project title',
    terminal: 'FOnline New Dawn terminal'
};

export default function TerminalShell({ stage, children }) {
    const screenRef = useRef(null);

    useEffect(() => {
        screenRef.current?.focus({ preventScroll: true });
    }, [stage]);

    return (
        <div className="terminal-stage">
            <a className="skip-link" href="#terminal-content">
                Skip to terminal content
            </a>

            <div className="terminal-unit">
                <span className="terminal-fastener fastener-tl" aria-hidden="true" />
                <span className="terminal-fastener fastener-tr" aria-hidden="true" />
                <span className="terminal-fastener fastener-bl" aria-hidden="true" />
                <span className="terminal-fastener fastener-br" aria-hidden="true" />

                <main
                    id="terminal-content"
                    ref={screenRef}
                    className={`terminal-screen terminal-screen--${stage}`}
                    tabIndex="-1"
                    aria-label={stageLabels[stage] ?? 'Terminal screen'}
                >
                    <div className="screen-surface">{children}</div>
                </main>
            </div>
        </div>
    );
}
