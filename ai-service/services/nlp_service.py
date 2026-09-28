from transformers import pipeline


class NLPService:

    def __init__(self):
        print("Loading RoBERTa sentiment model...")

        self.sentiment_model = pipeline(
            "sentiment-analysis",
            model="cardiffnlp/twitter-roberta-base-sentiment-latest"
        )

        print("Loading emotion model...")

        self.emotion_model = pipeline(
            "text-classification",
            model="j-hartmann/emotion-english-distilroberta-base",
            top_k=None
        )

        print("NLP models loaded successfully.")

    def analyze(self, text: str):

        clean_text = text.strip() if text else ""
        if not clean_text:
            clean_text = "neutral"

        # -----------------------------------------
        # SENTIMENT ANALYSIS
        # -----------------------------------------

        sentiment_result = self.sentiment_model(
            clean_text,
            truncation=True,
            max_length=512
        )[0]

        sentiment = {
            "label": sentiment_result["label"],
            "score": round(
                float(sentiment_result["score"]),
                4
            )
        }

        # -----------------------------------------
        # EMOTION ANALYSIS
        # -----------------------------------------

        emotion_results = self.emotion_model(
            clean_text,
            truncation=True,
            max_length=512
        )[0]

        emotions = sorted(
            emotion_results,
            key=lambda x: x["score"],
            reverse=True
        )

        top_emotions = emotions[:5]

        formatted_emotions = [
            {
                "label": emotion["label"],
                "score": round(
                    float(emotion["score"]),
                    4
                )
            }
            for emotion in top_emotions
        ]

        # -----------------------------------------
        # PRIMARY EMOTION
        # -----------------------------------------

        primary_emotion = (
            formatted_emotions[0]
            if formatted_emotions
            else {
                "label": "unknown",
                "score": 0.0
            }
        )

        # -----------------------------------------
        # FINAL NLP RESULT
        # -----------------------------------------

        return {
            "sentiment": sentiment,

            "primary_emotion": primary_emotion,

            "emotions": formatted_emotions
        }