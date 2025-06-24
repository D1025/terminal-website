import { useState, useEffect } from 'react';

export default function Countdown({ target }) {
    const calc = () => {
        const diff = target - Date.now();
        if (diff <= 0) return { d: 0, h: 0, m: 0, s: 0 };
        return {
            d: Math.floor(diff / 86400000),
            h: Math.floor(diff / 3600000) % 24,
            m: Math.floor(diff / 60000) % 60,
            s: Math.floor(diff / 1000) % 60
        };
    };
    const [time, setTime] = useState(calc());
    useEffect(() => {
        const id = setInterval(() => setTime(calc()), 1000);
        return () => clearInterval(id);
    }, [target]);

    const pad = v => v.toString().padStart(2, '0');
    return (
        <div className="tracking-widest select-none">
            {pad(time.d)}d {pad(time.h)}h {pad(time.m)}m {pad(time.s)}s
        </div>
    );
}
