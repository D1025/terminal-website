import { useState, useMemo } from 'react';
import Countdown from './Countdown';

/* --------- data imports --------- */
import info     from '../data/info.json';
import patches  from '../data/patches.json';
import perks    from '../data/perks.json';
import weapons  from '../data/weapons.json';
import classes  from '../data/classes.json';
/* (optionally) locked sections: discord / download */
/* import discord  from '../data/discord.json';
   import download from '../data/download.json'; */

const map = {
    info,
    patches,
    perks,
    weapons,
    classes,
    /* discord,
    download */
};

/* ---------- helper to render text / entries + images ---------- */
function ContentBody({ data }) {
    return (
        <div className="space-y-6">
            {/* text or JSON entries */}
            {data.content && (
                <p className="whitespace-pre-line leading-relaxed">{data.content}</p>
            )}
            {data.entries && (
                <ul className="list-none space-y-2">
                    {data.entries.map((e, i) => (
                        <li key={i} className="whitespace-pre-line">
                            {JSON.stringify(e, null, 2)}
                        </li>
                    ))}
                </ul>
            )}

            {/* images */}
            {data.images && (
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                    {data.images.map((img, i) => {
                        const src = typeof img === 'string' ? img : img.src;
                        const alt = typeof img === 'string' ? ''   : img.alt ?? '';
                        return (
                            <img
                                key={i}
                                src={src}
                                alt={alt}
                                className="w-full h-auto object-contain border border-terminal-green p-2"
                            />
                        );
                    })}
                </div>
            )}
        </div>
    );
}

/* ---------- main component ---------- */
export default function SectionContent({ section }) {
    const data = map[section];

    /* completely blocked or missing -------------------------------- */
    if (!data || data.unlockDate === undefined) {
        return <p className="text-red-500">ACCESS DENIED</p>;
    }

    /* countdown gate ---------------------------------------------- */
    const unlock = data.unlockDate ? new Date(data.unlockDate).getTime() : 0;
    if (unlock > Date.now()) {
        return (
            <div>
                <p className="mb-2">Section unlocks in:</p>
                <Countdown target={unlock} />
            </div>
        );
    }

    /* -------- subsections handling -------- */
    const hasSubs = Array.isArray(data.subsections) && data.subsections.length;
    // eslint-disable-next-line react-hooks/rules-of-hooks
    const [activeSub, setActiveSub] = useState(
        hasSubs ? data.subsections[0].key : null
    );

    // eslint-disable-next-line react-hooks/rules-of-hooks
    const current = useMemo(() => {
        if (!hasSubs) return data;
        return data.subsections.find((s) => s.key === activeSub) ?? data.subsections[0];
    }, [hasSubs, data, activeSub]);

    /* if the current subsection is still locked ------------------- */
    if (current.unlockDate === undefined) {
        return <p className="text-red-500">ACCESS DENIED</p>;
    }
    const subUnlock = current.unlockDate ? new Date(current.unlockDate).getTime() : 0;
    if (subUnlock > Date.now()) {
        return (
            <div>
                <p className="mb-2">Sub-section unlocks in:</p>
                <Countdown target={subUnlock} />
            </div>
        );
    }

    /* -------------------- render -------------------- */
    if (!hasSubs) {
        /* simple, no sub-tabs */
        return <ContentBody data={current} />;
    }

    /* with sub-tabs */
    return (
        <div className="flex gap-8 h-full overflow-hidden">
            <ul className="space-y-1 overflow-y-auto select-none">
                {data.subsections.map((s) => (
                    <li
                        key={s.key}
                        onClick={() => setActiveSub(s.key)}
                        className={`word ${activeSub === s.key ? 'rev' : ''} cursor-pointer`}
                    >
                        &gt; {s.label ?? s.key}
                    </li>
                ))}
            </ul>

            <div className="flex-1 overflow-y-auto pr-4">
                <ContentBody data={current} />
            </div>
        </div>
    );
}
