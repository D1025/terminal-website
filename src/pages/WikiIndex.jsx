import { useEffect, useMemo, useState } from 'react';
import { ArrowLeft, FolderTree, Search } from 'lucide-react';
import PublicFrame from '../components/PublicFrame';
import { api } from '../lib/api';

export default function WikiIndex() {
    const params = new URLSearchParams(window.location.search);
    const [query, setQuery] = useState(params.get('q') ?? '');
    const [pages, setPages] = useState([]);
    const [categories, setCategories] = useState([]);
    const [loading, setLoading] = useState(true);
    const [navigationLoading, setNavigationLoading] = useState(true);
    const [error, setError] = useState('');
    const selectedCategory = params.get('category') ?? '';
    const activeQuery = params.get('q') ?? '';

    useEffect(() => {
        api('/wiki/categories', { auth: true })
            .then(setCategories)
            .catch(() => setError('The wiki index could not be loaded.'))
            .finally(() => setNavigationLoading(false));
    }, []);

    useEffect(() => {
        setLoading(true);
        const search = new URLSearchParams({ q: activeQuery, size: '60' });
        if (selectedCategory) search.set('category', selectedCategory);
        api(`/wiki/pages?${search}`, { auth: true })
            .then(setPages)
            .catch(() => setError('Search is temporarily unavailable.'))
            .finally(() => setLoading(false));
    }, [activeQuery, selectedCategory]);

    function submit(event) {
        event.preventDefault();
        const next = new URLSearchParams(params);
        query.trim() ? next.set('q', query.trim()) : next.delete('q');
        navigate(next);
    }

    function filter(name, value) {
        const next = new URLSearchParams(params);
        value ? next.set(name, value) : next.delete(name);
        navigate(next);
    }

    const rootCategories = useMemo(() => categories.filter(category => !category.parentId), [categories]);
    const activeCategory = categories.find(category => category.id === selectedCategory);
    const activeParent = activeCategory?.parentId ? categories.find(category => category.id === activeCategory.parentId) : null;
    const categoryBrowser = !activeQuery && (!activeCategory || !activeCategory.parentId);

    const actions = (
        <form className="wiki-search" role="search" onSubmit={submit}>
            <label className="terminal-field terminal-field--search">
                <span className="sr-only">Search the wiki</span>
                <Search size={19} aria-hidden="true" />
                <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search by phrase, name, or property..." />
            </label>
            <select aria-label="Category" value={selectedCategory} onChange={event => filter('category', event.target.value)}>
                <option value="">All main categories</option>
                {rootCategories.map(root => (
                    <optgroup label={root.name} key={root.id}>
                        <option value={root.id}>All in {root.name}</option>
                        {categories.filter(category => category.parentId === root.id).map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
                    </optgroup>
                ))}
            </select>
            <button className="terminal-action" type="submit">Search</button>
        </form>
    );

    return (
        <PublicFrame eyebrow="Public archive // category index" title="New Dawn Wiki" actions={actions}>
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}
            {navigationLoading ? (
                <div className="module-empty">READING CATEGORY TREE<span className="cursor">_</span></div>
            ) : categoryBrowser ? (
                <CategoryBrowser roots={rootCategories} categories={categories} activeRoot={activeCategory} />
            ) : (
                <ArticleResults pages={pages} loading={loading} activeCategory={activeCategory} activeParent={activeParent} />
            )}
        </PublicFrame>
    );
}

function CategoryBrowser({ roots, categories, activeRoot }) {
    const visibleRoots = activeRoot ? [activeRoot] : roots;
    return (
        <section className="wiki-category-browser" aria-label="Wiki categories">
            {activeRoot && <a className="back-link" href="/wiki"><ArrowLeft size={17} /> All main categories</a>}
            <div className="wiki-category-grid">
                {visibleRoots.map(root => {
                    const children = categories.filter(category => category.parentId === root.id);
                    return (
                        <article className="wiki-category-card" key={root.id}>
                            <div className="wiki-category-heading">
                                <FolderTree size={22} aria-hidden="true" />
                                <div><span>Main category</span><h2><a href={categoryHref(root.id)}>{root.name}</a></h2></div>
                            </div>
                            {root.description && <p>{root.description}</p>}
                            <div className="wiki-subcategory-list">
                                <span>Subcategories</span>
                                {children.length ? children.map(child => (
                                    <a href={categoryHref(child.id)} key={child.id}>
                                        <strong>{child.name}</strong>
                                        {child.description && <small>{child.description}</small>}
                                    </a>
                                )) : <p>No subcategories have been created yet.</p>}
                            </div>
                        </article>
                    );
                })}
            </div>
            {!visibleRoots.length && <div className="module-empty">NO WIKI CATEGORIES HAVE BEEN PUBLISHED YET.</div>}
        </section>
    );
}

function ArticleResults({ pages, loading, activeCategory, activeParent }) {
    if (loading) return <div className="module-empty">SEARCHING ARCHIVE<span className="cursor">_</span></div>;
    return (
        <section>
            {activeCategory?.parentId && (
                <div className="wiki-category-context">
                    <a className="back-link" href={categoryHref(activeParent?.id)}><ArrowLeft size={17} /> {activeParent?.name || 'Main categories'}</a>
                    <span>Subcategory</span>
                    <h2>{activeCategory.name}</h2>
                    {activeCategory.description && <p>{activeCategory.description}</p>}
                </div>
            )}
            {pages.length ? (
                <div className="wiki-grid">
                    {pages.map(page => (
                        <a className="wiki-card" href={`/wiki/${page.slug}`} key={page.id}>
                            <span className="wiki-card-type">{page.category || 'Uncategorized'}</span>
                            <h2>{page.title}</h2>
                            <p>{plainExcerpt(page.excerpt || page.summary)}</p>
                            <span className="wiki-card-open">&gt; OPEN RECORD</span>
                        </a>
                    ))}
                </div>
            ) : <div className="module-empty">NO ARTICLES MATCH THE SELECTED CRITERIA.</div>}
        </section>
    );
}

function categoryHref(categoryId) {
    return categoryId ? `/wiki?category=${encodeURIComponent(categoryId)}` : '/wiki';
}

function navigate(params) {
    const query = params.toString();
    window.location.assign(query ? `/wiki?${query}` : '/wiki');
}

function plainExcerpt(value = '') {
    return value.replace(/<\/?mark>/g, '').replace(/\s+/g, ' ').slice(0, 220);
}
