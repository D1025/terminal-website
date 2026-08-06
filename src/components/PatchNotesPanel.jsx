import { useEffect, useState } from 'react';
import WikiMarkdown from './WikiMarkdown';

export default function PatchNotesPanel({ notes }) {
    const [activeId, setActiveId] = useState(notes[0]?.id ?? null);

    useEffect(() => {
        if (!notes.some(note => note.id === activeId)) setActiveId(notes[0]?.id ?? null);
    }, [activeId, notes]);

    const active = notes.find(note => note.id === activeId) ?? notes[0];
    if (!active) return null;

    return (
        <div className="patch-notes-layout">
            <nav className="patch-notes-index" aria-label="Published patch notes">
                <div className="nav-label">RELEASE LOG</div>
                {notes.map(note => (
                    <button type="button" key={note.id} onClick={() => setActiveId(note.id)} aria-current={note.id === active.id ? 'page' : undefined}>
                        <strong>{note.title}</strong>
                        <span>{formatDate(note.updatedAt)}</span>
                    </button>
                ))}
            </nav>
            <article className="section-article patch-note-article" tabIndex="0">
                <p className="content-path">PUBLIC / PATCH NOTES / {active.slug}</p>
                <h2>{active.title}</h2>
                {active.summary && <p className="wiki-lead">{active.summary}</p>}
                <WikiMarkdown>{active.contentMarkdown}</WikiMarkdown>
            </article>
        </div>
    );
}

function formatDate(value) {
    return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(value));
}
