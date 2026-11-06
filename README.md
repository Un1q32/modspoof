# ATM10 Spoof

A Fabric client mod for Minecraft 26.3 that lets you join modded servers without installing the
server's mods. It supports NeoForge 1.21.1 packs such as **All the Mods 10** and
**All the Mods 10: To the Sky**, NeoForge 26.1.2 packs such as **All the Mods 11**, and Forge
1.7.10 packs such as **GregTech: New Horizons**. It can learn unknown packs too.

You can walk around, but modded content will be missing or broken. Modded blocks show as stone or
air, modded items/entities usually don't work, and custom dimensions may not load. The point is to
get into the world at all.

## Install

1. Install Fabric Loader 0.20.1 or newer for Minecraft 26.3.
2. Put these in `mods`:
   - `atm10-spoof-0.1.0.jar`
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [ViaFabricPlus](https://modrinth.com/mod/viafabricplus)
3. Join the server. The mod picks the ViaFabricPlus target version from the server's advertised
   version (1.21.1 for ATM10, 26.1.2 for ATM11, 1.7.10 for GTNH). You can override it with
   `-Datm10spoof.vfpVersion=...` (see [Options](#options)).

Build from source with JDK 25+:

```sh
./gradlew build
# jar: build/libs/atm10-spoof-0.1.0.jar
```

## How it works

- Fakes NeoForge's configuration handshake (`neoforge:register`, `neoforge:network`,
  registry sync, data maps, extensible enums and feature flags) for both 21.1 (1.21.1) and
  26.1 (26.1.2).
- Fakes Forge 1.7.10's legacy FML handshake (`FML|HS` / `fml:hs`) for GregTech: New Horizons.
  When the server replies with `Mod rejections [...]`, the mod remembers the exact server mod
  ids and versions and echoes them back on the next connection.
- Ships channel manifests collected from real ATM10/ATM10 Sky and ATM11 servers. Servers hide
  optional channels, so a client without the mods has no other way to know their names.
- Learns any remaining channel versions/flows from the server's `modded_network_setup_failed` reply
  and saves them per server in `config/atm10spoof/<server address>.json`. The FML mod list is saved
  in `config/atm10spoof/fml-<server address>.json`.
- Drops packets that ViaFabricPlus or the client can't decode or handle instead of disconnecting.
- Uses the client's own registries if the server's modded registry data can't be loaded.
- If a mod's configuration task gets stuck, finishes the configuration phase itself.
- Works around ViaLegacy losing the third byte of FML's extended payload length, which otherwise
  makes large handshake packets (the server's world id map) unreadable.

## First join on an unknown pack

The first connection may be rejected while the mod learns that pack's channels. The log line to
watch for is:

```
[ATM10 Spoof] Server rejected the fake channel list (... problems, learned ...)
```

The mod also logs a summary of what it learned. When it says `Server accepted the fake mod list`,
you're through the handshake.

For NeoForge packs the mod reconnects automatically after learning (up to 4 times), so you normally
do not have to click anything. Each attempt learns more and the server kicks with
`Incompatible client! Please use NeoForge ...` until the versions match.

For GregTech: New Horizons the first connection is always rejected this way:

```
Mod rejections [FMLMod:BuildCraft|Factory{7.1.65}, ...]
```

The mod saves that list; reconnect once and the next connection gets through the mod check.
Usually two attempts are enough.

## Logs

Watch `logs/latest.log` for lines starting with `[ATM10 Spoof]`:

- `Dropped unreadable packet`: a modded packet was thrown away instead of disconnecting you.
- `Dropped tags for ... unknown registries`: modded registry tags were ignored.
- `Using local registries`: server registry data was incompatible, so client registries were used.
- `Configuration stalled`: a mod's config task never completed; the mod finished configuration itself.

## Options

- `-Datm10spoof.vfpVersion=1.21.1` - force the ViaFabricPlus target version. Normally the mod
  reads it from the server list entry. Use `1.7.10` for GTNH if auto-detection is not available
  (for example with `--quickPlayMultiplayer`).

## Tested

- All the Mods 10 5.5 (NeoForge 21.1.219, Minecraft 1.21.1)
- All the Mods 10: To the Sky 2.3.1 (NeoForge 21.1.250, Minecraft 1.21.1)
- All the Mods 11 0.14.0-beta (NeoForge 26.1.2.126, Minecraft 26.1.2)
- GregTech: New Horizons 2.9.0 (Forge 1.7.10)

All were tested with a Fabric client containing only Fabric API, ViaFabricPlus and this mod.
ATM10, ATM10 Sky, ATM11 and GTNH were tested on Minecraft 26.3 (Loader 0.20.1, ViaFabricPlus
5.1.3) with the same mod. ATM10 Sky was also tested on 26.2 earlier.

## Headless testing

`test/auto-test.sh` starts (or reuses) the local test server, runs the client under Xvfb, and
detects whether the client joined. It never opens a window on the desktop:

```sh
test/auto-test.sh atm10      # NeoForge 1.21.1
test/auto-test.sh atm10sky   # NeoForge 1.21.1, different pack version
test/auto-test.sh atm11      # NeoForge 26.1.2
test/auto-test.sh gtnh       # Forge 1.7.10
```

The script retries automatically when a handshake learned new data (FML mod lists or NeoForge
channels) and prints `PASS` or `FAIL`.
