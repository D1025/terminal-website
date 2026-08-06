import { useState, useEffect } from 'react';

function TitleScreen({ onEnter }) {
    const [showMenu, setShowMenu] = useState(false);

    useEffect(() => {
        const delay = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 650;
        const id = setTimeout(() => setShowMenu(true), delay);
        return () => clearTimeout(id);
    }, []);

    return (
        <section className="title-screen" aria-labelledby="project-title">
            {!showMenu && (
                <div className="terminal-loader" role="status" aria-label="Loading project terminal">
                    <span /><span /><span />
                </div>
            )}

            {showMenu && (
                <div className="title-lockup">
                    <p className="terminal-kicker">TECH Industries presents</p>
                    <h1 id="project-title" className="project-title">
                        <span>FOnline:</span>
                        <span>New Dawn</span>
                    </h1>
                    <div className="title-rule" aria-hidden="true">&gt; INITIALIZATION COMPLETE_</div>
                    <p className="project-year">© {new Date().getFullYear()} Project</p>
                    <button type="button" onClick={onEnter} className="terminal-action terminal-action--primary">
                        [ Enter terminal ]
                    </button>
                </div>
            )}
        </section>
    );
}

export default TitleScreen;
