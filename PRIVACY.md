# PiSandbox privacy policy

_Effective 29 October 2026 (applies to version 0.5.0 and later)._

**PiSandbox collects no data.** We (the PiSandbox developers) receive nothing from the app: no personal
information, no usage statistics, no device identifiers, no crash reports, no location.

* **No accounts, no analytics, no ads, no crash reporting, no third-party SDKs.**
* **The app itself never connects to the internet.** It has no servers to talk to and no update checks.
  Network traffic happens only when *you* cause it:
  * pages you open in the Browser tab (typed addresses, bookmarks, and searches, which go to DuckDuckGo);
  * programs you run inside Linux, such as `apt`, `pip`, `git` or `curl`. They connect to the servers you or
    they choose (for example deb.debian.org), exactly as on a computer.
  * Linux uses your phone's DNS servers; if Android does not report any, it falls back to 1.1.1.1 and 8.8.8.8.
    This is only used by programs you run.
* **Browser:** Android System WebView's usage-metrics upload is turned off for this app, and Safe Browsing is
  turned off (it would send URL hash prefixes to Google). Websites you visit can see your IP address and set
  cookies, as in any browser; cookies stay on the phone.
* **Crashes:** if the app crashes it shows the details on your screen. They are stored only on your phone. You
  decide whether to copy them and send them to anyone.
* **Your files:** everything in Linux (your home folder, installed packages) is stored in the app's private
  storage on your phone. It is deleted when you uninstall the app. Android's backup is not used for it.
* **Permissions:** Internet and network state (for the browser and your Linux programs), and "foreground
  service" (to keep Linux running with a notification). No contacts, camera, microphone, location or storage
  permissions.
* **Children:** PiSandbox is suitable for learners; since it collects nothing, there is nothing to share.

Questions: open an issue at https://github.com/pisandbox/pisandbox/issues (the project page is also listed in the app under
Settings → About & licenses).
Changes to this policy will be listed in CHANGELOG.md and dated here.
