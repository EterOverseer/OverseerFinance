OverseerFinance Built-in Offline PaddleOCR PP-OCRv5 Assets (Pinned)
==================================================================

All OCR models are bundled directly inside the APK so no runtime downloads are needed.

Pinned Model Artifacts:
1. PP-OCRv5_mobile_det.onnx
   - Source: HuggingFace PaddlePaddle/PP-OCRv5_mobile_det_onnx
   - Revision: e6f4fa85f00e168c862bc462aebca69eef9b3d3d
   - Size: 4,826,518 bytes
   - SHA-256: a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d

2. th_PP-OCRv5_mobile_rec.onnx
   - Source: HuggingFace PaddlePaddle/th_PP-OCRv5_mobile_rec_onnx
   - Revision: 1d4adbbafb1034a2fd6618498575b81ea7b69f69
   - Size: 7,891,015 bytes
   - SHA-256: 27618be66018f8598ac0a526a593f9f1cebf794e7eded93428e8fb016e537f5f

3. th_dict.txt
   - Extracted from PaddlePaddle/th_PP-OCRv5_mobile_rec_onnx (inference.yml CTCLabelDecode.character_dict)
   - Entries: 524 characters (+ trailing space char at decode time = 526 CTC classes with blank index 0)
   - SHA-256: 57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a
