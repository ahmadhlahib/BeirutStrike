Team flags go in this folder, one image per team, named after the team id in
app/src/main/java/com/example/beirutrun/Teams.kt:

  lebanese_forces.png     -> Lebanese Forces
  hezbollah.png           -> Hezbollah
  tayyar_watani_hor.png   -> Tayyar Watani Hor
  tayyar_mostakbal.png    -> Tayar Mostakbal
  hezb_el_ishtiraki.png   -> Hezb el Ishtiraki

.png, .jpg, .jpeg and .webp all work. A landscape image around 300 x 200 pixels is plenty.
Until a flag is added, the game shows a plain flag in the team colour with its initials.

To add a team: add a line to Teams.all in Teams.kt, e.g.
  Team("amal", "Amal Movement", 0xFF2E7D32.toInt()),
then put its flag here as amal.png.
