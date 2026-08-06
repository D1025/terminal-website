import { useEffect, useState } from 'react';
import { api } from './api';

export const SITE_ACCESS_DEFAULTS = Object.freeze({
    launchAt: '2026-09-11T00:00:00+02:00',
    wikiUnlockAt: '2026-09-09T00:00:00+02:00',
    downloadUnlockAt: '2026-09-09T00:00:00+02:00',
    trailerUnlockAt: '2026-09-04T00:00:00+02:00',
    trailerYoutubeUrl: ''
});

export function useSiteConfiguration() {
    const [configuration, setConfiguration] = useState(SITE_ACCESS_DEFAULTS);

    useEffect(() => {
        let current = true;
        api('/configuration')
            .then(value => {
                if (current) setConfiguration({ ...SITE_ACCESS_DEFAULTS, ...value });
            })
            .catch(() => {});
        return () => { current = false; };
    }, []);

    return configuration;
}

export function useClock(interval = 1000) {
    const [now, setNow] = useState(Date.now());
    useEffect(() => {
        const id = window.setInterval(() => setNow(Date.now()), interval);
        return () => window.clearInterval(id);
    }, [interval]);
    return now;
}

export function timestamp(value, fallback) {
    const parsed = new Date(value).getTime();
    return Number.isFinite(parsed) ? parsed : new Date(fallback).getTime();
}
