import { useState, useEffect } from 'react';

const lines = [
    '*** TECH INDUSTRIES (TM) TERMALINK PROTOCOL ***',
    'ENTERING SAFE MODE...',
    'INITIALIZING KERNEL...',
    'LOADING DRIVERS...',
    'BOOT COMPLETE'
];

function BootScreen({ onFinish }) {
    const [line, setLine] = useState(0);
    const [char, setChar] = useState(0);

    useEffect(() => {
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            const id = setTimeout(onFinish, 150);
            return () => clearTimeout(id);
        }

        if (line >= lines.length) {
            const id = setTimeout(onFinish, 450);
            return () => clearTimeout(id);
        }
        if (char < lines[line].length) {
            const id = setTimeout(() => setChar(char + 1), 24);
            return () => clearTimeout(id);
        }
        const id = setTimeout(() => {
            setLine(line + 1);
            setChar(0);
        }, 280);
        return () => clearTimeout(id);
    }, [line, char, onFinish]);

    const printed = lines.slice(0, line);
    const current = line < lines.length ? lines[line].slice(0, char) : '';

    return (
        <section className="boot-screen" aria-labelledby="boot-title" data-nosnippet>
            <div>
                <p className="terminal-kicker">TECH Unified Operating System</p>
                <h1 id="boot-title" className="sr-only">Terminal boot sequence</h1>
            </div>

            <div className="boot-log" role="status" aria-live="polite" aria-atomic="false">
                {printed.map((printedLine, index) => (
                    <div key={index}>{printedLine}</div>
                ))}
                {line < lines.length && (
                    <div>
                        {current}
                        <span className="cursor" aria-hidden="true">_</span>
                    </div>
                )}
            </div>

            <div className="boot-footer">
                <span>TERM-LINK // NODE ND-01</span>
                <button type="button" className="terminal-action terminal-action--quiet" onClick={onFinish}>
                    [ Skip boot ]
                </button>
            </div>
        </section>
    );
}

export default BootScreen;
