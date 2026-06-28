# Vega Voice Notification Generator for RadiaCode Android Data Collection

This document explains how to use the Vega TTS system to generate notification sounds with the Vega voice for the RadiaCode Android application.

## Overview

This solution implements a complete workflow to:
1. Deploy the full Vega TTS model with its dependencies  
2. Use the actual model weights (conds.pt, conditioning.pt, speaker files) to generate proper speech audio
3. Synthesize "Coding task complete" in the Vega voice
4. Create a WAV file suitable for Android notifications

## System Requirements

- Python 3.8+
- CUDA-enabled GPU (RTX 3090, 4090, or 5090) OR CPU support
- Internet connection (to download model weights from Hugging Face)

## Files

The following key files are part of this solution:

1. `generate_vega_notification.py` - Main script to generate the notification sound
2. `middleware/vega-tts/models/vega_tuned/` - Model weight directory with:
   - conds.pt
   - conditioning.pt
   - config.json
   - individual/ (speaker files)

## How It Works

The process works in two stages:
1. **Model Loading**: Imports the chatterbox-tts library and loads the ChatterboxTurboTTS model from Hugging Face
2. **Audio Generation**: Uses the existing model weights to generate natural-sounding speech with the Vega voice

## Usage Instructions

### Running the Generator

```bash
python generate_vega_notification.py
```

This will:
- Download required model files (if not present)
- Load the conditioning file from `middleware/vega-tts/models/vega_tuned/`
- Generate "Coding task complete" using the Vega voice
- Save output as `coding_task_complete.wav`

### Using the Generated Sound

1. Copy `coding_task_complete.wav` to your Android device
2. Set it as a notification sound in your device settings
3. The sound will play when coding tasks are completed in the RadiaCode application

## Implementation Details

The script:
- Uses existing repository model files from middleware/vega-tts/models/vega_tuned/
- Leverages the chatterbox-tts library for high-quality speech generation  
- Properly handles the conditioning file needed for speaker cloning
- Generates a WAV file with 24kHz sample rate suitable for Android notifications
- Includes fallback mechanism if direct model access fails

## Dependencies Used

The solution requires these Python packages:
- `torch>=2.0.0`
- `numpy>=1.24,<1.26`  
- `torchaudio>=2.0.0`
- `chatterbox-tts>=0.1.2`
- `librosa>=0.10.0`
- `soundfile>=0.12.0`
- `fastapi>=0.104.0`  
- `uvicorn[standard]>=0.24.0`
- `pydantic>=2.0.0`

## Troubleshooting

### Common Issues and Solutions:

1. **Import Errors**: Ensure all required packages are installed with:
   ```bash
   pip install torch numpy torchaudio librosa soundfile chatterbox-tts fastapi uvicorn
   ```

2. **Network Issues**: The first run will download model files from Hugging Face (this is normal).

3. **CUDA Availability**: If you encounter CUDA issues, the script automatically falls back to CPU execution.

4. **File Not Found Errors**: Verify the `middleware/vega-tts/models/vega_tuned` directory structure matches what's expected.

## Output

The generator creates a single output file:
- `coding_task_complete.wav`: 24kHz mono audio file suitable for Android notifications (~71KB)

This provides a complete solution that correctly loads and uses the Vega TTS model with all existing repository components to produce proper speech audio with the specified voice.