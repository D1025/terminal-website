import { Download, Home, Library, Shield } from 'lucide-react';

export default function PublicFrame({ eyebrow, title, children, actions }) {
    return (
        <section className="public-module">
            <header className="module-header">
                <div>
                    <p className="terminal-kicker">{eyebrow}</p>
                    <h1>{title}</h1>
                </div>
                <nav className="module-nav" aria-label="Public modules">
                    <a href="/" title="Terminal" className={active('/')}><Home size={17} /> <span>Terminal</span></a>
                    <a href="/wiki" className={active('/wiki')}><Library size={17} /> <span>Wiki</span></a>
                    <a href="/download" className={active('/download')}><Download size={17} /> <span>Client</span></a>
                    <a href="/admin" className={active('/admin')}><Shield size={17} /> <span>Access</span></a>
                </nav>
            </header>
            {actions && <div className="module-actions">{actions}</div>}
            <main className="module-content">{children}</main>
            <footer className="terminal-footer">
                <span>TECH KNOWLEDGE NETWORK</span>
                <a href="/">RETURN / ROOT</a>
            </footer>
        </section>
    );
}

function active(path) {
    return path === '/' ? (window.location.pathname === '/' ? 'active' : '') : (window.location.pathname.startsWith(path) ? 'active' : '');
}
