# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **RuneLite client plugin** for Old School RuneScape (despite the `Backend` repo name — the "backend" is Supabase). The plugin (`PluginDescriptor` name "Loot to JSON") observes in-game events, derives high-level player activity, and writes structured `GameEvent` records to two sinks: a local `loot_log.jsonl` file and a Supabase REST table. Java 11, Gradle, Lombok.

## Commands

- Build: `./gradlew build`
- Test compile / unit tests: `./gradlew test` (JUnit 4; the only test, `LootLoggerPluginTest`, is actually a launcher `main`, not an assertion test)
- Run the client with this plugin loaded in developer mode: run the `main` in `com.lootlogger.LootLoggerPluginTest` (it calls `ExternalPluginManager.loadBuiltin(LootLoggerPlugin.class)` then `RuneLite.main`). Easiest via IDE run config.
- Package a distributable jar: `./gradlew shadowJar`

### Build-config gotcha (important)

`build.gradle`, `settings.gradle`, and `runelite-plugin.properties` are still the **unmodified RuneLite example template**: `group = com.example`, `rootProject.name = 'example'`, and `pluginMainClass = 'com.example.ExamplePluginTest'`. The real code lives under `com.lootlogger`. Consequences:
- `./gradlew run` is **broken** — it points at the nonexistent `com.example.ExamplePluginTest`. Run `com.lootlogger.LootLoggerPluginTest` directly instead, or fix `pluginMainClass`.
- `runelite-plugin.properties` (displayName/author/plugins) is template boilerplate and does not describe this plugin.

If asked to make the plugin installable/publishable, these files need to be reconciled with `com.lootlogger`.

## Configuration & secrets

`gradle.properties` holds `SUPABASE_URL` and `SUPABASE_KEY`. It is **gitignored**, and `LootWriter.init()` reads it from the working directory at runtime via `Properties` (not via Gradle). So the file must exist next to wherever the client is launched. If Supabase props are missing, the plugin still logs locally to `loot_log.jsonl`.

## Architecture

The plugin is a single large event-driven class, `LootLoggerPlugin`, that subscribes to RuneLite events and emits `GameEvent`s through one funnel.

**Event funnel.** Everything ends at `queueEvent(...)`, which builds a `GameEvent` (stamping session id + player world coords) and hands it to `LootWriter.queueRecord` on the injected `ScheduledExecutorService`. `LootWriter` writes each record to `loot_log.jsonl` immediately and buffers a copy; a `@Schedule(period = 10s)` `submitBatch()` flushes the buffer to Supabase (`POST /rest/v1/loot_logs`) as a single batched async HTTP call. One reused `HttpClient`.

**The Net-Diff Engine** (`processNetItemDiff`, runs every `onGameTick`) is the core. Rather than trusting individual item events, it diffs a combined snapshot of inventory + equipment quantities against the previous tick to compute net gained / net lost items, then **infers intent** from surrounding UI/context to classify the change:
- Losses → `CONSUME` (item has Eat/Drink action; HP healed this tick is attributed to it), `SPELL_CAST` (name ends in "rune"), or `RANGED_FIRE` (arrow/bolt/dart/etc.).
- Gains → `SHOP_TRANSACTION` (shop widget open), `DIALOGUE_REWARD` (a dialogue widget was open within the last 3 ticks; quest vs NPC by widget/quest state), or `TAKE` (last menu option was "take").
- Diffs are **suppressed** while bank/GE/deposit-box widgets are open to avoid spurious gain/loss noise.

**Context locking.** `onMenuOptionClicked` captures the last clicked option/target and "locks" a skilling target, combat target, manual spell, or shop target. These locked strings are what later events (e.g. XP gains, spell casts) attribute activity to. `onStatChanged` turns XP deltas into `XP_GAIN` events, choosing combat vs skilling category and resolving the source from the locked targets (with magic autocast decoded from varp 108 via `getStandardAutocastSpell`).

**Other tracked signals:** `NpcLootReceived` → `NPC_DROP`; quest state machine in `checkQuestProgress` (per-tick `IN_PROGRESS` tick counters persisted to RuneLite config under group `lootlogger` as `qticks_<id>`, plus `QUEST_PROGRESS`/quest-point reward events); shop stock snapshots and monster-examine text scraping driven off raw widget IDs.

**Widget IDs are hardcoded magic numbers** throughout (group 300 = shop, 12 = bank, 465 = GE, 522 = monster examine, dialogue groups `{231,217,193,229,277}`, etc.). These are brittle against game updates — treat them as the main correctness risk when behavior drifts.

**State seeding.** On login/startup (`onGameStateChanged`, `startUp`) the plugin seeds XP and quest maps and uses `questSyncTicks`/`hpInitialized`/`previousNetItems.clear()` guards to avoid emitting fake diffs from the initial calibration tick.

### Dead code

`util/InventoryProcessor` plus `data/{InventoryEvent, PlayerState, ActionType}` implement an older, richer action-classification pipeline that is **not referenced anywhere by `LootLoggerPlugin`**. The live plugin uses only `GameEvent` + `DroppedItem`. Don't assume `ActionType`/`InventoryProcessor` changes affect runtime behavior — they don't, currently.

## Data model

`GameEvent` (Lombok `@Builder`, auto `timestamp`) is the wire/log format — note the codebase emits string `eventType` constants (`"CONSUME"`, `"XP_GAIN"`, `"NPC_DROP"`, ...) rather than the `ActionType` enum. `DroppedItem` carries id, name, qty, and three valuations: `GE` (live GE price), `HA` (high-alch), and `basePrice` (OSRS store value) — all multiplied by qty in `createDroppedItem`.
