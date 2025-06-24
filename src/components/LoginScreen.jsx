import { useState, useEffect, useMemo } from 'react';

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
const SYM = '{}<>[]()/\\|!@#$%^&*-+=;:?.';

const r   = a => a[(Math.random() * a.length) | 0];
const hex = () => `0x${((Math.random()*0xffff)|0).toString(16).toUpperCase().padStart(4,'0')}`;
const alike = (a,b) => [...a].filter((c,i)=>c===b[i]).length;

/* helpers */
function makeWords(){
    const s=new Set();
    while(s.size<23) s.add(r(BANK));
    const arr=[...s].map(w=>w.toUpperCase());
    arr.splice((Math.random()*arr.length)|0,0,CORRECT);
    return arr;
}
function chunk(w){
    const l=(Math.random()*8)|0;
    const rp=14-w.length-l;
    const junk=n=>Array.from({length:n},()=>r(SYM)).join('');
    return {addr:hex(),pre:junk(l),word:w,post:junk(rp)};
}
function matrix(ws){
    const ch=ws.map(chunk),out=[];
    for(let i=0;i<ch.length;i+=2) out.push([ch[i],ch[i+1]??null]);
    return out;
}

export default function LoginScreen({ onSuccess, fadeClass }) {
    const rows = useMemo(() => matrix(makeWords()), []);
    const [attempts, setAttempts] = useState(4);
    const [log, setLog] = useState([]);
    const [queue, setQueue] = useState([]);
    const [typing, setTyping] = useState('');
    const [pos, setPos] = useState(0);
    const [cursorBlink, setCursorBlink] = useState(true);

    useEffect(() => {
        const id = setInterval(() => setCursorBlink(c => !c), 450);
        return () => clearInterval(id);
    }, []);

    useEffect(() => {
        if (typing && pos < typing.length) {
            const id = setTimeout(() => setPos(p => p + 1), 18);
            return () => clearTimeout(id);
        }
        if (typing && pos === typing.length) {
            setLog(l => [typing, ...l]);
            setTyping('');
            setPos(0);
        }
        if (!typing && queue.length) {
            setTyping(queue[0]);
            setQueue(q => q.slice(1));
        }
    }, [typing, pos, queue]);

    function pick(word) {
        if (word === CORRECT) {
            setQueue(q => [...q, `> ${word}`, 'ACCESS GRANTED']);
            localStorage.setItem('logged', 'true');
            setTimeout(onSuccess, 900);
            return;
        }
        const left = attempts - 1;
        setAttempts(left);
        setQueue(q => [
            ...q,
            `> ${word}`,
            `Entry denied – likeness = ${alike(word, CORRECT)}`
        ]);
        if (!left) setTimeout(() => location.reload(), 1200);
    }

    return (
        <div className="frame">
            <div className="bezel">
                <div className="crt flex flex-col h-full p-10">
                    <div className={`leading-snug space-y-2 select-none ${fadeClass}`}>
                        <div>WELCOME TO ROBCO INDUSTRIES (TM) TERMLINK</div>
                        <div>PASSWORD REQUIRED</div>
                        <div>
                            ATTEMPTS REMAINING:{' '}
                            {'■'.repeat(attempts) + '□'.repeat(4 - attempts)}
                        </div>
                    </div>

                    <div className="flex-1 mt-8 grid grid-cols-[auto_20rem] gap-x-20 overflow-hidden leading-tight">
                        <pre className={`select-none ${fadeClass}`}>
                            {rows.map((pair,i)=>(
                                <div key={i} className="flex gap-20">
                                    {pair.map((c,k)=>c && (
                                        <span key={k}>
                                            {c.addr}  {c.pre}
                                            <span className="word" onClick={()=>pick(c.word)}>
                                                {c.word}
                                            </span>
                                            {c.post}
                                        </span>
                                    ))}
                                </div>
                            ))}
                        </pre>

                        <div className={`w-80 flex flex-col-reverse overflow-hidden ${fadeClass}`}>
                            {typing && <div>{typing.slice(0,pos)}</div>}
                            {log.map((l,i)=><div key={i}>{l}</div>)}
                            {!typing && !log.length && <div>{cursorBlink && '[]'}</div>}
                        </div>
                    </div>

                    <div className="scanline" />
                </div>
            </div>
        </div>
    );
}
