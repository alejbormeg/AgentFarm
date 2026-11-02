"""Small sample domain for comparing agent results."""


def discount(price, percent):
    return price - price * percent / 100


def order_total(lines):
    total = 0
    for line in lines:
        total += line["price"] * line["quantity"]
    return total


def parse_coupon(text):
    code, value = text.split(":")
    return {"code": code, "percent": int(value)}
