export function clearSession() {

    localStorage.removeItem("token");
    localStorage.removeItem("userId");
    localStorage.removeItem("userName");
    localStorage.removeItem("userEmail");
}

export function isAuthenticated() {

    return Boolean(
        localStorage.getItem("token")
    );
}
