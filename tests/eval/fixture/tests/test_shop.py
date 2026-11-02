import unittest

import shop


class ShopTest(unittest.TestCase):
    def test_discount(self):
        self.assertEqual(shop.discount(100, 10), 90)


if __name__ == "__main__":
    unittest.main()
