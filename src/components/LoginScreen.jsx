import { useEffect, useMemo, useState } from 'react';

const CORRECT = 'NEWDAWN';
const BANK = [
    'LOYALIST', 'HUMANITY', 'SERVANTS', 'RESULTED', 'SUPPLIED', 'FEVERISH',
    'CONQUER', 'CITIZEN', 'SUDDEN', 'REPEATS', 'ANTENNA', 'HANGING', 'RAILING',
    'WEAPONS', 'CLASSES', 'PATCHES', 'PERKINS', 'TERRAIN', 'BLENDING', 'MELODIC',
    'THRIVING', 'MOCKING', 'BORDERED', 'CIRCULAR', 'CRIMSON', 'FROSTED',
    'GRANITE', 'HARMONY', 'ISOLATE', 'JUNCTION', 'KINDRED', 'LANTERN', 'MAGNETO',
    'NEUTRAL', 'ORBITAL', 'PIONEER', 'QUANTUM', 'REFRACT', 'STATURE', 'TRIANGLE',
    'UNIFIED', 'VIRTUAL', 'WITHERS', 'XRAYING', 'YEARLING', 'ZEALOTS'
];
const SYMBOLS = '{}<>[]()/\\|!@#$%^&*-+=;:?.';

const randomItem = array => array[(Math.random() * array.length) | 0];
const randomAddress = () => `0x${((Math.random() * 0xffff) | 0).toString(16).toUpperCase().padStart(4, '0')}`;
const likeness = (left, right) => [...left].filter((character, index) => character === right[index]).length;

function makeWords() {
    const words = new Set();
    while (words.size < 23) words.add(randomItem(BANK));
    const result = [...words].map(word => word.toUpperCase());
    result.splice((Math.random() * result.length) | 0, 0, CORRECT);
    return result;
}

function makeChunk(word) {
    const leftLength = (Math.random() * 5) | 0;
    const rightLength = Math.max(1, 12 - word.length - leftLength);
    const junk = length => Array.from({ length }, () => randomItem(SYMBOLS)).join('');

    return {
        address: randomAddress(),
        before: junk(leftLength),
        word,
        after: junk(rightLength)
    };
}

export default function LoginScreen({ onSuccess }) {
    const chunks = useMemo(() => makeWords().map(makeChunk), []);
    const [attempts, setAttempts] = useState(4);
    const [log, setLog] = useState([]);
    const [queue, setQueue] = useState([]);
    const [typing, setTyping] = useState('');
    const [position, setPosition] = useState(0);
    const [solved, setSolved] = useState(false);

    useEffect(() => {
        if (typing && position < typing.length) {
            const id = setTimeout(() => setPosition(current => current + 1), 18);
            return () => clearTimeout(id);
        }

        if (typing && position === typing.length) {
            setLog(current => [...current, typing]);
            setTyping('');
            setPosition(0);
        }

        if (!typing && queue.length) {
            setTyping(queue[0]);
            setQueue(current => current.slice(1));
        }
    }, [typing, position, queue]);

    function pick(word) {
        if (solved || attempts === 0) return;

        if (word === CORRECT) {
            setSolved(true);
            setQueue(current => [...current, `> ${word}`, 'ACCESS GRANTED']);
            localStorage.setItem('logged', 'true');
            setTimeout(onSuccess, 700);
            return;
        }

        const remaining = attempts - 1;
        setAttempts(remaining);
        setQueue(current => [
            ...current,
            `> ${word}`,
            `ENTRY DENIED // LIKENESS ${likeness(word, CORRECT)}`
        ]);

        if (!remaining) setTimeout(() => window.location.reload(), 1100);
    }

    return (
        <section className="login-screen" aria-labelledby="login-title">
            <header className="login-header">
                <div>
                    <p className="terminal-kicker">TECH Industries (TM) Termlink</p>
                    <h1 id="login-title">Password required</h1>
                </div>
                <div className="attempts" aria-label={`${attempts} of 4 attempts remaining`}>
                    <span>Attempts</span>
                    <span aria-hidden="true">{'■'.repeat(attempts)}{'□'.repeat(4 - attempts)}</span>
                </div>
            </header>

            <div className="login-layout">
                <div className="password-grid" aria-label="Password candidates">
                    {chunks.map(chunk => (
                        <div className="hack-line" key={`${chunk.address}-${chunk.word}`}>
                            <span className="memory-address" aria-hidden="true">{chunk.address}</span>
                            <span className="junk" aria-hidden="true">{chunk.before}</span>
                            <button
                                type="button"
                                className="hack-word"
                                onClick={() => pick(chunk.word)}
                                disabled={solved || attempts === 0}
                                aria-label={`Try password ${chunk.word}`}
                            >
                                {chunk.word}
                            </button>
                            <span className="junk" aria-hidden="true">{chunk.after}</span>
                        </div>
                    ))}
                </div>

                <aside className="login-console" aria-label="Authentication log">
                    <div className="console-label">AUTH.LOG</div>
                    <div className="console-output" aria-live="polite">
                        {log.map((entry, index) => <div key={`${entry}-${index}`}>{entry}</div>)}
                        {typing && <div>{typing.slice(0, position)}</div>}
                        {!typing && !log.length && <div>&gt; AWAITING INPUT<span className="cursor" aria-hidden="true">_</span></div>}
                    </div>
                </aside>
            </div>

            <p className="screen-help">Select a candidate. Likeness is the number of letters in the correct position.</p>
        </section>
    );
}
