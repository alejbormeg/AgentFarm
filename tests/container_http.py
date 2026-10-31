"""Read-only HTTP burst for the Compose container's native-thread budget."""
import concurrent.futures
import urllib.request

URL = "http://localhost:8080/login"

def check(_):
    with urllib.request.urlopen(URL, timeout=20) as response:
        if response.status != 200:
            raise AssertionError(response.status)
        response.read()

if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=96) as pool:
        list(pool.map(check, range(384)))
    check(None)
    print("385 login requests passed, with up to 96 concurrent clients")
