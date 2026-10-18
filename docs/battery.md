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
