const SITE_NAME = 'FOnline: New Dawn';
const SITE_URL = 'https://fonline-nd.com';
const SOCIAL_IMAGE = `${SITE_URL}/branding/new-dawn-logo-square.png`;

const HOME_DESCRIPTION = 'Official website of FOnline: New Dawn. Explore the game wiki, follow release information, and download the official client.';

const ROUTE_SEO = Object.freeze({
    home: {
        title: `${SITE_NAME} — Official Website`,
        description: HOME_DESCRIPTION,
        canonicalPath: '/'
    },
    wiki: {
        title: `Wiki — ${SITE_NAME}`,
        description: `Browse the official ${SITE_NAME} wiki for game information, guides, and reference material.`,
        canonicalPath: '/wiki'
    },
    article: {
        title: `Wiki Article — ${SITE_NAME}`,
        description: `Read an article from the official ${SITE_NAME} wiki.`
    },
    download: {
        title: `Download — ${SITE_NAME}`,
        description: `Download the official ${SITE_NAME} game client and review available releases.`,
        canonicalPath: '/download'
    }
});

export function applyRouteSeo(pathname = window.location.pathname) {
    const normalizedPath = pathname.replace(/\/+$/, '') || '/';

    if (normalizedPath === '/') return applySeo(ROUTE_SEO.home);
    if (normalizedPath === '/wiki') return applySeo(ROUTE_SEO.wiki);
    if (normalizedPath.startsWith('/wiki/')) {
        return applySeo({ ...ROUTE_SEO.article, canonicalPath: normalizedPath });
    }
    if (normalizedPath === '/download') return applySeo(ROUTE_SEO.download);
    if (normalizedPath === '/admin' || normalizedPath.startsWith('/admin/')) {
        return applySeo({
            title: `Staff Access — ${SITE_NAME}`,
            description: 'Private staff access for the FOnline: New Dawn website.',
            robots: 'noindex, nofollow'
        });
    }

    return applySeo({
        title: `Page Not Found — ${SITE_NAME}`,
        description: 'The requested page could not be found.',
        robots: 'noindex, nofollow'
    });
}

export function applySeo({
    title,
    description,
    canonicalPath,
    robots = 'index, follow, max-image-preview:large',
    type = 'website'
}) {
    document.title = title;
    setMeta('name', 'description', description);
    setMeta('name', 'robots', robots);
    setMeta('property', 'og:site_name', SITE_NAME);
    setMeta('property', 'og:title', title);
    setMeta('property', 'og:description', description);
    setMeta('property', 'og:type', type);
    setMeta('property', 'og:image', SOCIAL_IMAGE);
    setMeta('name', 'twitter:card', 'summary');
    setMeta('name', 'twitter:title', title);
    setMeta('name', 'twitter:description', description);
    setMeta('name', 'twitter:image', SOCIAL_IMAGE);

    const canonicalUrl = canonicalPath ? new URL(canonicalPath, SITE_URL).href : null;
    setCanonical(canonicalUrl);
    setMeta('property', 'og:url', canonicalUrl);
    setWebsiteStructuredData(canonicalPath === '/');
}

export function noindexCurrentPage(title, description) {
    applySeo({ title, description, robots: 'noindex, follow' });
}

function setMeta(attribute, key, content) {
    const selector = `meta[${attribute}="${key}"]`;
    let element = document.head.querySelector(selector);

    if (!content) {
        element?.remove();
        return;
    }

    if (!element) {
        element = document.createElement('meta');
        element.setAttribute(attribute, key);
        document.head.appendChild(element);
    }
    element.setAttribute('content', content);
}

function setCanonical(url) {
    let element = document.head.querySelector('link[rel="canonical"]');
    if (!url) {
        element?.remove();
        return;
    }
    if (!element) {
        element = document.createElement('link');
        element.setAttribute('rel', 'canonical');
        document.head.appendChild(element);
    }
    element.setAttribute('href', url);
}

function setWebsiteStructuredData(enabled) {
    const id = 'website-structured-data';
    let element = document.getElementById(id);
    if (!enabled) {
        element?.remove();
        return;
    }
    if (!element) {
        element = document.createElement('script');
        element.id = id;
        element.type = 'application/ld+json';
        document.head.appendChild(element);
    }
    element.textContent = JSON.stringify({
        '@context': 'https://schema.org',
        '@type': 'WebSite',
        name: SITE_NAME,
        alternateName: 'FOnline New Dawn',
        url: `${SITE_URL}/`
    });
}
