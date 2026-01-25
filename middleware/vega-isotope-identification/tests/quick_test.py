#!/usr/bin/env python3
"""Quick test for isotope API endpoint."""
import json
import random
import urllib.request

# Generate random spectrum
data = {"spectrum": [random.random() * 100 for _ in range(1023)]}

# Make request
req = urllib.request.Request(
    "http://localhost:8020/identify",
    data=json.dumps(data).encode(),
    headers={"Content-Type": "application/json"}
)

response = urllib.request.urlopen(req)
result = json.loads(response.read().decode())

print("API Response:")
print(json.dumps(result, indent=2))

# Check through ingress as well
print("\n\nTesting via Ingress (port 8080)...")
req2 = urllib.request.Request(
    "http://localhost:8080/identify",
    data=json.dumps(data).encode(),
    headers={"Content-Type": "application/json"}
)
response2 = urllib.request.urlopen(req2)
result2 = json.loads(response2.read().decode())
print("Ingress Response:")
print(json.dumps(result2, indent=2))
