import { useState } from 'react';
import { Lock } from 'lucide-react';
import Countdown from './Countdown';
import SectionContent from './SectionContent';

const targetDate = new Date('2025-07-31T20:00:00Z').getTime();

/* ────────── menu ────────── */
const sections = [
    { key: 'info',     label: 'General info', locked: false },
    { key: 'patches',  label: 'Patch notes',  locked: true  },
    // { key: 'perks',    label: 'Perks',        locked: true },
    // { key: 'weapons',  label: 'Weapons',      locked: true },
    { key: 'classes',  label: 'Classes',      locked: true },
    { key: 'discord',  label: 'Discord',      locked: true  },
    { key: 'download', label: 'Download',     locked: true  }
];

export default function TerminalScreen({ fadeClass = '' }) {
    const [active, setActive] = useState('info');

    return (
        <div className="frame">
            <div className="bezel">
                <div className="crt p-6 flex flex-col h-full">
                    {/* tytuł i licznik */}
                    <h1 className={`text-4xl md:text-5xl tracking-widest ${fadeClass}`}>
                        FOnline: New Dawn
                    </h1>

                    <div
                        className={`my-8 text-6xl md:text-8xl leading-none tracking-widest ${fadeClass}`}
                    >
                        <Countdown target={targetDate} />
                    </div>

                    {/* treść */}
                    <div className="flex-1 flex gap-8 overflow-hidden">
                        {/* menu boczne */}
                        <ul
                            className={`space-y-1 overflow-y-auto select-none ${fadeClass}`}
                        >
                            {sections.map((s) => (
                                <li
                                    key={s.key}
                                    onClick={
                                        s.locked ? undefined : () => setActive(s.key)
                                    }
                                    className={`word ${
                                        active === s.key ? 'rev' : ''
                                    } ${s.locked ? 'cursor-default opacity-70' : 'cursor-pointer'}`}
                                >
                  <span className="inline-block w-4">
                    {s.locked ? (
                        <Lock className="inline-block w-4 h-4 -mt-0.5" />
                    ) : (
                        '>'
                    )}
                  </span>{' '}
                                    {s.label}
                                </li>
                            ))}
                        </ul>

                        {/* panel */}
                        <div className={`flex-1 overflow-y-auto pr-4 ${fadeClass}`}>
                            <SectionContent section={active} />
                        </div>
                    </div>

                    <div className="scanline" />
                </div>
            </div>
        </div>
    );
}
