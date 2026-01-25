"""
Vega LLM Client Sample

Demonstrates how to use the Vega LLM API for chat completion.
"""

import requests
import sys
from typing import Optional, List

# Default API endpoint
API_URL = "http://192.168.86.48:8001"


def chat(
    message: str,
    system_prompt: Optional[str] = None,
    history: Optional[List[dict]] = None,
    max_tokens: int = 512,
    temperature: float = 0.7
) -> dict:
    """
    Send a chat message to Vega LLM.
    
    Args:
        message: The user message
        system_prompt: Optional custom system prompt (uses default Vega personality if not provided)
        history: Optional conversation history as list of {"role": str, "content": str}
        max_tokens: Maximum tokens to generate
        temperature: Sampling temperature (higher = more creative)
    
    Returns:
        API response dict with 'response', 'model', 'tokens_generated', 'finish_reason'
    """
    # Build messages array
    messages = history.copy() if history else []
    messages.append({"role": "user", "content": message})
    
    payload = {
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature
    }
    
    if system_prompt is not None:
        payload["system_prompt"] = system_prompt
    
    response = requests.post(f"{API_URL}/chat", json=payload)
    response.raise_for_status()
    
    result = response.json()
    # Add the assistant response to history for convenience
    result["full_history"] = messages + [{"role": "assistant", "content": result["response"]}]
    return result


def generate(prompt: str, max_tokens: int = 256, temperature: float = 0.7) -> str:
    """
    Generate text completion (no chat format).
    
    Args:
        prompt: The text prompt to complete
        max_tokens: Maximum tokens to generate
        temperature: Sampling temperature
    
    Returns:
        Generated text string
    """
    response = requests.post(
        f"{API_URL}/generate",
        json={
            "prompt": prompt,
            "max_tokens": max_tokens,
            "temperature": temperature
        }
    )
    response.raise_for_status()
    return response.json()["text"]


def health_check() -> dict:
    """Check API health and model status."""
    response = requests.get(f"{API_URL}/health")
    response.raise_for_status()
    return response.json()


def get_info() -> dict:
    """Get model and server information."""
    response = requests.get(f"{API_URL}/info")
    response.raise_for_status()
    return response.json()


def interactive_chat():
    """Run an interactive chat session with Vega."""
    print("=" * 60)
    print("  Vega LLM Interactive Chat")
    print("  Type 'quit' or 'exit' to end session")
    print("  Type 'clear' to reset conversation history")
    print("=" * 60)
    print()
    
    # Check health first
    try:
        health = health_check()
        if not health.get("model_loaded"):
            print("Warning: Model is still loading, responses may be slow...")
        info = get_info()
        print(f"Connected to: {info.get('model_id', 'Unknown')}")
        print()
    except Exception as e:
        print(f"Error connecting to API: {e}")
        print(f"Make sure the API is running at {API_URL}")
        return
    
    history = []
    
    while True:
        try:
            user_input = input("\nYou: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n\nGoodbye!")
            break
        
        if not user_input:
            continue
        
        if user_input.lower() in ("quit", "exit"):
            print("\nGoodbye!")
            break
        
        if user_input.lower() == "clear":
            history = []
            print("\nConversation history cleared.")
            continue
        
        try:
            result = chat(user_input, history=history)
            response_text = result["response"]
            history = result["full_history"]
            
            print(f"\nVega: {response_text}")
        except requests.exceptions.RequestException as e:
            print(f"\nError: {e}")
        except Exception as e:
            print(f"\nUnexpected error: {e}")


def demo():
    """Run a quick demo of the API."""
    print("=" * 60)
    print("  Vega LLM Demo")
    print("=" * 60)
    print()
    
    # Check health
    print("Checking API health...")
    try:
        health = health_check()
        print(f"  Status: {health['status']}")
        print(f"  Model loaded: {health['model_loaded']}")
    except Exception as e:
        print(f"  Error: {e}")
        return
    
    print()
    
    # Get info
    print("Getting model info...")
    try:
        info = get_info()
        print(f"  Model: {info['model_id']}")
        print(f"  Device: {info['device']}")
    except Exception as e:
        print(f"  Error: {e}")
    
    print()
    
    # Chat demo
    print("Demo chat:")
    print("-" * 40)
    
    message = "Hello! Can you introduce yourself briefly?"
    print(f"User: {message}")
    
    try:
        result = chat(message)
        print(f"\nVega: {result['response']}")
    except Exception as e:
        print(f"Error: {e}")
    
    print()
    print("=" * 60)


if __name__ == "__main__":
    if len(sys.argv) > 1:
        if sys.argv[1] == "demo":
            demo()
        elif sys.argv[1] == "chat":
            interactive_chat()
        elif sys.argv[1] == "health":
            health = health_check()
            print(f"Health: {health}")
        elif sys.argv[1] == "info":
            info = get_info()
            for key, value in info.items():
                print(f"{key}: {value}")
        else:
            # Treat argument as a message
            message = " ".join(sys.argv[1:])
            result = chat(message)
            print(result["response"])
    else:
        interactive_chat()
