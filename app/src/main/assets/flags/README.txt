Team flags go in this folder, one image per team, named after the team id in
app/src/main/java/com/example/beirutrun/Teams.kt:

  el_lahib.png          -> عشيرة اللهيب
  corniche_sharks.png   -> Corniche Sharks
  golden_lions.png      -> Golden Lions
  evergreen_squad.png   -> Evergreen Squad
  raouche_eagles.png    -> Raouche Eagles
  phoenix_legion.png    -> Phoenix Legion
  summit_rangers.png    -> Summit Rangers

All but el_lahib.png are drawn by tools/FactionFlags.java (run it from the project root with
Java 17+ to redraw them).

Teams must be fictional: don't use the names, flags or logos of real political parties, militias
or other groups. Google Play rejects apps that do.

.png, .jpg, .jpeg and .webp all work. A landscape image around 300 x 200 pixels is plenty.
Until a flag is added, the game shows a plain flag in the team colour with its initials.
