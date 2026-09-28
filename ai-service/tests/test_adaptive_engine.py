from services.adaptive_engine import AdaptiveSocialCueEngine


def test_severe_distress_sets_elevated_risk():

    engine = AdaptiveSocialCueEngine()

    nlp = {
        "sentiment": {"label": "negative", "score": 0.9},
        "primary_emotion": {"label": "sadness", "score": 0.88},
        "emotions": []
    }

    result = engine.analyze(
        "I want to end my life",
        nlp,
        [],
        {}
    )

    assert result["severe_distress"] is True
    assert result["riskLevel"] == "Elevated"
    assert result["distressLevel"] == "High"
    assert result["response_strategy"] == "safety_focused_support"


def test_goodbye_does_not_trigger_positive_signal():

    engine = AdaptiveSocialCueEngine()

    nlp = {
        "sentiment": {"label": "negative", "score": 0.7},
        "primary_emotion": {"label": "sadness", "score": 0.8},
        "emotions": []
    }

    result = engine.analyze(
        "I feel terrible, goodbye",
        nlp,
        [],
        {}
    )

    assert result["positive_signal"] is False


def test_fear_and_anger_signals_contribute_to_distress():

    engine = AdaptiveSocialCueEngine()

    nlp = {
        "sentiment": {"label": "negative", "score": 0.85},
        "primary_emotion": {"label": "fear", "score": 0.82},
        "emotions": []
    }

    result = engine.analyze(
        "I am trembling with panic",
        nlp,
        [],
        {}
    )

    assert result["fear_signal"] is True
    assert result["distress_score"] >= 1
    assert result["response_strategy"] == "emotional_support"
