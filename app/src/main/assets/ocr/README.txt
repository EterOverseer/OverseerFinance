OverseerFinance Offline PaddleOCR PP-OCRv5 Assets
===================================================

Place the official PaddleOCR PP-OCRv5 ONNX model files in this directory
to enable fully offline on-device OCR inference via ONNX Runtime:

1. PP-OCRv5_mobile_det.onnx  (Text detection DBNet model)
2. th_PP-OCRv5_mobile_rec.onnx (Thai text recognition PP-OCRv5 model)
3. th_dict.txt (Included in this repository)

Reference: https://github.com/PaddlePaddle/PaddleOCR

If the .onnx model binaries are absent, OverseerFinance honestly reports
MODEL_NOT_BUNDLED and preserves all imported slips in NEEDS_REVIEW for
side-by-side manual verification without fabricating OCR results.
