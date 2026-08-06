import { useCallback, useEffect, useState } from 'react';

export default function Countdown({ target }) {
    const calc = useCallback(() => {
        const diff = target - Date.now();
        if (diff <= 0) return { d: 0, h: 0, m: 0, s: 0 };
        return {
            d: Math.floor(diff / 86400000),
            h: Math.floor(diff / 3600000) % 24,
            m: Math.floor(diff / 60000) % 60,
            s: Math.floor(diff / 1000) % 60
        };
    }, [target]);

    const [time, setTime] = useState(calc());

    useEffect(() => {
        const id = setInterval(() => setTime(calc()), 1000);
        return () => clearInterval(id);
    }, [calc]);

    const pad = v => v.toString().padStart(2, '0');
    const readable = `${time.d} days, ${time.h} hours, ${time.m} minutes and ${time.s} seconds`;

    return (
        <time className="countdown" role="timer" aria-label={readable}>
            {pad(time.d)}d {pad(time.h)}h {pad(time.m)}m {pad(time.s)}s
        </time>
    );
}
