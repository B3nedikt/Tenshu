# How-to: Deploy the Tenshu Hub (H1) on a Raspberry Pi 5

The `:hub` module is the headless Hub runtime of
[`hub-hardware-and-management-service.md`](../proposals/hub-hardware-and-management-service.md)
§13 **H1**: `control-desktop`'s production proxy assembly with no UI, run as a systemd
service on an ARM64 Linux node. Pairing is manual for now (terminal QR over the standard
offer flow); H2 replaces it with the printed static-key variant.

## 1. Build the distributable

On the dev machine (any OS; the artifact is portable JVM bytecode):

```bash
./gradlew :hub:distTar
# → hub/build/distributions/tenshu-hub.tar  (bin/tenshu-hub + lib/*.jar)
```

## 2. Prepare the Pi

Raspberry Pi OS (64-bit) or any ARM64 Debian. The hub needs a JRE 21+:

```bash
sudo apt install openjdk-21-jre-headless
sudo useradd --system --home /var/lib/tenshu-hub --create-home --shell /usr/sbin/nologin tenshu
```

Copy + unpack:

```bash
scp hub/build/distributions/tenshu-hub.tar pi@<hub-host>:/tmp/
ssh pi@<hub-host>
sudo mkdir -p /opt && sudo tar -xf /tmp/tenshu-hub.tar -C /opt
sudo mv /opt/tenshu-hub* /opt/tenshu-hub   # strip any version suffix
```

## 3. First-run pairing (interactive, once)

Run the `pair` subcommand in a terminal **as the service user and with the service's
data dir**, so the keys land where the service will look for them:

```bash
sudo -u tenshu TENSHU_HUB_DATA_DIR=/var/lib/tenshu-hub /opt/tenshu-hub/bin/tenshu-hub pair
```

A QR code prints in the terminal. On a paired Tenshu device: *Add a device → Scan*, scan
it, check the device name, approve. The CLI prints `[approved]` plus the confirmation
code and exits. (`--invert-qr` if your terminal is light-themed and the scan fails;
`--relay-url` to target a non-production relay — then set the same
`TENSHU_RELAY_URL` in the unit.)

## 4. Install the service

```bash
sudo cp /opt/tenshu-hub/…/hub/deploy/tenshu-hub.service /etc/systemd/system/   # or scp it from the repo
sudo systemctl daemon-reload
sudo systemctl enable --now tenshu-hub
journalctl -u tenshu-hub -f     # expect: "paired (owner …)" then "[running] Headless proxy up"
```

## 5. Verify it survives reboot

```bash
sudo reboot
# after it comes back:
journalctl -u tenshu-hub -b | grep tenshu-hub:
```

The startup lines must say `paired (owner …)` — not `not paired`. Durable identity is the
point of the hub's file-backed key store (`FileWrappingKeyStore`): unlike a Linux desktop
run of Control, the realm keys survive restarts. Then make a change on another realm
device (create a folder in Drive) and watch it arrive in the journal-quiet background —
verify from the other side: the hub shows up in Control's device list.

## 6. Full-disk encryption (LUKS) — required for the H3 backup role

H3 makes the hub the realm's **keep-everything replica**: every file's content lands as
**plaintext** under `<data-dir>/content/` (and `wrap.key` sits beside `secrets.enc` —
POSIX permissions only, see threat-model T-C-17's hub notes). The disk-level answer is
**LUKS on the data disk** (hub proposal §12.1). Put the data dir on an encrypted NVMe
partition before enabling the backup role:

```bash
sudo apt install cryptsetup
sudo cryptsetup luksFormat /dev/nvme0n1p1          # DESTROYS the partition — pick a strong passphrase
sudo cryptsetup open /dev/nvme0n1p1 tenshu-data
sudo mkfs.ext4 /dev/mapper/tenshu-data
echo 'tenshu-data /dev/nvme0n1p1 none luks' | sudo tee -a /etc/crypttab
echo '/dev/mapper/tenshu-data /var/lib/tenshu-hub ext4 defaults 0 2' | sudo tee -a /etc/fstab
sudo mount /var/lib/tenshu-hub && sudo chown tenshu: /var/lib/tenshu-hub
```

Note the trade: with a passphrase in `crypttab` you must enter it (serial console /
keyboard) after a power cut; a keyfile on the SD card auto-unlocks but only defends
against theft of the NVMe *alone*. Either beats plaintext. (A "retain ciphertext, decrypt
on demand" hub mode is a noted future alternative — not built.)

## 7. Zigbee devices (HD-2): mosquitto + zigbee2mqtt beside the hub

[`home-device-service.md`](../proposals/home-device-service.md) HD-D3: the JVM is the brain,
radios are **sidecars**. Two upstream services run next to `tenshu-hub` and the hub talks to
them as an MQTT *client* — outbound-only, even here (it opens a connection to loopback; it
never listens).

```
tenshu-hub ──MQTT client──▶ mosquitto (127.0.0.1:1883) ◀──MQTT client── zigbee2mqtt ──USB──▶ radio
```

### 7.1 The radio

⚠ **The Pi 5 has no Zigbee radio.** Its built-in wireless is Wi-Fi + Bluetooth only — Zigbee
needs separate hardware:

- **USB coordinator (recommended)** — Sonoff ZBDongle-E (EFR32MG21) or the SkyConnect. Plug it
  in, ideally on a short USB *extension* away from the Pi's USB 3 ports: USB 3 is a famous
  2.4 GHz noise source and a dongle sitting right next to one drops packets.
- **GPIO HAT** — no cable, but it occupies the header and sits in the Pi's own RF noise.

Find the device path (prefer the stable one):

```bash
ls -l /dev/serial/by-id/
# → usb-ITead_Sonoff_Zigbee_3.0_USB_Dongle_Plus_… -> ../../ttyUSB0
```

### 7.2 mosquitto — loopback only

```bash
sudo apt install mosquitto
sudo cp /opt/tenshu-hub/…/hub/deploy/mosquitto-tenshu.conf /etc/mosquitto/conf.d/tenshu.conf
sudo systemctl restart mosquitto
ss -ltnp | grep 1883        # MUST show 127.0.0.1:1883 — never 0.0.0.0:1883
```

⚠ The shipped config **binds to 127.0.0.1 and to nothing else**. The broker joins two
processes on one machine; it is not a LAN automation bus. A broker on 0.0.0.0 hands anyone on
the network the ability to switch every device and to watch the household's occupancy in
plaintext. If you genuinely need a LAN listener later, `allow_anonymous false` + a password
file is the *minimum* and TLS is the real answer — set `TENSHU_MQTT_USERNAME` /
`TENSHU_MQTT_PASSWORD` in the hub's unit to match (credentials ride the environment, never
argv: `/proc/<pid>/cmdline` is world-readable).

### 7.3 zigbee2mqtt

Install upstream (their docs are the reference; the Debian instructions are the ones to
follow on Pi OS), then take the Tenshu-shaped config:

```bash
sudo -u zigbee2mqtt cp /opt/tenshu-hub/…/hub/deploy/zigbee2mqtt-configuration.yaml \
    /opt/zigbee2mqtt/data/configuration.yaml
sudo -u zigbee2mqtt nano /opt/zigbee2mqtt/data/configuration.yaml   # set serial.port
sudo usermod -aG dialout zigbee2mqtt
sudo cp /opt/tenshu-hub/…/hub/deploy/zigbee2mqtt.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now zigbee2mqtt
journalctl -u zigbee2mqtt -f    # expect "Coordinator firmware version" then "Zigbee: allowing new devices to join: false"
```

Two settings in that file are load-bearing rather than taste:

- `permit_join: false` — **permanently**. Joining is driven from Home as an explicit,
  time-bounded act (§7.5); a `true` here would leave the household's radio open to the
  neighbourhood forever.
- `advanced.network_key: GENERATE` — z2m mints a random Zigbee network key on first start and
  writes it back into `configuration.yaml`. That file is now a **household credential**: put
  the z2m data dir on the LUKS volume (§6), same at-rest class as the hub's replica.

### 7.4 Point the hub at it

```ini
# /etc/systemd/system/tenshu-hub.service  (ExecStart line)
ExecStart=/opt/tenshu-hub/bin/tenshu-hub run --zigbee2mqtt
```

Defaults: broker `tcp://127.0.0.1:1883` (`--mqtt-url` / `$TENSHU_MQTT_URL`), base topic
`zigbee2mqtt` (`--mqtt-base-topic` / `$TENSHU_MQTT_BASE_TOPIC`). With a bridge enabled the hub
also drops to the HD-D2 fast sync cadence (5 s) unless `--sync-interval-ms` says otherwise, so
a phone tap reaches the radio within about a poll.

```bash
sudo systemctl daemon-reload && sudo systemctl restart tenshu-hub
journalctl -u tenshu-hub -f
# expect: "[devices] zigbee2mqtt bridge up (broker tcp://127.0.0.1:1883, base topic zigbee2mqtt …)"
#         "zigbee2mqtt: connected; subscribed to zigbee2mqtt/#"
#         "zigbee2mqtt: bridge reports online"
```

Order does not matter: the hub reconnects with exponential backoff, and z2m's retained
`bridge/devices` topic replays the whole inventory whenever the hub subscribes.

### 7.5 Your first bulb, end to end

1. **In Home** (desktop today; Android with HD-3): *Devices → Add device*. The dialog names
   the bridge it is pairing through and shows *Open pairing window*.
2. Press it. The hub opens the radio for 60 s (clamped to 5 min however much is asked for) and
   the dialog counts down. `journalctl -u zigbee2mqtt` shows
   `Zigbee: allowing new devices to join: true`.
3. **Put the bulb into pairing mode.** For most bulbs: power-cycle it 5–6 times, ~1 s on / 1 s
   off, until it flashes. (IKEA: hold the remote's pairing button next to it; Hue: use a
   Hue dimmer's on+off, or a fresh-out-of-box bulb joins on its own.)
4. Within a few seconds z2m logs `Successfully interviewed '0x…'`, the device appears in the
   dialog's *Joined* list, and a row lands in the Devices tab — on **every** realm device,
   because it is ordinary synced data.
5. Toggle it from the phone or the desktop. Expect: `zigbee2mqtt/<name>/set` in the z2m log,
   the bulb changing, then the confirmed state coming back on the row.
6. Close the window (or let it expire) and check `journalctl -u zigbee2mqtt` says
   `allowing new devices to join: false` again.

Renaming a device inside zigbee2mqtt is safe — Tenshu keys devices by IEEE address, so a
rename moves the MQTT topics without touching the device's identity, its room, or the name you
gave it in Home. (The reverse also holds: renaming in Home does not rename it in z2m.)

### 7.6 When it does not work

| Symptom | Look at |
| --- | --- |
| Hub logs `connect failed … retrying` | `ss -ltnp \| grep 1883`; is mosquitto up and on loopback? |
| Hub connects, no devices ever appear | `mosquitto_sub -h 127.0.0.1 -t 'zigbee2mqtt/bridge/devices' -v` — is z2m publishing (retained) at all? |
| z2m will not start | `journalctl -u zigbee2mqtt`: wrong `serial.port`, or the user is not in `dialout` |
| Bulb never joins | The window really was open (z2m log), the bulb really is in pairing mode, and it is within a few metres of the coordinator for the interview |
| Devices drop out constantly | 2.4 GHz interference: move the dongle onto a USB extension away from the Pi and from the Wi-Fi antenna; consider a Zigbee channel away from your Wi-Fi channel |
| A command does nothing after a long offline spell | Expected: commands older than 2 minutes are refused by design (a backlog must not flip lights at 3 a.m.). Issue it again. |

## Notes

- **Data dir**: `/var/lib/tenshu-hub` (unit-set). Everything durable lives there —
  `objects.db`, `secrets.enc`, `wrap.key`, and (H3) the `content/` file-byte replica.
  Wiping it factory-resets the hub (re-pair).
- **At rest**: `wrap.key` protects `secrets.enc` but sits on the same disk — POSIX
  permissions only. With H3 the content replica is plaintext too; §6 above (LUKS) is the
  disk-level answer.
- **Backup role (H3)**: once paired, the hub pins its own device-scoped sync policy to
  *keep everything offline* and periodically drains the offline keeper, so every file's
  bytes are fetched to local disk and survive restarts — and relay loss. Cadence:
  `--backup-interval-ms` (default 15 min). Eviction never runs on the hub.
- **Sync cadence**: production default 30 s; `--sync-interval-ms` to tune.
- **Management (H4)**: once paired, the hub publishes a `HubStatus` row (name, version,
  uptime anchor, replica figures, last backup pass) any realm device reads locally, and
  consumes `RestartHub` / `RunBackupNow` command rows arriving via sync — at most once,
  with a durable `consumed-commands.log` in the data dir and a 15-min staleness window.
  A `RestartHub(mode="proxy")` exits the process with code **10** (`EXIT_RESTART`); the
  unit's `Restart=always` re-spawns it, so the journal shows a deliberate exit-10 rather
  than a crash. `mode="system"` (node reboot) is not implemented in v1.
- **Devices (HD-1/HD-2)**: `--simulated-devices` bridges two fake devices (and, in that mode,
  a fake bridge you can "pair" a fake bulb into) for a hardware-free demo; `--zigbee2mqtt`
  bridges the real radio (§7). Both are OFF by default. Each bridge keeps its own durable
  consumed-command journal in the data dir (`consumed-device-commands.log`,
  `consumed-zigbee-commands.log`) — that plus the 2-minute staleness window is what makes a
  physical actuation at-most-once and never replayed from a backlog.
