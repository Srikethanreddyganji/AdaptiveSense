export function chatRequestErrorMessage(error) {
    const status = error?.response?.status;
    if (!status) return "Could not reach AdaptiveSense. Check your connection and try again.";

    switch (status) {
        case 401:
            return "Your session expired. Please sign in again.";
        case 403:
            return "You are not allowed to send this message.";
        case 413:
            return "This message is too large. Please shorten it and try again.";
        case 429:
            return "AdaptiveSense is busy. Please wait a moment and try again.";
        case 502:
        case 503:
        case 504:
            return "The response service is temporarily unavailable. Your message was not answered; please try again shortly.";
        default:
            return "AdaptiveSense could not send this message. Please try again.";
    }
}
