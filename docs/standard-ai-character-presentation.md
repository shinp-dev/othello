# Standard AI character presentation

Standard Mode now presents each AI level as a character opponent.

- Lv1: chick
- Lv2: rabbit
- Lv3: koala
- Lv4: elephant
- Lv5: wild chick
- Lv6: wild rabbit
- Lv7: wild koala
- Lv8: wild elephant

The first challenge against each level shows the opponent introduction once per signed-in user on the device. Every completed match shows the opponent result artwork: the crying `*_lose.webp` asset when the human wins, and the confident `*_win.webp` asset when the opponent wins or the game is drawn. A first clear without Undo also shows the next-level unlock or campaign completion message.

The presentation state is intentionally separate from Standard AI strength/progression state so replaying or resetting a match does not alter campaign progression.
