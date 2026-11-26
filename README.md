# Wireless CarPlay + Android Auto for HMG-API

Use wireless CarPlay and Android Auto on a compatible Gen5W head unit through the
car's own Wi-Fi hotspot.

> **Turn-by-turn moved (from 3.0).** Your phone's directions on the instrument cluster
> and HUD are now their own mod,
> [Phone Turn-by-Turn](https://github.com/Open-HMG/hmgapi-projection-tbt), which works
> for wired phones too. Install it next to this one to keep them. Versions 2.x of this
> mod still include turn-by-turn. **3.0 is set up but not released**, and hasn't been on
> a car yet.

[Download the mod](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases) ·
[Confirmed cars](#confirmed-cars) ·
[Install and connect](#install-and-connect) ·
[Get help](#troubleshooting)

## Confirmed cars

These are the model, region and firmware combinations with documented on-car
results. Check the six-digit firmware suffix under **Settings → General →
Software info**. The mod requires a Gen5W head unit with Wi-Fi hardware and an
enabled HMG-API hook map. [HMG-API supported cars and hook maps](https://github.com/Open-HMG/HMG-API/wiki/Supported-cars)

| Car | Region | Firmware |
| --- | --- | --- |
| Kia EV6 (`CVEV`) | USA | `260507` |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`) | USA | `260507` |
| Hyundai Ioniq 6 (`CEEV`) | USA | `260507`, `260903` |
| Hyundai Ioniq 6 (`CEEV`) | Canada | `260206`, `260903` |
| Hyundai Elantra N (`CN7NPE`) | USA | `260903` |
| Kia K5 | USA | `260507` |

For the full list of model codes and firmware the installer accepts, see
[`system` in the mod manifest](wireless-carplay-aa/mod.json#L21).

## Install and connect

1. Install or update HMG-API. Mod version **2.2.0-rc.8** requires **HMG-API
   1.1.0-rc.6 or newer**. [HMG-API installation guide](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)
2. Download `wireless-carplay-aa-<version>.zip` and copy it, still zipped, into
   `HMGAPI/updates/` on a USB stick. [Mod releases](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases)
3. Plug in the stick. In HMG-API, select **Check USB updates**, then **Wireless
   CarPlay + Android Auto**. On the main screen, tap **Apply changes**. Restart the
   head unit if prompted.
4. Remove the installation stick. If the phone was already paired, delete it
   from **Device Connections** on the head unit. On the phone, forget the car in
   Bluetooth and remove it from CarPlay or Android Auto settings.
5. With Bluetooth and Wi-Fi on, open **Phone Projection → Device Connections**
   on the head unit and pair the phone.

**Alternative NEMESIS installation path**
Users can extract `wireless-carplay-aa-<version>-nemesis.zip` to the
root of a USB stick and confirm the install in NEMESIS Manager. HMG-API must
already be installed. The guide also covers adb, updates and removal.
[Installing and removing mods](https://github.com/Open-HMG/HMG-API/wiki/Installing-and-removing-mods)

Updates require the same author key as the installed copy. If you installed a
test build signed with a different key, remove it and apply the removal before
installing the release. To uninstall an app-installed copy, choose **Remove**
in HMG-API and tap **Apply changes** to restore the files the mod changed.

## Features and settings

Open the mod in HMG-API to change its settings. Tap **Save settings** before
**Apply changes**, then restart the head unit if prompted.

- **Wireless CarPlay audio and Now Playing** add music and Siri over Wi-Fi and
  refresh song, artist and album art after connection.
- **Phone Projection** opens **Device Connections** from the settings menu on US
  and Canadian builds. USB drives and hubs can stay plugged in without
  triggering wired Android Auto.
- **Turn-by-turn directions** on the cluster and HUD come from the
  [Phone Turn-by-Turn](https://github.com/Open-HMG/hmgapi-projection-tbt) mod. With both
  installed, this mod keeps the iPhone's route guidance flowing after wireless CarPlay's
  Bluetooth-to-Wi-Fi handover. [How the two mods work together](docs/tbt-contract.md)
- **Experimental: Park and reconnect** ends the wireless session when you park and leave so
  calls and audio return to your phone. When you start the car, the head unit
  asks the last phone to reconnect. Pick another phone in **Device Connections**
  to switch.

## Troubleshooting

- **Your phone finds the car but won't connect.** Try the hotspot firewall
  option for CarPlay or Android Auto in the mod's settings. Both options are off
  by default. Tap **Save settings**, then **Apply changes**, and retry.
  [CarPlay setup notes](docs/cn7npe-usa-260903.md) ·
  [Android Auto setup notes](docs/ioniq6-can-260206.md)
- **HMG-API says the hook map is missing.** Send a Car Report to
  [HMG-API](https://github.com/Open-HMG/HMG-API/issues/new/choose).
- **Need more help?** Use **Add my car** if the car or firmware is missing from
  the mod manifest. For a bug, include your car, firmware, phone and installed
  versions. Attach a Car Report if you can.
  [Open a mod issue](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/issues/new/choose) ·
  [Export a Car Report](https://github.com/Open-HMG/HMG-API/wiki/Car-Report)

## Hook points this mod is waiting for

| For | Point | Where it stands |
|---|---|---|
| The USB port's power cycle a few seconds into a wireless CarPlay session (issue #372 in HMG-API) | `dipo.service.reset_usb` | [HMG-API #392](https://github.com/Open-HMG/HMG-API/pull/392), open |

## Contributing

Build and test the mod, add hook points, or integrate another mod with the
CarPlay identification API. [Contributor guide](CONTRIBUTING.md)

## Credits

- **[VysseCodes](https://github.com/VysseCodes)**: this mod,
  [HMG-API](https://github.com/Open-HMG/HMG-API) and, before them,
  [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus): the CarPlay Bluetooth
  bridge, the Wi-Fi session and audio patches, and the audio converter.
- **[xwtk](https://github.com/xwtk)**: [NEMESIS](https://github.com/xwtk/hmg_nemesis) and
  its package *Enable Wireless Features* (`wireless.projection`), which the hotspot and
  feature changes build on.
- **[Isaac Wisdom](https://github.com/isaacwisdom)**: the shared CarPlay identity API,
  CarPlay fixes and reviews.
- **Bryan Lieberman, AwsomeFox, SafariForest, lemoyneiv, mantene, hwjang1, Weab-chan,
  Narayan, Joshua Auger, Ian Fisher, DrTaru, Conor McBride** and everyone else in this
  repository's history (`git shortlog -sn`), and the owners who tested builds and sent
  Car Reports.
- **[zpoon](https://github.com/zpoon)** ([Evergreen](https://github.com/zpoon/gen5w-Evergreen))
  for reviews and fixes across Open-HMG.

## License

[AGPL-3.0](LICENSE)
