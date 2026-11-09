# filt3r

An Android VPN app that filters DNS and sends your traffic through a Shadowsocks node.

- **DNS filtering.** 21 block lists (1Hosts, StevenBlack, AdAway, Hagezi, oisd and others, each with its own
  switch), your own allow and block rules, and an optional IP blocklist. Blocked names are answered with a
  local address (127.0.0.1 or ::1).
- **Encrypted DNS.** DNSCrypt, with the queries sent through the tunnel.
- **Shadowsocks tunnel.** Add nodes from `ss://` links or a Clash or plain subscription. The app tests
  them, picks the fastest, switches when the current one dies and retries blacklisted ones later.
- **Failure mode.** Block all traffic when no node works, or connect directly. Your choice.
- **Exempt apps.** Pick apps that bypass the VPN.
- **Local only.** No account, no analytics. The query log is off by default and stays on the phone.

Nothing is preconfigured. You add your own subscription URL and switch on the block lists you want.

## Requirements

Android 16 or newer (API 36), 64-bit ARM. Java, classic Views. The APK is about 3.3 MiB.

## Build

Builds run in GitHub Actions only (`.github/workflows`). CI fetches hev-socks5-tunnel at a pinned tag,
checks every pinned commit (`scripts/fetch-hev.sh`) and compiles it with the NDK. Actions and the Gradle
wrapper are pinned by checksum.

## Licence

Our code is GPL-2.0-or-later, inherited from PersonalDNSFilter. The APK includes Apache-2.0 components
(Material Components, AndroidX), so it is conveyed under GPL-3.0. The full text is in `LICENSE`.

Third-party licences and credits are in `app/src/main/assets/THIRD_PARTY_NOTICES.md`: PersonalDNSFilter
(Ingo Zenz), hev-socks5-tunnel, lwIP, libyaml and Material Components. The app shows both texts under
Settings > About.

## Privacy

filt3r sends no data to its author. It talks to your Shadowsocks nodes, your DNS resolver, and the
subscription and block-list URLs you configure. Block lists are downloaded through your node, so the
servers hosting them see the node's address, not yours. All block lists start switched off.

The node lookup on the Status screen is the one exception to "only your own URLs". When you tap the Node
card, filt3r asks ipwho.is (or ipapi.co if that one fails) where the node's address is. The request goes
through your node, so those services see the node's address, not yours. Nothing is looked up unless you
tap. The optional node history stays on the phone, is off by default and can be cleared in Settings > Privacy.

The app reads the clipboard only when you tap + on the Nodes screen. A settings backup is a plain file you
save yourself; it holds subscription addresses and added nodes only if you tick the box that says so.
