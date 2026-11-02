"""Runs a whole order through the module, as a caller would."""
import shop

coupon = shop.parse_coupon("WELCOME:10")
total = shop.order_total([{"price": 50, "quantity": 2}])
assert shop.discount(total, coupon["percent"]) == 90
