"""Склейка снимка игры с макетом: слева игра, справа макет, по 1280×800. Запуск: python shots/pair.py <снимок> <макет> <выход>."""
import sys
from PIL import Image
game, mock, out = sys.argv[1:4]
a = Image.open(game).convert('RGB').resize((1280, 800))
b = Image.open(mock).convert('RGB').resize((1280, 800))
w = Image.new('RGB', (2570, 800), 'white')
w.paste(a, (0, 0))
w.paste(b, (1290, 0))
w.save(out, optimize=True)
