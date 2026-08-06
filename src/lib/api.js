const API_ROOT = (import.meta.env.VITE_API_URL ?? '/api/v1').replace(/\/$/, '');

let accessToken = null;
let refreshInFlight = null;

export class ApiError extends Error {
    constructor(status, payload) {
        super(payload?.message ?? `Request failed with status ${status}`);
        this.status = status;
        this.code = payload?.code ?? 'REQUEST_FAILED';
        this.fields = payload?.fields ?? null;
    }
}

export function setAccessToken(token) {
    accessToken = token;
}

export function readCookie(name) {
    return document.cookie.split('; ')
        .find(item => item.startsWith(`${name}=`))
        ?.slice(name.length + 1) ?? null;
}

async function parseResponse(response) {
    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') ?? '';
    return contentType.includes('application/json') ? response.json() : response.text();
}

async function rawRequest(path, options = {}) {
    assertProtectedTransport(path, options);
    const auth = options.auth;
    const requestOptions = { ...options };
    delete requestOptions.auth;
    delete requestOptions.noRetry;
    delete requestOptions.sensitive;
    const headers = new Headers(options.headers);
    if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }
    if (auth && accessToken) headers.set('Authorization', `Bearer ${accessToken}`);

    const response = await fetch(`${API_ROOT}${path}`, {
        ...requestOptions,
        headers,
        credentials: 'include'
    });
    const payload = await parseResponse(response);
    if (!response.ok) throw new ApiError(response.status, payload);
    return payload;
}

export async function refreshSession() {
    if (!readCookie('XSRF-TOKEN')) return null;
    if (!refreshInFlight) {
        refreshInFlight = rawRequest('/auth/refresh', {
            method: 'POST',
            headers: { 'X-XSRF-TOKEN': readCookie('XSRF-TOKEN') }
        }).then(session => {
            setAccessToken(session.accessToken);
            return session;
        }).catch(() => {
            setAccessToken(null);
            return null;
        }).finally(() => {
            refreshInFlight = null;
        });
    }
    return refreshInFlight;
}

export async function api(path, options = {}) {
    try {
        return await rawRequest(path, options);
    } catch (error) {
        if (options.auth && error instanceof ApiError && error.status === 401 && !options.noRetry) {
            const session = await refreshSession();
            if (session) return rawRequest(path, { ...options, noRetry: true });
        }
        throw error;
    }
}

async function rawDownload(path, options = {}) {
    assertProtectedTransport(path, options);
    const requestOptions = { ...options };
    delete requestOptions.auth;
    delete requestOptions.noRetry;
    delete requestOptions.sensitive;
    const headers = new Headers(options.headers);
    if (options.auth && accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    const response = await fetch(`${API_ROOT}${path}`, {
        ...requestOptions,
        headers,
        credentials: 'include'
    });
    if (!response.ok) {
        const payload = await parseResponse(response);
        throw new ApiError(response.status, payload);
    }
    return {
        blob: await response.blob(),
        fileName: downloadFileName(response.headers.get('content-disposition'))
    };
}

export async function apiDownload(path, options = {}) {
    try {
        return await rawDownload(path, options);
    } catch (error) {
        if (options.auth && error instanceof ApiError && error.status === 401 && !options.noRetry) {
            const session = await refreshSession();
            if (session) return rawDownload(path, { ...options, noRetry: true });
        }
        throw error;
    }
}

export async function login(username, password) {
    const session = await rawRequest('/auth/login', {
        method: 'POST',
        sensitive: true,
        body: JSON.stringify({ username, password })
    });
    setAccessToken(session.accessToken);
    return session;
}

export async function logout() {
    const csrf = readCookie('XSRF-TOKEN');
    try {
        if (csrf) await rawRequest('/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': csrf } });
    } finally {
        setAccessToken(null);
    }
}

export async function uploadToSignedUrl(ticket, file) {
    const headers = new Headers();
    Object.entries(ticket.headers ?? {}).forEach(([name, values]) => {
        const lower = name.toLowerCase();
        if (lower !== 'host' && lower !== 'content-length') {
            headers.set(name, Array.isArray(values) ? values.join(',') : values);
        }
    });
    const response = await fetch(ticket.uploadUrl, { method: 'PUT', headers, body: file });
    if (!response.ok) throw new ApiError(response.status, { message: 'Storage rejected the upload.', code: 'STORAGE_UPLOAD_FAILED' });
}

function assertProtectedTransport(path, options) {
    if (!options.auth && !options.sensitive && !path.startsWith('/auth/')) return;
    const target = new URL(`${API_ROOT}${path}`, window.location.origin);
    if (target.protocol === 'https:' || isLoopback(target.hostname)) return;
    throw new ApiError(0, {
        code: 'INSECURE_AUTH_TRANSPORT',
        message: 'Protected requests require HTTPS. Login was blocked before credentials left this browser.'
    });
}

function isLoopback(hostname) {
    return hostname === 'localhost'
        || hostname.endsWith('.localhost')
        || hostname === '[::1]'
        || /^127(?:\.\d{1,3}){3}$/.test(hostname);
}

function downloadFileName(disposition) {
    if (!disposition) return 'new-dawn-wiki-backup.zip';
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
    if (encoded) {
        try {
            return decodeURIComponent(encoded.replace(/^"|"$/g, ''));
        } catch {
            // Fall through to the ASCII filename supplied by the server.
        }
    }
    return disposition.match(/filename="?([^";]+)"?/i)?.[1] ?? 'new-dawn-wiki-backup.zip';
}

export async function sha256(file) {
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
    return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('');
}
