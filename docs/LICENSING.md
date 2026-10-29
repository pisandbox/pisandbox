# Why GPL-3.0-or-later

PiSandbox is licensed **GPL-3.0-or-later**. The realistic alternative was **Apache-2.0**. Reasons for GPL:

1. **It matches what we ship.** The APK already contains GPL-2.0-or-later (PRoot), LGPL-3.0-or-later (talloc) and
   many GPL Debian packages, so every distribution of the APK already carries GPL source obligations (the written
   offer in the app). Apache-2.0 for our own code would not remove any of that work — it would only allow others
   to make closed copies of the PiSandbox parts.
2. **Compatibility.** Apache-2.0 code (Termux terminal libraries, Apache Commons, AndroidX, Kotlin) can be included in
   a GPL-3.0 work; GPL-3.0 is compatible with Apache-2.0 in that direction (GPL-2.0-only would not be). PRoot runs
   as a separate program, and "or later" keeps the door open to future GPL versions. BSD/0BSD parts are compatible.
3. **Learners and teachers get the source.** It is an educational tool; anyone who redistributes a modified build
   (for example a school or a store copy) must share their changes too.
4. **Store distribution is still possible.** GPL apps are on Google Play and F-Droid; the source offer and the
   About screen cover the obligations.

**When Apache-2.0 would be the better choice:** if you want companies to embed PiSandbox's Kotlin code in
proprietary apps, or want a patent grant without copyleft. Relicensing later needs agreement of all contributors,
so decide before accepting outside contributions (or use a contributor licence agreement / DCO policy).

Our own files carry no per-file headers yet; the repository-level LICENSE and NOTICE apply. Debian packages keep
their own licences; see `/usr/share/doc/*/copyright` inside Linux and THIRD_PARTY.md.
