# Changelog

## Unreleased (planned v1.0.0)

- Per-app firewall (Settings > Firewall, formerly Apps). Every app has three boxes: Wi-Fi, Mobile and Locked. Tick one to block the app there; the boxes combine, so you can block an app on Wi-Fi and also while the phone is locked. "Wi-Fi" means a network that is not metered and "Mobile" means a metered one, as in RethinkDNS. A blocked app's connections are refused and its DNS questions get a refusal, so it does not even look up names. The connection log shows why: "App rule" (Wi-Fi or Mobile), "Device locked" ("Firewall error" if the check itself failed). Changes apply at once, with no restart, and connections that are already open and now refused are closed (also shortly after the screen turns off). An app on the Exempt list skips filt3r completely, so no rule can reach it; ticking the first box on a system app asks first. An app Android cannot name is never stopped. The old "Universal rules" switches are gone; rules saved by the first firewall (block Wi-Fi, mobile or both) carry over, but a "block when locked" or "block on metered" switch you turned on is dropped. Not yet: blocking an app only while it is in the background (that needs an accessibility service), a re-check when you switch between Wi-Fi and mobile data, and rules are not part of settings backups.
- New "Firewall" screen (Settings > Firewall): every installed app A to Z with its three boxes and what the connection log says about it, for example "12 allowed, 3 blocked, last 11-26 14:02". System apps are hidden until you switch on "Show system apps" (an app that is a system app only because the phone maker pre-installed it, and that you can update, counts as a normal app). Search matches the name and the package name. Tap an app to open the log's Connections side showing only that app; tap the line above the list to show everything again; the "Connection log" button opens the log for every app. The counts cover only the connections the log still holds (the last 1,000), and nothing is counted while "Keep a log" is off. A few system components share one user ID; they change together, and each of them names the others that share it.
- Fixed "Show system apps" on the Exempt apps screen, which showed no system apps: it listed only apps with a launcher icon, and most system apps (AI Core, System Intelligence, Play services) have none. It now lists every installed app, like the Apps screen, with system apps hidden until you switch the option on. App names are shortened and cleaned the same way as in the log.
- New "Connections" side in the log screen (next to "DNS"). With "Keep a log" on, it lists each TCP connection by app: where it went (the name that app looked up, or the IP), the port, how often it repeated (repeats within 30 seconds join one line), and whether it was allowed or blocked. A refusal shows its reason: IP list, IPv6 or No node. Tap a line for the app's package name. A connection whose app Android cannot name shows as "Unknown app". Search matches the app, the address and the port. The switch is now called "Keep a log" and keeps up to 1,000 DNS queries and 1,000 connections; Settings > Privacy > Clear logs clears both, the bin on the log screen clears the side you are looking at. Saving the log as a text file works on either side.
- Apps are now remembered by package name. Lines of an app that is no longer installed read "(not installed)", and the bin has "Entries of uninstalled apps" to remove them after a confirmation. This needs the Android permission `QUERY_ALL_PACKAGES` (see Privacy in the README); Android hides some apps even so, and those are never marked.
- The DNS query log now shows which app asked, next to the time ("Blocked · 12:03 · Chrome"), and the search box also matches the app name. System traffic shows as "System (name)"; an app Android does not let filt3r name shows as "App <number>". About 1 query in 8 from a browser shows no app, because the browser had already closed its socket when filt3r looked. The app name is only looked up while the log is on. Saving the log as a text file now also writes the app name and package name after each domain (separated by tabs), so a saved file shows which apps you use. Needs a small patch to the bundled hev-socks5-tunnel (about 18 lines, in scripts/hev-app-owner.patch).
- Fixed restoring a settings backup: it was refused ("a value that is not allowed (enabled_lists)", nothing changed) whenever a block list such as ad&malware, HageziPro++ or Dan Pollock's Hosts was switched on. After a restore the message now also says how many subscriptions and nodes came back, and that a subscription's nodes download the next time you connect.
- DNS-over-HTTPS (DoH) resolvers. Settings > DNS > Resolver now lists DoH resolvers from the public list next to DNSCrypt ones (tagged "DoH"), and a custom sdns:// stamp may be a DoH stamp (sdns://Ag...). DNSCrypt stays the default. Expect DoH lookups to be slower, because each query opens its own secure connection through the node.
- If the first connect fails because the resolver for the first subscription download cannot be reached, a message says so with a Retry button.
- New switch in Settings > Connection, "Allow automation apps" (off by default). When on, a macro app such as Tasker can connect and disconnect filt3r by sending the action com.bridge.filt3r.action.AUTOMATION_CONNECT or AUTOMATION_DISCONNECT with the "Automation key" shown in Settings (tap to copy). The switch and the key are not part of settings backups.

## v0.0.14

- Share a node link (ss://) or a subscription address (https://) to filt3r from a chat or browser. It asks before adding anything, and shows the host (and port). A subscription goes in the first free slot and its nodes load at the next connect or restart.
- You can add block lists and IP lists from files on your phone: Settings > DNS has "Add list from file", and Block lists has "Add IP list from file". The files are copied into filt3r, up to 8 of each, and load after a restart. Tap a list to remove it.
- The IP blocklist now understands ranges such as 203.0.113.0/24 (and IPv6 ranges). Ranges wider than /8 (IPv4) or /16 (IPv6) are ignored, so a typo cannot block everything.
- Connecting no longer fails when you have nodes of your own and have just added a subscription: the new subscription is downloaded at the start.
- The README now explains how to install, update with Obtainium and check the signing certificate.

## v0.0.13

- My rules can now answer a name with a fixed IP address and accepts * wildcards such as *.example.com. Both are included in the settings backup, and answered names show in the query log.
- Subscriptions in the standard Shadowsocks SIP008 format now work.

## v0.0.12

- Other apps can no longer switch filt3r on or off by sending it a Connect or Disconnect request. Only the quick-settings tile, the widget and the launcher shortcuts can. A shortcut you pinned before this update may need a second tap the first time.
- Traffic to your local network (router, printer, other devices at home) now stays out of the tunnel instead of going to a node. A new switch in Settings, "Keep local network out of the tunnel", is on by default; changing it applies after a restart.
- Names that only exist on a local network, such as router.lan, now fail at once and are no longer sent to the public resolver. With the query log on, they appear in it.
- Self-check and Copy diagnostics now count how often apps sent DNS straight to their own servers, around the filter. Nothing is blocked. Self-check also tells you when Private DNS is set to a host name.

## v0.0.11

- Internal cleanup only; nothing changes in how filt3r works.
- The diagnostic log now says why each node failure is counted and what the check on a failing node found, so an unexpected node switch can be traced to its cause.

## v0.0.10

- Every node is now tested after you connect. A full scan runs in the background, 75 nodes every 5 minutes, and finishes in about 15 minutes. It repeats every 24 hours while connected. Before, only a few untested nodes were tried per hour, so fast nodes could stay unknown.
- Nodes that answer in under 800 ms are your fast list. They are ranked first, saved for the next connect and re-tested in every round. Slower nodes remain as backup.
- When a scan ends and your current node answers in 800 ms or more, filt3r moves to a clearly faster node (never from a pinned node).
- The Nodes screen shows the scan progress, tags each node "tested 12 min ago", "not tested" or "no answer", and hides slow and dead nodes behind a button. Your current, pinned and added nodes always show.
- The "Test only on Wi-Fi or while charging" switch now also pauses the full scan. Every node owner sees your IP about once a day because of the scan.

## v0.0.9

- Node blacklisting is now written to the diagnostic log, with the reason and whether it was your current or pinned node, so an unexpected node switch can be traced.

## v0.0.8

- Faster, safer node switching. When your node stops working, filt3r now checks the best other nodes at once and moves to one that answered a moment ago, instead of one that was fast hours ago.
- Nodes are ranked by speed, how recently they were tested and whether they failed lately. The Nodes list uses this order; the number shown is still the median time.
- Connecting is quicker: filt3r starts as soon as the first node answers, and a short check after connecting moves to a clearly faster node.
- The best nodes are saved on your phone and used at the next connect. Clear node history, or turning Node history off, deletes them.
- Nodes are re-tested about 10 seconds after you switch between Wi-Fi and mobile data. The hourly test also tries the nodes tested longest ago.
- No network, for example airplane mode, no longer counts against your nodes: none are blocked and a pinned node stays pinned.
- New setting, off by default: Move to a much faster node. After the hourly test it switches to a node at least twice as fast and 100 ms quicker, and never leaves a pinned node. Open connections restart and your exit IP changes.

## v0.0.7

- Update subscriptions on demand: an Update now button under each subscription in Settings > Connection, and a cloud button on Nodes. You need to be connected, because the download goes through a node.
- The 10 best nodes are now re-tested every hour and speed-tested when you tap re-test (it was 5). A re-test can take a little longer.

## v0.0.6

- Pause filtering for 5, 15 or 60 minutes from Status, the notification or the Quick Settings tile. The tunnel stays up and filtering comes back on its own.
- Status cards are tappable: look up where your node is, see a bigger traffic graph, and open the most blocked names. Optional node history stays on your phone and is off by default.
- Add your own node by copying an ss:// link and tapping + on Nodes. Your nodes show first, under a Mine filter, and you can copy a node's link or remove your own.
- Settings backup and restore through the file picker. Subscription addresses and added nodes are left out unless you tick the box.
- Self-check under Settings > About lists what may stop filt3r from working, and Copy diagnostics gives you a report to share.
- Test nodes only on Wi-Fi or while charging, if you want to save mobile data.
- Nodes: search, hide a node, and a notice when another node takes over.
- Query log: a Top view of the most blocked names, a menu when you hold a line, and clearer buttons.
- Subscriptions are checked when you enter the address and tell you how the update went. Restarting returns you to Status, and a warning appears before you restart.
- Settings has an Always-on VPN row. The toolbar shadow and the pill icons were polished.

## v0.0.5

- Redesigned look: the status circle changes shape as the connection changes, and screens move with springy motion. New visual effects can be switched on or off in Settings.
- Settings has a home page with sub-pages. Choices are confirmed before they apply, URL fields save when you leave them, and the colour picker is easier to use.
- Nodes: tap and hold a node for its details, re-test one node on its own, and see a progress bar while testing. The Nodes icon shows how many nodes work.
- The Settings and Exempt apps icons show a dot while a restart is waiting, and a banner reminds you to restart.
- Query log: the filter buttons use the app colour, and each line reads out clearly with a screen reader.
- The home-screen widget shows an icon for each state and has a preview in the widget picker. The Connect and Disconnect shortcuts follow the connection, and the notification now says what filt3r is doing.
- Dialogs blur the screen behind them.
- Better support for screen readers and the on-screen keyboard.
- All text now lives in language files, which makes translations possible later.

## v0.0.4

- New Visual effects setting (Minimal, Standard, Full or Custom) and a choice of Slide, Fade or Pop for how screens open and close.
- The status circle fades between states and bounces when you connect. Full adds a soft glow and an animated background.
- Quick Settings tile, launcher shortcuts and a home-screen widget to connect and disconnect.
- Traffic graph and a counting Blocked number on Status, and a thin speed bar on each node.
- Block lists have their own page in Settings.
- The back gesture shows a preview, messages appear as a small bar, and Settings > About keeps the last crash report on your device.

## v0.0.3

- Query log can be saved as a text file. You choose whether to save blocked lines, allowed lines or both.
- The node in use is listed first. Each node is tagged in use, dead, no answer or not tested.
- The current node shows its IP address and when the last test round finished.
- Each subscription has a Use switch. Settings shows when each subscription was last updated.

## v0.0.2

- First signed release, built and published by the tag-triggered release workflow.
