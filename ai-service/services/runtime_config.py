import os


def configured_torch_threads() -> int:
    """Bound PyTorch intra-op work to the available CPUs and configured limit."""
    try:
        requested = int(os.getenv("AI_TORCH_THREADS", "2"))
    except (TypeError, ValueError):
        requested = 2
    return max(1, min(requested, os.cpu_count() or 1))


def configure_torch_threads(torch_module) -> int:
    """Apply the same thread limit across all models in this process."""
    thread_count = configured_torch_threads()
    torch_module.set_num_threads(thread_count)
    return thread_count
