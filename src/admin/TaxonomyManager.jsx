import { useEffect, useMemo, useState } from 'react';
import { Pencil, Plus, Save, X } from 'lucide-react';
import { api } from '../lib/api';

const emptyCategory = { id: null, slug: '', name: '', description: '', parentId: '', sortOrder: 0 };

export default function TaxonomyManager() {
    const [categories, setCategories] = useState([]);
    const [category, setCategory] = useState(emptyCategory);
    const [error, setError] = useState('');
    const [message, setMessage] = useState('');
    const [busy, setBusy] = useState(false);

    async function load() {
        setCategories(await api('/wiki/categories', { auth: true }));
    }

    useEffect(() => { load().catch(requestError => setError(requestError.message)); }, []);

    async function saveCategory(event) {
        event.preventDefault();
        setBusy(true);
        setError('');
        try {
            const body = { ...category, id: undefined, parentId: category.parentId || null, sortOrder: Number(category.sortOrder) };
            await api(category.id ? `/admin/wiki/categories/${category.id}` : '/admin/wiki/categories', {
                method: category.id ? 'PUT' : 'POST', auth: true, body: JSON.stringify(body)
            });
            setMessage(category.id ? 'Category updated.' : 'Category created.');
            setCategory(emptyCategory);
            await load();
        } catch (requestError) {
            setError(requestError.message);
        } finally {
            setBusy(false);
        }
    }

    function editCategory(item) {
        setCategory({
            id: item.id,
            slug: item.slug,
            name: item.name,
            description: item.description ?? '',
            parentId: item.parentId ?? '',
            sortOrder: item.sortOrder
        });
        setMessage('');
        setError('');
    }

    function updateName(name) {
        setCategory(current => {
            const generatedFromCurrentName = slugify(current.name);
            const updateSlug = !current.id && (!current.slug || current.slug === generatedFromCurrentName);
            return { ...current, name, slug: updateSlug ? slugify(name) : current.slug };
        });
    }

    const rootCategories = useMemo(() => categories.filter(item => !item.parentId), [categories]);

    return (
        <div className="simple-manager">
            <header><h2>Categories</h2></header>
            {message && <p className="terminal-alert" role="status">{message}</p>}
            {error && <p className="terminal-alert terminal-alert--error" role="alert">{error}</p>}
            <div className="taxonomy-columns">
                <section>
                    <h3>{category.id ? 'Edit category' : 'Create category'}</h3>
                    <form onSubmit={saveCategory} className="stack-form">
                        <label className="terminal-field"><span>Name</span><input required value={category.name} onChange={event => updateName(event.target.value)} /></label>
                        <label className="terminal-field"><span>Level</span><select value={category.parentId} onChange={event => setCategory({ ...category, parentId: event.target.value })}><option value="">Main category</option>{rootCategories.filter(item => item.id !== category.id).map(item => <option key={item.id} value={item.id}>Subcategory of {item.name}</option>)}</select></label>
                        <label className="terminal-field"><span>Description (optional)</span><textarea rows="3" value={category.description} onChange={event => setCategory({ ...category, description: event.target.value })} /></label>
                        <details className="form-options">
                            <summary>URL and order</summary>
                            <div className="form-options-fields">
                                <label className="terminal-field"><span>URL</span><input required pattern="[a-z0-9]+(?:-[a-z0-9]+)*" value={category.slug} onChange={event => setCategory({ ...category, slug: event.target.value.toLowerCase() })} /></label>
                                <label className="terminal-field"><span>Sort order</span><input type="number" min="0" max="10000" value={category.sortOrder} onChange={event => setCategory({ ...category, sortOrder: event.target.value })} /></label>
                            </div>
                        </details>
                        <div className="taxonomy-form-actions">
                            <button className="terminal-action" type="submit" disabled={busy}>{category.id ? <Save size={17} /> : <Plus size={17} />}{category.id ? 'Save category' : 'Add category'}</button>
                            {category.id && <button type="button" onClick={() => setCategory(emptyCategory)} disabled={busy}><X size={17} /> Cancel</button>}
                        </div>
                    </form>

                </section>
                <section>
                    <h3>Structure</h3>
                    <div className="taxonomy-tree" aria-label="Wiki category hierarchy">
                        {rootCategories.map(root => (
                            <section className="taxonomy-root" key={root.id}>
                                <CategoryRow category={root} onEdit={editCategory} />
                                <div className="taxonomy-children">
                                    {categories.filter(item => item.parentId === root.id).map(child => <CategoryRow category={child} onEdit={editCategory} key={child.id} />)}
                                    {!categories.some(item => item.parentId === root.id) && <p>No subcategories yet.</p>}
                                </div>
                            </section>
                        ))}
                        {!rootCategories.length && <p className="module-empty">Create the first main category.</p>}
                    </div>
                </section>
            </div>
        </div>
    );
}

function slugify(value) {
    return value
        .toLowerCase()
        .replaceAll('ł', 'l')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
}

function CategoryRow({ category, onEdit }) {
    return (
        <div className="taxonomy-category-row">
            <div><strong>{category.name}</strong>{category.description && <p>{category.description}</p>}</div>
            <button type="button" onClick={() => onEdit(category)} aria-label={`Edit ${category.name}`}><Pencil size={16} /> Edit</button>
        </div>
    );
}
