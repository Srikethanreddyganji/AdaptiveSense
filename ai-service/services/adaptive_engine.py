import re


class AdaptiveSocialCueEngine:

    def analyze(
            self,
            text: str,
            nlp_result: dict,
            previous_conversation=None,
            previous_emotion=None):

        text_lower = text.lower()

        # -----------------------------------------
        # PREVIOUS CONTEXT
        # -----------------------------------------

        previous_text = ""

        if previous_conversation:

            if isinstance(
                    previous_conversation,
                    list):

                parts = []

                for item in previous_conversation:

                    if isinstance(item, dict):

                        parts.append(
                            str(item.get("user", ""))
                        )

                        parts.append(
                            str(item.get("assistant", ""))
                        )

                    else:

                        parts.append(str(item))

                previous_text = " ".join(parts)

            else:

                previous_text = str(
                    previous_conversation
                )

        previous_text_lower = (
            previous_text.lower()
        )

        # -----------------------------------------
        # NLP SIGNALS
        # -----------------------------------------

        sentiment_data = nlp_result.get(
            "sentiment",
            {}
        )

        sentiment = sentiment_data.get(
            "label",
            "unknown"
        ).lower()

        sentiment_score = float(
            sentiment_data.get(
                "score",
                0.0
            )
        )

        primary_emotion = nlp_result.get(
            "primary_emotion",
            {}
        )

        top_emotion = primary_emotion.get(
            "label",
            "unknown"
        )

        top_emotion_score = float(
            primary_emotion.get(
                "score",
                0.0
            )
        )

        emotions = nlp_result.get(
            "emotions",
            []
        )

        # -----------------------------------------
        # PREVIOUS EMOTION
        # -----------------------------------------

        previous_emotion_label = "unknown"

        if isinstance(
                previous_emotion,
                dict):

            previous_emotion_label = str(
                previous_emotion.get(
                    "emotion",
                    previous_emotion.get(
                        "dominant_emotion",
                        "unknown"
                    )
                )
            ).lower()

        # -----------------------------------------
        # SOCIAL SIGNAL KEYWORDS
        # -----------------------------------------

        loneliness_words = [
            "alone",
            "lonely",
            "isolated",
            "nobody",
            "no one",
            "no friends",
            "by myself",
            "feel left out",
            "feel unwanted",
            "have nobody"
        ]

        withdrawal_phrases = [
            "don't want to meet",
            "dont want to meet",
            "avoid people",
            "stay away",
            "don't want to talk",
            "dont want to talk",
            "stop talking",
            "stopped talking",
            "don't want to go out",
            "dont want to go out",
            "avoiding everyone",
            "avoid everyone",
            "cut everyone off",
            "don't feel like talking",
            "dont feel like talking"
        ]

        distress_words = [
            "hopeless",
            "worthless",
            "overwhelmed",
            "exhausted",
            "can't cope",
            "cannot cope",
            "breaking down",
            "miserable",
            "helpless",
            "desperate",
            "can't handle",
            "cannot handle",
            "too much",
            "falling apart"
        ]

        severe_distress_phrases = [
            "can't go on",
            "cannot go on",
            "don't want to live",
            "dont want to live",
            "want to die",
            "kill myself",
            "killing myself",
            "end my life",
            "suicide",
            "suicidal",
            "no reason to live",
            "better off dead",
            "hurt myself",
            "self harm",
            "self-harm"
        ]

        positive_words = [
            "happy",
            "great",
            "good",
            "excited",
            "wonderful",
            "amazing",
            "joy",
            "love",
            "grateful"
        ]

        # -----------------------------------------
        # HELPER FOR WORD BOUNDARY DETECTION
        # -----------------------------------------

        def matches_any(phrases, src):
            for p in phrases:
                if re.search(r'(?:\b|^)' + re.escape(p) + r'(?:\b|$)', src):
                    return True
            return False

        # -----------------------------------------
        # SOCIAL CUE DETECTION
        # -----------------------------------------

        loneliness = matches_any(loneliness_words, text_lower)
        social_withdrawal = matches_any(withdrawal_phrases, text_lower)
        distress = matches_any(distress_words, text_lower)
        severe_distress = matches_any(severe_distress_phrases, text_lower)
        positive_signal = matches_any(positive_words, text_lower)

        # -----------------------------------------
        # PREVIOUS CONVERSATION SIGNALS
        # -----------------------------------------

        previous_loneliness = matches_any(loneliness_words, previous_text_lower)
        previous_withdrawal = matches_any(withdrawal_phrases, previous_text_lower)
        previous_distress = matches_any(distress_words, previous_text_lower)
        previous_severe_distress = matches_any(severe_distress_phrases, previous_text_lower)

        # -----------------------------------------
        # EMOTION SIGNALS
        # -----------------------------------------

        top_emotion_lower = top_emotion.lower()

        sadness_signal = (
            top_emotion_lower == "sadness"
        )

        fear_signal = (
            top_emotion_lower == "fear"
        )

        anger_signal = (
            top_emotion_lower == "anger"
        )

        anxiety_signal = (
            top_emotion_lower == "anxiety"
            or top_emotion_lower == "anxiety-like"
            or top_emotion_lower == "fear"
        )

        # -----------------------------------------
        # SOCIAL CUE SCORE
        # -----------------------------------------

        social_cue_score = 0

        if loneliness:
            social_cue_score += 1

        if social_withdrawal:
            social_cue_score += 1

        if sadness_signal:
            social_cue_score += 1

        if anxiety_signal or fear_signal:
            social_cue_score += 1

        if anger_signal:
            social_cue_score += 1

        # Previous context contributes
        # additional contextual information.

        if previous_loneliness:
            social_cue_score += 1

        if previous_withdrawal:
            social_cue_score += 1

        # -----------------------------------------
        # DISTRESS SCORE
        # -----------------------------------------

        distress_score = 0

        if distress:
            distress_score += 2

        if severe_distress:
            distress_score += 4

        if sadness_signal:
            distress_score += 1

        if anxiety_signal or fear_signal:
            distress_score += 1

        if anger_signal:
            distress_score += 1

        if social_withdrawal:
            distress_score += 1

        # Previous conversation context

        if previous_distress:
            distress_score += 1

        if previous_severe_distress:
            distress_score += 2

        # -----------------------------------------
        # DETERMINE CUE LEVEL
        # -----------------------------------------

        if severe_distress:

            cue_level = "critical"

        elif distress_score >= 4:

            cue_level = "high"

        elif (
                social_cue_score >= 2
                or distress_score >= 2):

            cue_level = "moderate"

        elif (
                loneliness
                or social_withdrawal
                or distress
                or sentiment == "negative"
                or previous_loneliness
                or previous_withdrawal
                or previous_distress
        ):

            cue_level = "mild"

        else:

            cue_level = "low"

        # -----------------------------------------
        # DETERMINE RESPONSE STRATEGY
        # -----------------------------------------

        if severe_distress:

            response_strategy = (
                "safety_focused_support"
            )

        elif distress:

            response_strategy = (
                "empathetic_support"
            )

        elif loneliness or social_withdrawal:

            response_strategy = (
                "social_connection_support"
            )

        elif (
                sadness_signal
                or anxiety_signal
                or fear_signal
                or anger_signal
        ):

            response_strategy = (
                "emotional_support"
            )

        elif positive_signal or sentiment == "positive":

            response_strategy = (
                "positive_conversation"
            )

        elif sentiment == "negative":

            response_strategy = (
                "emotional_support"
            )

        else:

            response_strategy = (
                "normal_conversation"
            )

        # -----------------------------------------
        # RISK LEVEL (for backend persistence)
        # -----------------------------------------

        if severe_distress:

            risk_level = "Elevated"

        elif distress_score >= 4:

            risk_level = "Elevated"

        elif distress_score >= 2:

            risk_level = "Moderate"

        else:

            risk_level = "Low"

        if severe_distress:

            distress_level = "High"

        elif distress_score >= 4:

            distress_level = "High"

        elif distress_score >= 2:

            distress_level = "Moderate"

        else:

            distress_level = "Low"

        # -----------------------------------------
        # RETURN RESULT
        # -----------------------------------------

        return {

            "sentiment": sentiment,

            "sentiment_score": round(
                sentiment_score,
                4
            ),

            "dominant_emotion": top_emotion,

            "emotion_score": round(
                top_emotion_score,
                4
            ),

            "emotions": emotions,

            "previous_emotion":
                previous_emotion_label,

            "loneliness": loneliness,

            "social_withdrawal":
                social_withdrawal,

            "distress": distress,

            "severe_distress":
                severe_distress,

            "positive_signal":
                positive_signal,

            "sadness_signal":
                sadness_signal,

            "anxiety_signal":
                anxiety_signal,

            "fear_signal":
                fear_signal,

            "anger_signal":
                anger_signal,

            "social_cue_score":
                social_cue_score,

            "distress_score":
                distress_score,

            "cue_level":
                cue_level,

            "response_strategy":
                response_strategy,

            "riskLevel":
                risk_level,

            "distressLevel":
                distress_level
        }