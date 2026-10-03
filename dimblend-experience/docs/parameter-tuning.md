# Parameter Tuning

Open the live panel with `/dbx tune` or the unbound **Parameter Tuning** key in
Controls. Changing server settings requires permission level 2. The screen does
not pause the game. Labels, descriptions, validation and status messages support
English (`en_us`) and Simplified Chinese (`zh_cn`) through Minecraft's language
selection.

Validated numeric input is submitted after a short typing delay. Checkboxes,
steppers and per-setting resets apply immediately. Each request edits one setting;
the server validates bounds and precision, saves it, then broadcasts the current
values. Changes also reach other open panels and client-side coupler prediction.
Missing optional mods leave their controls disabled.

| Setting | Default | Valid values | Owner |
| --- | --- | --- | --- |
| Train shaking draw interval | 2.0 seconds | 0 = disabled; 0.1-60.0, step 0.1 | experience |
| Train additional lateral force | 1200 pN | 0-10000, step 1 | experience |
| Dirtiness probability multiplier | 0.5 | 0.00-1.00, step 0.01; 0 = no soiling draws | carwash |
| Allow coupler redstone activation | false | Checkbox | experience |
| Allow Survival right-click on couplers | false | Checkbox | experience |
| Limited-water checks | true | Checkbox | experience |
| Portable Engine quantity limit | true | Checkbox | experience |
| Steam Engine overload penalty | true | Checkbox | experience |
| Small/Modular/Large Diesel Engine overload penalty | true | Checkbox | experience |
| Electric Motor overload penalty | true | Checkbox | experience |

## Ownership and Persistence

The panel and its network protocol live in experience. Carwash registers its own
SERVER config and exposes `dimblend.carwash.api.CarwashTuning`; experience has a
compile-only project dependency and an optional runtime dependency. The bridge
is loaded only when carwash is present. Carwash can run without experience, and
experience can run without carwash.

Settings persist in the world's `serverconfig/dimblend_experience-server.toml`
and `serverconfig/dimblend_carwash-server.toml`. Carwash remains the only source
of truth for its multiplier. Its next one-second settlement uses the new value:
`min(1, speed / 12 * multiplier)` above 4 m/s. Rain and other washing continue
when the multiplier is zero.

The existing `couplerRedstone` config uses the inverse meaning: true blocks
redstone effects. The panel displays the positive **allow** meaning. Survival
right-click permission affects couplers only; breaking and explosion protection
remain independent.

Diesel and motor penalty switches are separate from their existing behavior
switches, preserving RPM, redstone and energy rules. Disabling penalties clears
loaded engines' active warning/fuse/latch state on their next tick. Disabling
the train interval clears its countdown and active push; changing a nonzero
interval restarts the countdown. Changing force affects subsequent physics steps.
Disabling a rule cannot undo damage that has already occurred. Re-enabling the
Portable Engine limit resumes the existing enforcement on tracked networks.

## Verification

Run `gradlew.bat :dimblend-experience:build :dimblend-carwash:build` with JDK 21.
Tests cover numeric bounds and precision, translations, configurable grime and
force math, and clearing diesel overload state without changing its RPM state.

In game, check both languages and GUI scales, persistence after reconnect,
permission rejection, each rule's live effect, and the disabled carwash row when
carwash is absent. Test worlds should enable cheats for permission level 2.
