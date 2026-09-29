# Living trails

- A player or persistent animal entering a new supported cell records one step;
  appearing, standing still, jumping over it and long teleports do not draw a line.
- Repeated crossings turn grass into dirt; neighbouring cells stay unchanged.
- Active pressure blocks grass regrowth and new snowfall. Steps press existing
  snow; snowfall resumes after pressure expires.
- Pressure survives chunk save, unload and reload. At most 4096 cells are kept
  in one chunk, with a one-byte pressure value per cell.
- Ten quiet days can retire a route made by 20 passes. An unloaded chunk catches
  up when it is loaded and ticked again.
- A malformed saved section cannot silently replace a player's terrain; a newer
  section version remains protected and read-only.
