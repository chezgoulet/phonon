# Battery and thermals

The phones are appliances: permanently plugged into USB power, permanently on
Ethernet, permanently inferring. That is the operating assumption of both the app
and the ROM, and it is what makes battery health a safety question rather than a
convenience question.

## What is being managed

Three different aging mechanisms, often conflated:

1. **Calendar aging** — capacity lost over time regardless of use. Accelerated by
   heat and by high state of charge.
2. **Cycle aging** — capacity lost per full charge/discharge cycle.
3. **Thermal damage** — permanent, and the fastest of the three when a phone is
   generating at 45 °C for hours.

A phone held at 100% and 45 °C ages faster than one held at 80% and 35 °C. The
variables worth controlling are **state of charge** and **temperature**, not
cycle count — and deliberately cycling the battery adds cycle aging in exchange
for a SoC benefit that has to be measured, not assumed.

## What the app can do (stock Android)

- **Nothing about charging.** An unprivileged app cannot stop or start the
  charger. The platform owns it.
- **Tell the user to use Charging optimization → Limit to 80%**, which current
  Pixel builds provide. This is the single largest intervention and it is free.
- **Read battery state** through the standard `BatteryManager` interface:
  temperature, voltage, charge counter, health, and cycle count where the
  platform exposes it. Temperature is the important one.
- **Schedule inference around temperature**: pause or reduce concurrency above a
  threshold, resume when cool. This is the app's real thermal lever.
- **Refuse to run on a battery reported as overheated or failing**, and surface
  that state loudly.

## What the app does today

Implemented in the app (v0.1.0):

- **Charge-state policy.** The battery is sampled on the thermal monitor's
  30-second tick. When the phone is unplugged and the level falls below
  `battery_pause_below` (default 15%), serving pauses with the reason `battery`;
  it resumes when the charger is connected or the level reaches
  `battery_resume_above` (default 25%). A phone left on a shelf is not run flat
  by an idle agent.
- **Health reporting.** `/health` carries `battery_level`, `battery_charging`
  and `pause_reason`, so a gateway or an operator can see why a node stopped
  before asking it.
- **Thermal pause drains first.** A pause no longer kills a completion mid
  stream. New requests are refused as soon as the state leaves `ready`, and the
  child is stopped once the in-flight requests finish, with a 120-second ceiling.

Not implemented, and not claimed: charge limiting, charge scheduling and
deliberate cycling. Those need the charger, which an unprivileged app does not
own; the ROM work is where they belong. The single largest intervention remains
the platform's **Charging optimization → Limit to 80%**.

## What active cycling requires

Discharge to a floor and recharge to a ceiling, repeatedly, is not available to
an unprivileged app. It needs one of:

- a switchable power source the app can command (a smart plug),
- root and the charger's kernel interface, or
- a ROM that owns the charging path.

This is why cycling belongs to the appliance-image or ROM milestone, not to the
app milestone. A spec that says the app cycles the battery is a milestone that
cannot be met.

## What we assume for the hardware

- USB-C hub with **Power Delivery passthrough and gigabit Ethernet** (ASIX
  AX88179-class adapter; Realtek works but draws more and has had firmware
  quirks). Android's Ethernet stack is native; no app is needed for networking.
- Airflow across the phone, no case, no stacking. A clip-on fan is cheaper than
  a replacement battery.
- A charger that is not marginal. A phone that browns out under load is a
  corrupted filesystem waiting to happen.
- The battery is a consumable. Budget for replacement; retire any cell that
  swells, and never enclose a phone that is swelling.

## Safety

A lithium cell held at high charge and high temperature for months is a fire
risk, not just a longevity risk. The appliance image should:

- cap charge at 80% where the platform allows it;
- stop inference above a temperature threshold rather than throttling silently;
- log temperature continuously so a trend is visible before it becomes a bulge;
- refuse to start if the battery reports an unhealthy state.

## Open question

Whether a permanently-plugged phone at 80% is meaningfully better off being
cycled between 40% and 80% is **not established**. It depends on the cell, the
temperature and the duty cycle. Measure both if the ROM makes cycling possible;
until then, do not encode an assumption into the product.

## What the app can now establish (0.1.19)

The Serve screen carries a charging card and `/health` a `charge` block:

```json
"charge": {
  "mode": "limit-80",          // limit-80 | adaptive | off | unknown
  "source": "secure",          // secure | plateau | recalibration | none
  "confirmed": true,
  "hours_above_80": 12,
  "can_toggle": false,
  "detail": "read from Settings.Secure"
}
```

**Reading the setting.** `Settings.Secure.charge_optimization_mode` is 1 for
"Limit to 80%" and 0 for off on Pixel. Verified on the reference Pixel 7
(Android 15): the key exists, `settings put secure charge_optimization_mode 1`
persists and reads back, and the device's only charge-related secure key is
that one. `adaptive_charging_enabled` **does not exist** on this build — the
code reads it as absent and does not write it, rather than inventing a value.

**Writing the setting.** Only with `WRITE_SECURE_SETTINGS`, which an ordinary
install never holds: it is granted by `adb shell pm grant` or by a kiosk/device
owner install. Without it the card shows the OEM screen and an explanation —
never a switch that writes nothing. With it, the switch writes the key and the
platform's own state is what the card reflects on the next `/health` poll.

**Inferring it.** Without the permission, the app will not claim the limit is
on from a single reading. It needs: plugged in, flat inside 80 ± 1, for at
least 20 minutes, with at least four observations. That is reported as
`source: "plateau"`, `confirmed: true`. Anything less is `unknown`.

**Recalibration.** Pixel charges to 100% periodically to recalibrate capacity
even with the limit on. A 100% charge within 12 hours of a confirmed plateau is
reported as `mode: limit-80`, `source: "recalibration"`, `confirmed: false` —
not as the limit having been turned off, and it does not re-alarm.

**Hours above 80%.** The service records one state-of-charge sample an hour into
a persisted seven-day ring and reports the hours spent above 80% in the window.
Each sample counts for at most one hour, so a gap in sampling under-reports
rather than inventing time. The number is in `/health` and in the device report
under `charge.hours_above_80`.

### What is still the platform's job

Nothing here starts or stops the charger. The app writes a setting the platform
already implements and reads back what the platform says; on a device that does
not expose the key, the only honest answer is `unknown` plus a link to the OEM
screen.
