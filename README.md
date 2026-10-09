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
- **Methods – like Spark**: samples the server's or the client's thread and opens the call tree when it ends (see
  below).
- **Objects – memory**: counts the objects in the heap by class a few times along the scan and opens the memory
  window when it ends (see below).

**Where** picks the side: the server (ticks) or the client (frames); for Methods and Objects it is either.

Pick the duration on the slider (marks at 10 s, 30 s, 1, 2, 5 and 10 min; a click on a mark picks it) or type it in
the box next to it: `45`, `90s`, `2m`, `1m 30s`, `1:30` (5 s to 1 h; the server may allow less). A progress bar shows
how far along it is (also on the HUD when the window is closed). When it ends, **Open report** becomes available.

**Logs** lists the saved reports, newest first, cut to one kind of scan if wanted: click one to open it, the bin to
delete it (click again to confirm), or delete all shown at once; **Open folder** shows the files.

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

## Methods (call tree)

A Methods scan samples the scanned thread (the server thread or the client thread) every few milliseconds, as Spark
does; lag scans do too unless `sampleMethods` is off. The window opens when a Methods scan ends, and from **Methods** in
a lag report's header:

- **Call tree**: from the thread's start down to the methods doing the work, each line with its share of the sampled
  time, its time per tick (or frame) and its own time. A soft bar behind each line shows its share. **Open the
  hottest path** goes straight to where the time goes.
- **Hot methods**: methods by the time of their own code, wherever they were called from; click a picked one again
  to see it in the tree.
- **Mods** on the left, by the time each is to blame for. Pick one to see only its part of the tree. A sample is put
  down to the mod whose code is nearest the top of the stack, so what a mod calls in the game or in Java is that
  mod's time.
- Sort **by load**, **by own time** or **by mod**, and search by class, method or mod.

With a mod picked in the report, **Methods** opens filtered to that mod.

## Memory

An Objects scan watches the Java heap and counts its objects; lag scans watch only how much is used and allocated
(unless `watchMemory` is off), without stopping the game to count. The window opens when an Objects scan ends, and from
**Memory** in a lag report's header:

- **The graph**: the heap used over the whole scan (with how far it swung in each moment), how many megabytes a second
  the game allocated (all threads, and the scanned one in the tooltip), and marks where garbage collections happened.
- **Objects by class** (Objects scans): a few times along the scan (`heapSnapshots`, 4 by default: at the start, the end and between)
  all the objects in the heap are counted by class, as `jmap -histo` does. For each class: how many objects and how
  many bytes at the last count, how much each grew or shrank since the first, and a small trend line through every
  count. Sort **by size**, **by growth** (finds leaks), **by objects** or **by new objects**, and search by class,
  package or mod. A picked class (or mod) is drawn through the counts on the graph.
- **Mods** on the left, with the bytes of all their classes; pick one to see only its classes. An array goes to the
  mod of what it holds.

Each count stops the game for a moment (longer on a big heap). By default (`heapLiveOnly`) a full garbage collection
is made before it, so only objects still in use are counted; the time each count took is shown on the graph.

Reports are saved in `.minecraft/lagfinder/reports` (the last 50) and can be reopened or deleted from **Logs**.

## Config

`config/lagfinder.cfg`: who may scan (`requireOp`), teleporting, the longest scan, spike thresholds, how long
highlights stay, and the method sampling (`sampleMethods`, `sampleIntervalMs`, 5 ms by default), and the memory (`watchMemory`,
`heapSnapshots`, `heapLiveOnly`).
