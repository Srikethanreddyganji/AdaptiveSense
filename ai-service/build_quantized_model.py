import os
import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification

MODEL_NAME = "j-hartmann/emotion-english-distilroberta-base"
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "model_quantized")
MODEL_FILE = os.path.join(OUTPUT_DIR, "model_quantized.pt")


def build_and_quantize_model():
    print(f"Starting ahead-of-time quantization for {MODEL_NAME}...")
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print("Step 1: Saving tokenizer locally...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    tokenizer.save_pretrained(OUTPUT_DIR)

    print("Step 2: Loading base sequence classification model with low CPU memory...")
    model = AutoModelForSequenceClassification.from_pretrained(
        MODEL_NAME,
        low_cpu_mem_usage=True
    )
    model.eval()

    print("Step 3: Dynamically quantizing Linear layers to INT8...")
    quantized_model = torch.quantization.quantize_dynamic(
        model,
        {torch.nn.Linear},
        dtype=torch.qint8
    )
    quantized_model.eval()

    print("Step 4: Tracing quantized model via TorchScript...")
    dummy = tokenizer("warmup trace sentence", return_tensors="pt")
    with torch.no_grad():
        traced_model = torch.jit.trace(
            quantized_model,
            (dummy["input_ids"], dummy["attention_mask"]),
            strict=False
        )

    print(f"Step 5: Saving traced quantized model to {MODEL_FILE}...")
    traced_model.save(MODEL_FILE)

    file_size_mb = os.path.getsize(MODEL_FILE) / (1024 * 1024)
    print(f"Quantization complete! Model size: {file_size_mb:.2f} MB")

    # Step 6: Verify inference
    print("Step 6: Verifying inference with saved model...")
    loaded_model = torch.jit.load(MODEL_FILE)
    loaded_model.eval()
    test_inputs = tokenizer("I am feeling great!", return_tensors="pt")
    with torch.no_grad():
        out = loaded_model(test_inputs["input_ids"], test_inputs["attention_mask"])
        logits = out["logits"] if isinstance(out, dict) else out[0]
        probs = torch.softmax(logits, dim=-1)
        print(f"Verification successful! Output shape: {probs.shape}")


if __name__ == "__main__":
    build_and_quantize_model()
