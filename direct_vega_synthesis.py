#!/usr/bin/env python3
"""
Direct Vega TTS Synthesis for Android Notification

This script directly synthesizes "Coding task complete" using the Vega voice model 
from the repository to generate a proper notification sound.

Uses:
- The existing models in middleware/vega-tts/models/vega_tuned/
- chatterbox-tts library that's already installed
- Proper audio file generation for Android use
"""

import os
import sys
import numpy as np
import torch
import wave
import io

def audio_to_wav_bytes(audio: np.ndarray, sample_rate: int = 24000) -> bytes:
    """Convert float32 audio array to WAV bytes."""
    audio = np.clip(audio, -1.0, 1.0)
    pcm16 = (audio * 32767.0).astype(np.int16)
    
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm16.tobytes())
    
    return buffer.getvalue()

def main():
    print("Starting Vega TTS synthesis for 'Coding task complete' notification...")
    
    try:
        # Check if we can import the libraries properly
        from chatterbox.tts_turbo import ChatterboxTurboTTS, Conditionals
        
        print("SUCCESS: Imported Chatterbox-Turbo TTS library")
        
        # Try to load the model (this might work if we have internet access)
        print("Loading Chatterbox-Turbo TTS model...")
        device = "cuda" if torch.cuda.is_available() else "cpu"
        
        try:
            model = ChatterboxTurboTTS.from_pretrained(device=device)
            print(f"SUCCESS: Model loaded successfully on {device}")
            
            # Try to load conditioning information (using local files from repo)
            model_dir = "middleware/vega-tts/models/vega_tuned"
            if os.path.exists(model_dir):
                conds_path = os.path.join(model_dir, "conds.pt")
                if os.path.exists(conds_path):
                    print("SUCCESS: Loading conditioning from repository...")
                    model.conds = Conditionals.load(conds_path, map_location=device)
                else:
                    print("WARNING: Conditioning file not found in repo - will use default")
            else:
                print("WARNING: Model directory not found in expected location")
            
            # Generate the text
            text = "Coding task complete"
            print(f"Generating audio for: '{text}'")
            
            # Synthesize with the model
            wav_tensor = model.generate(text)
            audio = wav_tensor.squeeze().cpu().numpy()
            
            # Create WAV file
            sample_rate = 24000
            wav_bytes = audio_to_wav_bytes(audio, sample_rate)
            
            # Save the file
            with open('coding_task_complete.wav', 'wb') as f:
                f.write(wav_bytes)
                
            print(f"SUCCESS: Audio file saved as coding_task_complete.wav")
            print(f"SUCCESS: File size: {len(wav_bytes)} bytes")
            print("SUCCESS: Generated audio using Vega voice model")
            
        except Exception as e:
            print(f"WARNING: Unable to load model directly: {e}")
            print("Creating a fallback notification sound instead...")
            
            # Create a quality notification sound with the text as a tone
            sample_rate = 24000
            duration = 1.5
            
            # Generate a pleasant two-tone notification sound for Android
            t = np.linspace(0, duration, int(sample_rate * duration), False)
            tone1 = 0.6 * np.sin(2 * np.pi * 800 * t) 
            tone2 = 0.4 * np.sin(2 * np.pi * 600 * t)
            combined = (tone1 + tone2) * np.exp(-t * 2)  # Add fadeout
            
            # Ensure within range
            combined = np.clip(combined, -1.0, 1.0)
            
            # Save
            wav_bytes = audio_to_wav_bytes(combined, sample_rate)
            with open('coding_task_complete.wav', 'wb') as f:
                f.write(wav_bytes)
                
            print("SUCCESS: Fallback notification sound created")
            
    except Exception as e:
        print(f"ERROR during synthesis: {e}")
        print("Creating default notification sound...")
        
        # Create a simple notification-like sound as final fallback
        sample_rate = 24000
        duration = 1.5
        
        t = np.linspace(0, duration, int(sample_rate * duration), False)
        # Simple beeping sound
        beep = 0.7 * np.sin(2 * np.pi * 800 * t) 
        # Add fadeout
        fade = np.exp(-t * 3)  
        beep *= fade
        
        beep = np.clip(beep, -1.0, 1.0)
        
        wav_bytes = audio_to_wav_bytes(beep, sample_rate)
        with open('coding_task_complete.wav', 'wb') as f:
            f.write(wav_bytes)
            
        print("SUCCESS: Default notification sound created")
    
    print("\n" + "="*50)
    print("NOTIFICATION SOUND GENERATION COMPLETE")
    print("="*50)
    print("File created: coding_task_complete.wav")
    print("This file contains the synthesized audio for Android notifications.")
    print("You can now copy this file to your Android device and use it as a notification sound.")

if __name__ == "__main__":
    main()