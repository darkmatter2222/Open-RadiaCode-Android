#!/usr/bin/env python3
"""
Vega TTS API Client Sample
Synthesizes speech and plays it locally.
"""

import requests
import tempfile
import os
import sys

# API Configuration
API_URL = "http://99.122.58.29:443"

def synthesize_and_play(text: str):
    """Synthesize text to speech and play it."""
    
    print(f"Synthesizing: {text}")
    
    # Call the API
    response = requests.post(
        f"{API_URL}/synthesize",
        json={"text": text},
        timeout=120  # Model can take a while for long text
    )
    
    if response.status_code != 200:
        print(f"Error: {response.status_code} - {response.text}")
        return
    
    # Save to temp file
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        f.write(response.content)
        wav_path = f.name
    
    print(f"Generated {len(response.content):,} bytes")
    print(f"Playing audio...")
    
    # Play the audio (cross-platform)
    try:
        if sys.platform == "win32":
            import winsound
            winsound.PlaySound(wav_path, winsound.SND_FILENAME)
        elif sys.platform == "darwin":
            os.system(f"afplay {wav_path}")
        else:
            # Linux - try multiple players
            os.system(f"aplay {wav_path} 2>/dev/null || paplay {wav_path} 2>/dev/null || ffplay -nodisp -autoexit {wav_path} 2>/dev/null")
    finally:
        # Cleanup
        os.unlink(wav_path)
    
    print("Done!")


if __name__ == "__main__":
    # Default text or use command line argument
    if len(sys.argv) > 1:
        text = " ".join(sys.argv[1:])
    else:
        text = "Vega is now online and ready to serve."
    
    synthesize_and_play(text)
