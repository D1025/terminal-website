import { useState, useEffect } from 'react';

function TitleScreen({ onEnter, fadeClass }) {
    const [showMenu, setShowMenu] = useState(false);

    useEffect(() => {
        const id = setTimeout(() => setShowMenu(true), 1800);
        return () => clearTimeout(id);
    }, []);

    return (
        <div className="frame">
            <div className="bezel">
                <div className="crt flex items-center justify-center">
                    {!showMenu && (
                        <div className={`dots flex ${fadeClass}`}>
                            <span /><span /><span />
                        </div>
                    )}

                    {showMenu && (
                        <div className={`text-center space-y-6 glitch-fade ${fadeClass}`}>
                            <h1 className="tracking-widest">ROBCO INDUSTRIES presents</h1>
                            <h2 className="text-8xl tracking-widest">FOnline: New Dawn</h2>
                            <h3 className="text-2xl tracking-widest">@2025 Project</h3>
                            <button
                                onClick={onEnter}
                                className="border border-terminal-green px-8 py-4 tracking-wider hover:bg-terminal-green hover:text-terminal-gray transition uppercase"
                            >
                                Enter
                            </button>
                        </div>
                    )}

                    <div className="scanline" />
                </div>
            </div>
        </div>
    );
}

export default TitleScreen;
