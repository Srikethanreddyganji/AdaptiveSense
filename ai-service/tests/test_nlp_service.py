from services.nlp_service import NLPService


def test_nlp_service_analyze_positive():
    service = NLPService()
    result = service.analyze("I am having such a joyful and wonderful day!")
    assert "sentiment" in result
    assert "primary_emotion" in result
    assert "emotions" in result
    assert result["primary_emotion"]["label"] in ["joy", "surprise"]
    assert result["sentiment"]["label"] == "positive"


def test_nlp_service_analyze_negative():
    service = NLPService()
    result = service.analyze("I feel so terrified, alone, and heartbroken.")
    assert "sentiment" in result
    assert "primary_emotion" in result
    assert result["sentiment"]["label"] == "negative"
    assert result["primary_emotion"]["label"] in ["fear", "sadness", "anger", "disgust"]


def test_nlp_service_fallback():
    service = NLPService()
    result = service._fallback_analyze("I am happy and good")
    assert result["sentiment"]["label"] == "positive"
    assert result["primary_emotion"]["label"] == "joy"
