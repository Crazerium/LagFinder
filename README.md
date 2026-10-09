# LagFinder

A lag profiler for Minecraft 1.7.10 (GTNH), in the spirit of LagGoggles and Spark, with the look of
[Wayfarer's Map](https://github.com/evgengoldwar/Wayfarer-s-Map).

Press **L** to open it.

## Scanning

- **Server – TPS**: every tile entity, entity, scheduled/random block tick, event handler and world on the server,
  measured tick by tick. Needs LagFinder on the server and operator rights (the owner of a single player world
  always may).
- **Client – FPS**: block renderers (TESR), entity renderers, chunk section rebuilds, client event handlers and the
  client world's own ticks, measured frame by frame. Works on any server. The window closes while it runs.

Pick a duration (10 s – 10 min) and start. A progress bar shows how far along it is (also on the HUD when the
window is closed). When it ends, **Open report** becomes available.

## The report

- Health tiles: average and lowest TPS/FPS, average and worst tick/frame.
- Tabs per category (Tile Entities, Entities, Block Ticks, Events, Worlds / Block Renderers, Entity Renderers,
  Chunk Updates...).
- A tree per tab: **mod → kind (e.g. Electric Blast Furnace) → each one in the world**, with the average time per
  tick and the worst single tick. Sort by average, by peak or by how often it spiked, and search by mod, name,
  class or coordinates.
- The graph: TPS/FPS over the whole scan, with the load of what is picked over it — its average per tick in each
  moment and, lighter, its worst tick then (the amplitude), so things that lag hard but rarely stand out.
- Details of the pick: share of the tick, peak and when it happened, spikes, calls per tick, and for things in the
  world **Teleport** (any dimension), **Highlight** (outlined through walls with a beam to the sky, shown on the
  HUD with distance and direction) and **Copy** coordinates.
- With nothing picked: the things that took the most time and the worst spikes of the whole scan.

Reports are saved in `.minecraft/lagfinder/reports` (the last 20) and can be reopened from the main window.

## Config

`config/lagfinder.cfg`: who may scan (`requireOp`), teleporting, the longest scan, spike thresholds, how long
highlights stay.
