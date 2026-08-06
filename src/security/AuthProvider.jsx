import { useCallback, useEffect, useMemo, useState } from 'react';
import { login as loginRequest, logout as logoutRequest, refreshSession } from '../lib/api';
import { AuthContext } from './auth-context';

export function AuthProvider({ children }) {
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let current = true;
        refreshSession().then(session => {
            if (current) {
                setUser(session?.user ?? null);
                setLoading(false);
            }
        });
        return () => { current = false; };
    }, []);

    const login = useCallback(async (username, password) => {
        const session = await loginRequest(username, password);
        setUser(session.user);
        return session.user;
    }, []);

    const logout = useCallback(async () => {
        await logoutRequest();
        setUser(null);
    }, []);

    const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout]);
    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
