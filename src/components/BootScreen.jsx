import { useState, useEffect } from 'react';

const lines = [
    '*** ROBCO INDUSTRIES (TM) TERMALINK PROTOCOL ***',
    'ENTERING SAFE MODE...',
    'INITIALIZING KERNEL...',
    'LOADING DRIVERS...',
    'BOOT COMPLETE'
];

function BootScreen({ onFinish, fadeClass }) {
    const [line, setLine] = useState(0);
    const [char, setChar] = useState(0);

    useEffect(() => {
        if (line >= lines.length) {
            const id = setTimeout(onFinish, 800);
            return () => clearTimeout(id);
        }
        if (char < lines[line].length) {
            const id = setTimeout(() => setChar(char + 1), 35);
            return () => clearTimeout(id);
        }
        const id = setTimeout(() => {
            setLine(line + 1);
            setChar(0);
        }, 450);
        return () => clearTimeout(id);
    }, [line, char, onFinish]);

    const printed = lines.slice(0, line);
    const current = line < lines.length ? lines[line].slice(0, char) : '';

    return (
        <div className="frame">
            <div className="bezel">
                <div className="crt p-10">
                    <pre className={`leading-snug ${fadeClass}`}>
                        {printed.map((l, i) => <div key={i}>{l}</div>)}
                        {line < lines.length && (
                            <span>
                                {current}
                                <span className="cursor">[]</span>
                            </span>
                        )}
                    </pre>
                    <div className="scanline" />
                </div>
            </div>
        </div>
    );
}

export default BootScreen;
