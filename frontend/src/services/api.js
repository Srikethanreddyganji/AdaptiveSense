import axios from "axios";

const api = axios.create({
    baseURL: "https://adaptivesense.onrender.com/api"
});

export default api;