from services.runtime_config import configured_torch_threads


def test_torch_thread_limit_is_bounded_by_available_cpu(monkeypatch):
    monkeypatch.setenv("AI_TORCH_THREADS", "8")
    monkeypatch.setattr("services.runtime_config.os.cpu_count", lambda: 2)

    assert configured_torch_threads() == 2


def test_invalid_torch_thread_limit_uses_safe_default(monkeypatch):
    monkeypatch.setenv("AI_TORCH_THREADS", "not-a-number")
    monkeypatch.setattr("services.runtime_config.os.cpu_count", lambda: 8)

    assert configured_torch_threads() == 2
