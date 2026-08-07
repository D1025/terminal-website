import { useEffect, useState } from 'react';
import { ArrowLeft, Link2, Paperclip } from 'lucide-react';
import PublicFrame from '../components/PublicFrame';
import WikiMarkdown from '../components/WikiMarkdown';
import { api } from '../lib/api';
import { applySeo, noindexCurrentPage } from '../lib/seo';

export default function WikiArticle({ slug }) {
    const [page, setPage] = useState(null);
    const [error, setError] = useState('');

    useEffect(() => {
        setPage(null);
        setError('');
        api(`/wiki/pages/${encodeURIComponent(slug)}`, { auth: true })
            .then(setPage)
            .catch(() => {
                setError('This wiki article could not be found.');
                noindexCurrentPage(
                    'Article Not Found — FOnline: New Dawn',
                    'The requested FOnline: New Dawn wiki article could not be found.'
                );
            });
    }, [slug]);

    useEffect(() => {
        if (!page) return;
        applySeo({
            title: `${page.title} — FOnline: New Dawn Wiki`,
            description: seoDescription(page.summary) || `Read ${page.title} in the official FOnline: New Dawn wiki.`,
            canonicalPath: `/wiki/${encodeURIComponent(page.slug)}`,
            type: 'article'
        });
    }, [page]);

    if (error) {
        return <PublicFrame eyebrow="Public archive" title="Article unavailable"><div className="module-empty">{error} <a href="/wiki">Return to the index.</a></div></PublicFrame>;
    }
    if (!page) {
        return <PublicFrame eyebrow="Public archive" title="Loading article"><div className="module-empty">READING DATA<span className="cursor">_</span></div></PublicFrame>;
    }

    return (
        <PublicFrame eyebrow={`Wiki article // ${page.category ?? 'UNCATEGORIZED'}`} title={page.title}>
            <div className="article-layout">
                <article className="wiki-article">
                    <a className="back-link" href="/wiki"><ArrowLeft size={17} /> Wiki index</a>
                    {page.summary && <p className="wiki-lead">{page.summary}</p>}
                    <WikiMarkdown>{page.contentMarkdown}</WikiMarkdown>

                    {page.assets.some(asset => asset.usage === 'GALLERY' || asset.usage === 'HERO') && (
                        <div className="wiki-gallery">
                            {page.assets.filter(asset => asset.kind === 'WIKI_IMAGE').map(asset => (
                                <figure key={asset.id}>
                                    <img src={asset.url} alt={asset.altText ?? ''} loading="lazy" />
                                    {(asset.caption || asset.altText) && <figcaption>{asset.caption || asset.altText}</figcaption>}
                                </figure>
                            ))}
                        </div>
                    )}
                </article>

                <aside className="wiki-sidebar" aria-label="Article dependencies and attachments">
                    {page.relations.length > 0 && (
                        <section>
                            <h2><Link2 size={17} /> Dependencies</h2>
                            <ul className="relation-list">
                                {page.relations.map(relation => (
                                    <li key={relation.id}>
                                        <span>{relation.relationType.replaceAll('_', ' ')}</span>
                                        <a href={`/wiki/${relation.targetSlug}`}>{relation.label || relation.targetTitle}</a>
                                    </li>
                                ))}
                            </ul>
                        </section>
                    )}
                    {page.assets.some(asset => asset.kind === 'WIKI_FILE') && (
                        <section>
                            <h2><Paperclip size={17} /> Attachments</h2>
                            <ul className="relation-list">
                                {page.assets.filter(asset => asset.kind === 'WIKI_FILE').map(asset => (
                                    <li key={asset.id}><a href={asset.url}>{asset.fileName}</a></li>
                                ))}
                            </ul>
                        </section>
                    )}
                    <p className="revision-stamp">REV {String(page.revisionNumber).padStart(4, '0')}</p>
                </aside>
            </div>
        </PublicFrame>
    );
}

function seoDescription(value = '') {
    return value.replace(/\s+/g, ' ').trim().slice(0, 300);
}
