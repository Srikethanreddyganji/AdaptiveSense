import os
import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification

EMOTION_LABELS = ["anger", "disgust", "fear", "joy", "neutral", "sadness", "surprise"]
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUANTIZED_DIR = os.path.join(BASE_DIR, "model_quantized")
QUANTIZED_MODEL_PATH = os.path.join(QUANTIZED_DIR, "model_quantized.pt")


class NLPService:

    def __init__(self):
        print("Configuring single-thread execution for minimal memory footprint...")
        torch.set_num_threads(1)

        self.tokenizer = None
        self.model = None
        self.model_loaded = False

        # 1. Attempt to load ahead-of-time pre-quantized TorchScript model (~190MB)
        if os.path.exists(QUANTIZED_MODEL_PATH) and os.path.exists(QUANTIZED_DIR):
            try:
                print(f"Loading pre-quantized model from {QUANTIZED_MODEL_PATH}...")
                self.tokenizer = AutoTokenizer.from_pretrained(QUANTIZED_DIR, local_files_only=True)
                self.model = torch.jit.load(QUANTIZED_MODEL_PATH, map_location="cpu")
                self.model.eval()
                self.model_loaded = True
                print("Pre-quantized INT8 NLP model loaded successfully into low-memory footprint.")
                return
            except Exception as e:
                print(f"Warning: Failed to load pre-quantized model ({e}). Attempting dynamic fallback.")

        # 2. Fallback to on-demand lightweight load + quantization
        try:
            model_name = "j-hartmann/emotion-english-distilroberta-base"
            print(f"Loading {model_name} on-demand with low CPU memory usage...")
            self.tokenizer = AutoTokenizer.from_pretrained(model_name)
            base_model = AutoModelForSequenceClassification.from_pretrained(
                model_name,
                low_cpu_mem_usage=True
            )
            base_model.eval()

            try:
                self.model = torch.quantization.quantize_dynamic(
                    base_model,
                    {torch.nn.Linear},
                    dtype=torch.qint8
                )
                self.model.eval()
                print("Dynamic INT8 quantization applied successfully.")
            except Exception as q_err:
                print(f"Quantization skipped: {q_err}")
                self.model = base_model

            self.model_loaded = True
            print("NLP model loaded successfully.")
        except Exception as e:
            print(f"Warning: Failed to load transformer model ({e}). Using defensive lexicon NLP fallback.")
            self.model = None
            self.tokenizer = None
            self.model_loaded = False

    def analyze(self, text: str):
        clean_text = text.strip() if text else ""
        if not clean_text:
            clean_text = "neutral"

        if not self.model_loaded or self.model is None or self.tokenizer is None:
            return self._fallback_analyze(clean_text)

        try:
            # -----------------------------------------
            # EMOTION ANALYSIS (Quantized Model)
            # -----------------------------------------
            inputs = self.tokenizer(
                clean_text,
                truncation=True,
                max_length=512,
                return_tensors="pt"
            )

            with torch.no_grad():
                out = self.model(inputs["input_ids"], inputs["attention_mask"])
                logits = out["logits"] if isinstance(out, dict) else (out.logits if hasattr(out, "logits") else out[0])
                probs = torch.softmax(logits, dim=-1)[0].tolist()

            emotion_results = [
                {
                    "label": EMOTION_LABELS[i],
                    "score": round(float(probs[i]), 4)
                }
                for i in range(len(EMOTION_LABELS))
            ]

            emotions = sorted(
                emotion_results,
                key=lambda x: x["score"],
                reverse=True
            )

            top_emotions = emotions[:5]

            formatted_emotions = [
                {
                    "label": emotion["label"],
                    "score": round(float(emotion["score"]), 4)
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