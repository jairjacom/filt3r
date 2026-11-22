# Changelog

## Unreleased

- Other apps can no longer switch filt3r on or off by sending it a Connect or Disconnect request. Only the quick-settings tile, the widget and the launcher shortcuts can.

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
