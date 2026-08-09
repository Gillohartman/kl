# MegaHoppers

A Paper **26.2** plugin with two features:

1. **16x hoppers** — hoppers move 16 items per transfer cycle instead of 1, so they behave exactly
   like normal hoppers but 16 times faster. Furnace handling is correct: a hopper below a furnace
   only pulls the **finished product**, a hopper on **top** feeds the **input** slot (never the
   output), and a hopper on the **side** feeds the **fuel** slot. Every transfer is dupe-safe.
2. **Redstone-hopper chunkloader** — place two hoppers **facing into each other** and drop
   **redstone dust** into either one. That pair force-loads exactly **one chunk**. Take the redstone
   out (or break a hopper) and the chunk is released.

Built against `io.papermc.paper:paper-api:26.2` (Java 25 / Adventure 5).

---

## Get the .jar without installing anything (GitHub auto-build)

1. On GitHub, click **New repository** → give it a name → **Create repository**.
2. On the empty repo page, click **uploading an existing file**, then drag in **everything inside
   this `MegaHoppers` folder** (keep the folder structure — `pom.xml`, `src/`, `.github/`, etc.).
   Commit the files.
3. Open the **Actions** tab. A workflow called **Build MegaHoppers** runs automatically.
4. When it finishes (green check), open the run and download the **`MegaHoppers-jar`** artifact from
   the **Artifacts** section. Inside is `MegaHoppers-1.0.0.jar`.
5. Drop that jar into your server's `plugins/` folder and restart.

> Tip: you can also click **Actions → Build MegaHoppers → Run workflow** to rebuild any time.

## Build it locally instead

Requires JDK 25 and Maven:

```bash
mvn -B package
# -> target/MegaHoppers-1.0.0.jar
```

---

## How the chunkloader is built in-game

```
[Hopper A] > < [Hopper B]      (two hoppers, horizontally adjacent, pointing at each other)
```

- Place two hoppers next to each other so each one **points into** the other (aim at the side of the
  other hopper while placing, or use the standard "shift place onto the side" trick).
- Open one of them and put in at least one **redstone dust**.
- You'll get a chat message: *"Chunkloader on — chunk [x, z] stays loaded."*
- Keep both hoppers within the same chunk so the whole contraption sits in the loaded chunk.
- Remove the redstone, break a hopper, or rotate one away and the chunk is released.

Check active loaders any time with `/mh list`, or open the in-game map below.

## Chunkloader map

Hold **redstone dust** in your hand and **right-click the air** (or sneak + right-click a block) to
open a map view listing every active chunkloader, nearest first. Each entry shows its world, chunk
coordinates, and distance. With the `megahoppers.map.teleport` permission, click an entry to
teleport straight to that chunkloader.

---

## Commands & permission

| Command | Description |
| --- | --- |
| `/mh` or `/megahoppers` | Show status (multiplier, active chunkloaders). |
| `/mh reload` | Reload `config.yml`. |
| `/mh list` | List active chunkloaders. |

Permission: `megahoppers.admin` (default: OP).

## Configuration (`config.yml`)

```yaml
hoppers:
  enabled: true
  multiplier: 16              # items per transfer cycle (vanilla = 1)
  transfer-cooldown-ticks: 8  # ticks between cycles (vanilla = 8)
  enforce-furnace-slots: true # correct furnace input/fuel/output behaviour
  disabled-worlds: []

chunkloader:
  enabled: true
  require-redstone: true      # false = any facing hopper pair loads its chunk
  validate-interval-ticks: 40
```

## Notes

- The booster keeps the vanilla ~8-tick rhythm: `multiplier: 16` = 16 items every 8 ticks = 16x
  throughput. Push and pull are timed independently, so a hopper can still fill and empty each cycle.
- `paper-api` version `[26.2.build,)` resolves to the latest 26.2 build at compile time. To pin a
  specific build for reproducible jars, replace it with e.g. `26.2.build.100`.
