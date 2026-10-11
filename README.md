# LagFinder

A lag profiler for Minecraft 1.7.10 (GTNH), in the spirit of LagGoggles and Spark, with the look of
[Wayfarer's Map](https://github.com/evgengoldwar/Wayfarer-s-Map).

Press **L** to open it.

## Scanning

- **Server – TPS**: every tile entity, entity, scheduled/random block tick, event handler, Applied Energistics 2
  network and world on the server, measured tick by tick. Each thing is counted for its own time only: what is
  measured inside it (an event a tile entity posts, the AE2 networks AE2's tick handler ticks) is counted where it
  belongs, not twice. A tile entity reloaded with its chunk stays the same thing in the report. Needs LagFinder on the server and operator rights (the owner of a single player world
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

Pick the kind of scan with a click on its card or with its number (**1**–**5**). Pick the duration on the slider (marks at 10 s, 30 s, 1, 2, 5 and 10 min; a click on a mark picks it) or type it in
the box next to it: `45`, `90s`, `2m`, `1m 30s`, `1:30` (5 s to 1 h; the server may allow less). A progress bar shows
how far along it is (also on the HUD when the window is closed). When it ends, **Open report** becomes available.

**Logs** lists the saved reports, newest first, cut to one kind of scan if wanted: click one to open it, the bin to
delete it (click again to confirm), or delete all shown at once; **Open folder** shows the files.

## The report

- **Tiles** across the top: average TPS/FPS (stable or not, and how it went from the start of the scan to the end),
  the average tick/frame and its part of the budget, the worst one (a click shows what lagged then), the budget (50 ms
  of a tick, a frame at the limit or at 60 FPS), how many were measured over how long, and the **top offender**: what
  takes the most time of all (the kind of thing, or the one of them taking most of its time), with its time per tick.
  When the rate dropped and it takes a good part of the tick it is named the main cause of the lag and glows red. A
  click shows it in its tab.
- **Tabs** per category (Tile Entities, Entities, Block Ticks, AE2 Networks, Events, Server, Worlds / Block Renderers,
  Entity Renderers, Chunk Updates...), each with its time per tick.
- **Filters** under them: search by mod, name, class or coordinates (a search for coordinates finds the kind, and its
  cards open searched for them), one mod only, one category only, the sorting (by average, per call, peak, spikes,
  count or name), and **Only slow** (what takes more than 0.1 ms a tick; the limit is in the settings).
- **The table**: a section per category, the picked tab's open, the others a line with how many things they hold and
  their time per tick (a click opens one). Each kind of thing (e.g. Hopper) with its icon, mod and class, how many
  there are (a button opening them all as cards), its time per tick with a bar against the heaviest of the section, its
  time per call, its worst tick and its share of the tick. A click on a column's title sorts by it, again the other
  way round. Under it, how many are shown and the average tick.
- **AE2 Networks** (with Applied Energistics 2 on the server): each ME network is a line of its own, named by where its
  controller is (or that it has none) and how many nodes it has. Under it, the machines it ticked by kind (interfaces,
  import/export buses, molecular assemblers, add-ons' parts...) with their places, to open as cards, teleport to and
  highlight, and the network's own work: channels, crafting, storage, energy...
- **The graph**, in tabs: the rate with the tick's average time in bars (red where a tick went over 25 ms), the tick's
  average, its worst, and the load of what is picked — its average per tick in each moment and, lighter, its worst
  tick then, so things that lag hard but rarely stand out. Show the whole scan or only its last minutes or seconds;
  a button gives it all the room on the right. Faint bands behind it show how good the rate is, a dashed line the
  time a tick may take, and the moment under the mouse is marked on the lines with its values beside the axes, and
  told in a box with what took the most time then. The memory and allocations graphs are drawn the same way.
- With nothing picked, under the graph: **Most time** (the steady load) and **Worst spikes** (rare heavy work, with
  where it was); **Show all spikes** lists every one of them. A click on a line shows it in its tab.
- Details of the pick: share of the tick, peak and when it happened, spikes, calls per tick, and for things in the
  world **Teleport** (any dimension), **Highlight** (outlined through walls with a beam to the sky, shown on the
  HUD with distance and direction) and **Copy** coordinates.
- **All as cards**: a kind of thing opens in a window of its own, one card for each of them in the world: its icon and
  name, coordinates, dimension and distance, average and worst tick with its spikes, and buttons to **Teleport**,
  **Highlight** and **Copy**. The grid scrolls through tens of thousands of them smoothly. Sort by average, peak,
  spikes or the nearest first, show one dimension only, and search by name, coordinates or dimension. Opened with the
  count button on a kind's line, a click on a picked line, the grid button of the filters, the button in the details,
  or **Enter**; a thing picked in the overview or the details opens its kind's cards at it. A teleport closes it and
  the key brings it back to go on to the next one. **Backspace** goes back to the report.
- **What lagged at a moment**: a click on the graph picks that moment (a red mark shows the lowest one; a click on it
  picks it too), and the details list what took the most time and the worst ticks then. **Backspace** goes back:
  from a pick to the moment, from the moment to the whole scan.
- **Performance insights** along the bottom: whether the rate held, the worst spike, and where to look first, each
  with a link there.
- **Export** saves the report as a table (CSV) in `.minecraft/lagfinder/exports`: every kind of thing and every one of
  them in the world, with its numbers and place. **Settings** (also the cog of the filters): the mod and class under
  each name, compact rows, the rate's bands on the graph, and what counts as slow.

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
- A flame marks the **hot spots** in the tree: lines whose own code takes 1% of all the time or more (red from 5%).
  With nothing picked, the details show the **hottest method** with a button down the tree to it.
- A bar under **Mods** splits all the time between the mods by color; a click on a part picks that mod.

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
  mod of what it holds. A bar under their title splits the heap between them.
- **Possible leaks**: classes that grew at every count (by 1 MB and a tenth at least) are marked with a red sign;
  with nothing picked, the details name the one that grew the most, with a button to it, or tell that none did.

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
- **Mods** on the left, by the classes creating the objects; pick one to see only what its classes create. A bar
  under their title splits what was created between them.
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
