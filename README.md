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
- **Methods**: samples the server's or the client's thread, like Spark, and opens the call tree when it ends (see
  below).
- **Objects**: counts the objects in the heap by class a few times along the scan and opens the memory window when
  it ends (see below).
- **Allocations**: watches the objects being created and which classes create them, and opens the allocations
  window when it ends (see below).

**Where** picks the side for Methods, Objects and Allocations: **Server**, **Client** or **Both** at once. With Both
the two sides are added up into one report, opened in the usual window: one call tree of both threads, the objects
of both heaps, what both threads created. In single player both sides run in the same Java, so the heap and all
threads' allocations are counted once there. Server – TPS and Client – FPS always scan one side, so Both is off for
them.

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
does. The window opens when a Methods scan ends:

- **Call tree**: from the thread's start down to the methods doing the work, each line with its share of the sampled
  time, its time per tick (or frame) and its own time. A soft bar behind each line shows its share. **Open the
  hottest path** goes straight to where the time goes.
- **Hot methods**: methods by the time of their own code, wherever they were called from; click a picked one again
  to see it in the tree.
- **Mods** on the left, by the time each is to blame for. Pick one to see only its part of the tree. A sample is put
  down to the mod whose code is nearest the top of the stack, so what a mod calls in the game or in Java is that
  mod's time.
- Sort **by load**, **by own time** or **by mod**, and search by class, method or mod.

## Memory

An Objects scan watches the Java heap and counts its objects. The window opens when an Objects scan ends:

- **The graph**: the heap used over the whole scan (with how far it swung in each moment), how many megabytes a second
  the game allocated (all threads, and the scanned one in the tooltip), and marks where garbage collections happened.
- **Objects by class**: a few times along the scan (`heapSnapshots`, 4 by default: at the start, the end and between)
  all the objects in the heap are counted by class, as `jmap -histo` does. For each class: how many objects and how
  many bytes at the last count, how much each grew or shrank since the first, and a small trend line through every
  count. Sort **by size**, **by growth** (finds leaks), **by objects** or **by new objects**, and search by class,
  package or mod. A picked class (or mod) is drawn through the counts on the graph.
- **Mods** on the left, with the bytes of all their classes; pick one to see only its classes. An array goes to the
  mod of what it holds.

Each count stops the game for a moment (longer on a big heap). By default (`heapLiveOnly`) a full garbage collection
is made before it, so only objects still in use are counted; the time each count took is shown on the graph.

## Allocations

An Allocations scan watches the objects the game creates while it runs, and where they are created: Java's Flight
Recorder takes samples of the allocations (`allocSampleRate` a second at most, 500 by default), each with the type of
the object and the stack it was created on. Each sample stands for the bytes allocated since the one before, so the
bytes add up to about all the game allocated. It costs very little and never stops the game.

- **Object types**: every type created (`int[]`, `String`, `NBTTagCompound`...). Open one to see **the classes that
  create it**, and open a class to see **its methods and lines** (`onUpdate():120`), with what of Java or a library
  it called to do it (`via ArrayList.add`, `via new HashMap`).
- **Who creates**: the same the other way round: every class creating objects, the types it creates under it, then
  its methods and lines. The class is the nearest one on the stack that isn't Java's or a library's, so what
  `new ArrayList` or `list.add` allocate goes to the class that called them. **Show the class** / **Show the type**
  under the list jumps to the other view.
- Each line: its share of all that was allocated, the bytes over the scan, the bytes a second, and a small graph of
  them over time. The graph on top shows how fast the game allocated over the scan, with the type or class picked
  drawn over it.
- **Mods** on the left, by the classes creating the objects; pick one to see only what its classes create.
- Only the scanned thread (server or client), or **all threads** of the game (chunk loading, networking...).
- Sort **by size**, **by name** or **by mod**; search by type, class, method or mod. Arrows, Enter and Tab work.

The Flight Recorder needs Java 16 or later (GTNH on Java 17+). On older Java the scanned thread's stack is sampled
instead, with the bytes it allocated in between: who allocates shows roughly, but not what type of objects.

Reports are saved in `.minecraft/lagfinder/reports` (the last 50) and can be reopened or deleted from **Logs**.

## Config

`config/lagfinder.cfg`: who may scan (`requireOp`), teleporting, the longest scan, spike thresholds, how long
highlights stay, how often Methods scans sample (`sampleIntervalMs`, 5 ms by default), and Objects and Allocations
scans (`heapSnapshots`, `heapLiveOnly`, `allocSampleRate`).

Each kind of scan gathers only what it is for: a lag scan times each thing, a Methods scan samples the thread, an
Objects scan watches the heap, an Allocations scan watches what is created. Every scan times its ticks or frames,
for its graph.
