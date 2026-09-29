# Living trails

- A player or persistent animal entering a new supported cell records one step;
  appearing, standing still, jumping over it and long teleports do not draw a line.
- Repeated crossings reveal five increasingly broad soil-wear stages on grass;
  the grass block itself stays unchanged and neighbouring cells stay untouched.
- Adjacent used cells join visually without a square, full-block boundary.
  Existing snow is pressed; snowfall resumes after pressure expires.
- Pressure survives chunk save, unload and reload. At most 4096 cells are kept
  in one chunk, with a one-byte pressure value per cell.
- Ten quiet days can retire a route made by 20 passes. An unloaded chunk catches
  up when it is loaded and ticked again.
- Existing host block metadata updates synchronize the same visible stages to
  a guest already present, a guest joining later, and during daily fade.
- Updating wear alone does not remesh the entire terrain chunk.
- A malformed saved section cannot silently replace a player's terrain; a newer
  section version remains protected and read-only.
