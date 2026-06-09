# Phonon Cluster — Hardware Setup Guide

This guide covers the hardware required to build a production-viable 6-phone
inference cluster using affordable, off-the-shelf Android devices.

## Why phones?

A 6-phone cluster running Gemma 4, Llama 3.2, or Mistral can deliver 30-60+
tokens/second aggregate — competitive with a $500+ cloud GPU instance, but
using devices you probably already own or can buy used for <$100 each.

The key insights:
- Phone NPUs (Qualcomm Hexagon, Samsung NPU, Google Tensor TPU) are idle 99%
  of the time
- A Pixel 7a or Galaxy S23 can run 2-3B models at native resolution on NPU
  or larger models via CPU with sharding
- 6 devices in a power-efficient cluster draw **<100W total** vs 300-500W for
  a desktop GPU

## Recommended hardware

### Phones

| Model | Why | Used Price | NPU | Notes |
|---|---|---|---|---|
| **Google Pixel 7a** | Best value — Tensor G2, great NPU, GrapheneOS first-class | $100-150 | Tensor TPU | Sweet spot for pool mode |
| **Google Pixel 8** | Better NPU, longer OS support | $200-280 | Tensor G3 | Future-proofing |
| **Samsung Galaxy S23** | Best overall NPU (Hexagon), can do larger models | $180-250 | Hexagon | Slightly more power draw |
| **OnePlus 11** | Good NPU, fast charging | $150-200 | Hexagon | Less GrapheneOS support |
| **Moto G Stylus 5G** (2024) | Cheap, good CPU for shard mode | $80-120 | Mid-range NPU | CPU-only recommendations |

For a **first cluster**, buy 6 matching Pixel 7a units. They're cheap, well
supported by GrapheneOS, and the Tensor TPU handles 2-3B models comfortably.

For a **mixed cluster**, use Pixel 7a for pool mode (NPU inference) and
Samsung Galaxy S23 for shard mode (CPU inference with prima.cpp).

### Networking

Every phone needs wired Ethernet for reliable, low-latency inference traffic.

| Component | Qty | Unit Price | Link |
|---|---|---|---|
| USB-C to Gigabit Ethernet adapter (ASIX AX88179 chipset) | 6 | $12-15 | [Amazon](https://www.amazon.com/dp/B00NOPA2L4) (UGREEN) |
| 8-port Gigabit switch (unmanaged) | 1 | $25-35 | [Amazon](https://www.amazon.com/dp/B07S953RS2) (TP-Link TL-SG108) |
| Cat6 Ethernet cables, 3ft | 6 | $2-3 | Monoprice / Amazon Basics |

**Chipset note:** Stick with ASIX AX88179-based adapters. Realtek rtl8153
works too but has higher power draw and occasional firmware issues on
GrapheneOS. Avoid Apple/Trendnet TB adapters — they use different drivers.

### Power

| Component | Qty | Unit Price | Link |
|---|---|---|---|
| Anker 60W USB-C charger (6-port) | 1 | $35-45 | [Amazon](https://www.amazon.com/dp/B08V2T4J32) |
| Short USB-C cables, 1ft (6-pack) | 1 | $10-15 | [Amazon](https://www.amazon.com/dp/B09DYV7VT8) |
| Smart power strip (per-port control) | 1 | $25-30 | [Amazon](https://www.amazon.com/dp/B07GXGVX78) (Kasa KP303) |

**Or** use individual 18W PD chargers (one per phone) if you already have
them — they're smaller but more cable mess.

### Mounting & Cooling

| Component | Qty | Unit Price | Link |
|---|---|---|---|
| Wire shelf rack, 4-tier, 36" wide | 1 | $35-50 | [Amazon](https://www.amazon.com/dp/B07F3YTFPX) |
| USB desk fan (clip-on) | 1-2 | $10-15 | [Amazon](https://www.amazon.com/dp/B08CPBVT5W) |
| Velcro cable ties (reusable) | 1 pack | $5-8 | Hardware store |

**Thermal note:** During sustained inference, phones can hit 40-45°C.
Position the fan to blow across the phones vertically. Don't stack phones on
top of each other — leave 2-3 inches for airflow.

## Bill of Materials (6-phone cluster)

| Item | Qty | Est. Cost |
|---|---|---|
| Pixel 7a (used, good condition) | 6 | $600-900 |
| USB-C Ethernet adapters | 6 | $72-90 |
| 8-port Gigabit switch | 1 | $25-35 |
| Cat6 cables (3ft) | 6 | $12-18 |
| 6-port USB-C charger | 1 | $35-45 |
| USB-C cables (1ft, 6-pack) | 1 | $10-15 |
| Wire shelf rack | 1 | $35-50 |
| Clip-on fan | 1-2 | $10-30 |
| Velcro ties | 1 pack | $5-8 |
| **Total** | | **$804-1,191** |

## Assembly

1. **Shelving**: Assemble wire rack. Place switch on top shelf, phones on
   shelves 2-4 (2 phones per shelf).
2. **Ethernet**: Connect switch to home network (DHCP). Plug each phone's
   Ethernet adapter into the switch.
3. **Power**: Route USB-C power cables from charger to each phone. Use
   velcro ties to keep cables tidy along the shelf legs.
4. **Fan**: Position fan to blow upward across the shelf levels. If you have
   two fans, mount one blowing up, one pulling from the top.
5. **Labeling**: LABEL EVERYTHING. Use a P-Touch labeler for each phone with
   its hostname and IP. The 5 minutes of labeling saves hours of debugging.

## Coordinating machine

The coordinator can run on any machine on the same LAN:

| Option | Pros | Cons |
|---|---|---|
| Raspberry Pi 4/5 | Low power, silent | Limited upgrade |
| Old laptop | Built-in UPS, screen for debugging | Takes space |
| Existing home server | Already running | May conflict with other services |

The coordinator needs only a Go binary — no GPU, no special hardware. It
weighs ~15MB and uses ~50MB RAM.

## Network layout

```
[Phone 1] ──USB-C── [ETH] ─┐
[Phone 2] ──USB-C── [ETH] ─┤
[Phone 3] ──USB-C── [ETH] ─┤─── [8-port Switch] ─── [Coordinator]
[Phone 4] ──USB-C── [ETH] ─┤
[Phone 5] ──USB-C── [ETH] ─┤
[Phone 6] ──USB-C── [ETH] ─┘
```

All devices on same subnet. Coordinator listens on `:8080`. Phones use mDNS
(`_phonon._tcp`) to discover the coordinator, or get it via DHCP option if
mDNS is unreliable.

## Power budget

| Component | Watts |
|---|---|
| 6× Pixel 7a (idle: 1-2W, infer: 4-7W) | 24-42W |
| 8-port Gigabit switch | 5-8W |
| USB fan | 2-5W |
| Coordinator (Pi 5) | 15-25W |
| **Total** | **46-80W** |

At US average $0.12/kWh: **$48-84/year** to run 24/7.

Compare: RTX 4090 at 450W = $473/year in electricity alone.

## Future upgrades

- Water cooling (yes, for phones — there's a guide)
- 40GbE network for large cluster (unlikely needed before 24+ phones)

### Power delivery options (investigating)

**PD passthrough Ethernet adapters** — tested, recommended for first cluster.
A USB-C to Ethernet adapter with PD 2.0/3.0 passthrough lets the phone charge
and get wired networking through a single USB-C port. The BENFEI USB-C to
Ethernet Gigabit Adapter with 100W PD (~$18 CAD) or Cable Matters equivalent
(~$25-37 CAD) are proven on Pixels. One cable to the phone, two cables behind
the adapter (Ethernet + USB-C PD charger). Cleaner than separate Ethernet +
charging cables, no hub needed.

**PoE to USB-C converters** — exists but unvalidated for this use case.
Gigabit PoE to USB-C converters (e.g. Amazon ASIN B0CP296VXN, ~$40 CAD)
take 802.3af PoE input and output USB-C with 10W charging + data. 10W may
not sustain a phone under inference load (phones can draw 18-27W under load).
Worth testing once the thermal/power profile is characterized on real hardware.
Would eliminate the need for USB-C chargers entirely — one Ethernet cable
from a PoE switch handles both power and data.

**Wireless charging + USB-C Ethernet** — investigate, thermal risk.
Putting the phone on a Qi wireless pad while using a plain USB-C Ethernet
adapter (no PD passthrough) separates power and data into two independent
paths. Pros: simpler Ethernet adapter (Anker A8313 at $15-20, most proven),
no PD negotiation complexity, only one cable to manage at the phone. Cons:
wireless charging is ~70-80% efficient (vs 90%+ wired) and generates waste
heat — stacked on top of inference heat, this may push Pixels into thermal
throttling faster. Worth testing with one phone under sustained load before
committing to this path for the full cluster.

## See also

- `GRAPHEMEOS_SETUP.md` — First-run GrapheneOS configuration for cluster phones
- `NETWORK_TUNING.md` — MTU, jitter, and QoS for inference traffic (future)
