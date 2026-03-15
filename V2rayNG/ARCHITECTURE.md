# XrayNG App Architecture

## Current shape

The Android app is still centered on a classic View system stack:

- `ui/`: Activities, Fragments, RecyclerView adapters, and screen-specific controllers.
- `viewmodel/`: Presentation state holders for list and settings screens.
- `handler/`: Process-wide managers for config, storage, settings, sync, and service control.
- `dto/`: Cross-layer data structures persisted in MMKV or passed between screens.

This shape is workable, but the main architecture risk is that many screens still depend on global `object` managers directly. That creates three problems:

- Business logic is spread across `Activity`, `ViewModel`, and `handler` classes.
- Storage concerns leak into presentation code.
- Unit testing becomes expensive because behavior is coupled to MMKV and process-wide state.

## Direction

The next safe step is not a full module split. It is a dependency-direction cleanup inside the existing `:app` module:

`UI -> ViewModel -> repository/notifier -> handler/storage`

Rules for new code:

- `Activity` and `Fragment` should only coordinate UI, navigation, and user events.
- `ViewModel` should not talk to `MmkvManager`, `SettingsManager`, or `SettingsChangeManager` directly.
- Storage and side-effect entry points should be wrapped behind small interfaces first.
- Existing `handler/*` objects remain the implementation detail until a larger module split is justified.

## Applied in this branch

Refactors already applied:

- `SubscriptionsViewModel`
- `RoutingSettingsViewModel`
- `PerAppProxyViewModel`
- `MainViewModel` server-list storage access and snapshot building
- `MainViewModel` server testing orchestration and delay-result parsing
- `MainViewModel` broadcast message interpretation and service event application

The settings-oriented ViewModels now depend on small repository/notifier abstractions instead of static managers. `MainViewModel` now routes server and subscription storage through a repository, delegates list snapshot construction to a dedicated builder, moves server testing coordination into a dedicated collaborator, and interprets service broadcasts through a dedicated event layer. This keeps runtime behavior unchanged while making the data path and testing path testable in isolation.

## Next candidates

High-value follow-up targets:

1. Split `MainViewModel` into server-list state, speed-test orchestration, and service-state observation.
2. Wrap `AngConfigManager` and `V2rayConfigManager` behind use-case style entry points.
3. Move MMKV key ownership closer to feature repositories so storage contracts stop leaking across the app.
