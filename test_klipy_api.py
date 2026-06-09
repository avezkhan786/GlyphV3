import urllib.request
import json

key = "yILOtI5eslMbOr3W6LVKazfkyUOvpCvz8k03fpzJCxAFOk88ZsGUZWMZQ9QcqTaT"
base_url = f"https://api.klipy.com/api/v1/{key}"
endpoints = ["memes/trending", "meme/trending", "clips/trending", "emojis/trending"]

for ep in endpoints:
    url = f"{base_url}/{ep}?page=1&per_page=1"
    print(f"Testing {url}")
    try:
        with urllib.request.urlopen(url) as response:
            status = response.getcode()
            body = response.read().decode('utf-8')
            print(f"Status: {status}")
            print(f"Length: {len(body)}")
            if len(body) > 0:
                print(f"Body: {body[:200]}...")
    except Exception as e:
        print(f"Error: {e}")
    print("-" * 20)
