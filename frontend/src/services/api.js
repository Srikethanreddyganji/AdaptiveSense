import axios from "axios";
import { clearSession } from "../utils/session";

const baseURL =
    import.meta.env.VITE_API_URL ||
    "http://localhost:8080/api";

const api = axios.create({
    baseURL
});

api.interceptors.request.use((config) => {

    const token =
        localStorage.getItem("token");

    if (token) {

        config.headers.Authorization =
            `Bearer ${token}`;
    }

    return config;
});

api.interceptors.response.use(
    (response) => response,
    (error) => {

        if (error.response?.status === 401) {

            clearSession();

            if (window.location.pathname !== "/login") {

                window.location.assign("/login");
            }
        }

        return Promise.reject(error);
    }
);

export default api;
