import os
import torch
from transformers import pipeline


class NLPService:

    def __init__(self):
        print("Configuring lightweight single-thread CPU execution...")
        torch.set_num_threads(1)

        print("Loading DistilRoBERTa emotion model...")
        try:
            self.emotion_model = pipeline(
                "text-classification",
                model="j-hartmann/emotion-english-distilroberta-base",
                top_k=None,
                device="cpu"
            )

            # Apply dynamic int8 quantization to compress linear layers (~85MB in RAM)
            try:
                self.emotion_model.model = torch.quantization.quantize_dynamic(
                    self.emotion_model.model,
                    {torch.nn.Linear},
                    dtype=torch.qint8
                )
                print("Dynamic int8 quantization applied successfully.")
            except Exception as q_err:
                print(f"Quantization skipped: {q_err}")

            print("NLP model loaded successfully into low-memory footprint.")
            self.model_loaded = True
        except Exception as e:
            print(f"Warning: Failed to load DistilRoBERTa model ({e}). Using lightweight rule-based NLP fallback.")
            self.emotion_model = None
            self.model_loaded = False

    def analyze(self, text: str):
        clean_text = text.strip() if text else ""
        if not clean_text:
            clean_text = "neutral"

        if not self.model_loaded or self.emotion_model is None:
            return self._fallback_analyze(clean_text)

        try:
            # -----------------------------------------
            # EMOTION ANALYSIS (DistilRoBERTa)
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

            primary_emotion = (
                formatted_emotions[0]
                if formatted_emotions
                else {
                    "label": "unknown",
                    "score": 0.0
                }
            )

            # -----------------------------------------
            # SENTIMENT DERIVATION (Zero extra RAM)
            # -----------------------------------------
            scores = {item["label"]: float(item["score"]) for item in emotion_results}
            joy = scores.get("joy", 0.0)
            surprise = scores.get("surprise", 0.0)
            neutral = scores.get("neutral", 0.0)
            sadness = scores.get("sadness", 0.0)
            anger = scores.get("anger", 0.0)
            fear = scores.get("fear", 0.0)
            disgust = scores.get("disgust", 0.0)

            pos_weight = joy + (surprise * 0.4)
            neg_weight = sadness + anger + fear + disgust
            neu_weight = neutral + (surprise * 0.6)

            if pos_weight >= neg_weight and pos_weight >= neu_weight:
                sentiment_label = "positive"
                sentiment_score = round(min(pos_weight, 1.0), 4)
            elif neg_weight >= pos_weight and neg_weight >= neu_weight:
                sentiment_label = "negative"
                sentiment_score = round(min(neg_weight, 1.0), 4)
            else:
                sentiment_label = "neutral"
                sentiment_score = round(min(neu_weight, 1.0), 4)

            sentiment = {
                "label": sentiment_label,
                "score": sentiment_score
            }

            return {
                "sentiment": sentiment,
                "primary_emotion": primary_emotion,
                "emotions": formatted_emotions
            }

        except Exception as e:
            print(f"Error during transformer inference ({e}), falling back to lexicon analysis.")
            return self._fallback_analyze(clean_text)

    def _fallback_analyze(self, text: str):
        lower = text.lower()

        # Simple lexicon rules for defensive fallback under memory limits
        sad_words = ["sad", "depressed", "unhappy", "hopeless", "crying", "grief", "miserable", "hurt"]
        fear_words = ["scared", "afraid", "fear", "anxious", "anxiety", "worried", "panic", "terrified", "nervous"]
        anger_words = ["angry", "mad", "furious", "hate", "frustrated", "annoyed", "rage"]
        joy_words = ["happy", "glad", "joy", "excited", "wonderful", "great", "delighted", "love", "good"]

        if any(w in lower for w in fear_words):
            top_label = "fear"
            sent_label = "negative"
        elif any(w in lower for w in sad_words):
            top_label = "sadness"
            sent_label = "negative"
        elif any(w in lower for w in anger_words):
            top_label = "anger"
            sent_label = "negative"
        elif any(w in lower for w in joy_words):
            top_label = "joy"
            sent_label = "positive"
        else:
            top_label = "neutral"
            sent_label = "neutral"

        primary_emotion = {"label": top_label, "score": 0.85}
        sentiment = {"label": sent_label, "score": 0.85}
        emotions = [
            primary_emotion,
            {"label": "neutral" if top_label != "neutral" else "joy", "score": 0.15}
        ]

        return {
            "sentiment": sentiment,
            "primary_emotion": primary_emotion,
            "emotions": emotions
        }