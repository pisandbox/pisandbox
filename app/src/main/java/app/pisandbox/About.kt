package app.pisandbox

import android.content.Context

/** Static About / legal information. Texts live in the repo-root legal/ dir, packaged as APK assets. */
object About {
    /** Required verbatim. */
    const val TRADEMARK = "Raspberry Pi is a trademark of Raspberry Pi Ltd. PiSandbox is not affiliated with or endorsed by Raspberry Pi Ltd."

    const val NOTICES = "THIRD_PARTY_NOTICES.txt"

    const val PRIVACY = "PiSandbox collects no data. It has no accounts, no analytics, no ads, no crash reporting " +
        "and no third-party SDKs, and the app itself never connects to the internet. Network traffic only happens " +
        "when you ask for it: pages you open in the Browser tab, and programs you run in Linux (such as apt or pip). " +
        "If a crash happens, the details stay on your phone; you choose whether to copy and share them."

    data class Component(val name: String, val what: String, val license: String, val file: String)

    val COMPONENTS = listOf(
        Component("PiSandbox", "this app", "GPL-3.0-or-later", "licenses/GPL-3.0.txt"),
        Component("PRoot 5.1.107", "runs Linux without root (separate program)", "GPL-2.0-or-later", "licenses/GPL-2.0.txt"),
        Component("talloc 2.5.0", "memory library used by PRoot", "LGPL-3.0-or-later", "licenses/LGPL-3.0.txt"),
        Component("libandroid-shmem 0.7", "shared memory for PRoot", "BSD-3-Clause", "licenses/libandroid-shmem-BSD-3-Clause.txt"),
        Component("Debian 12 packages", "the Linux system; see /usr/share/doc/*/copyright", "various free licenses", NOTICES),
        Component("gpiozero 2.0.1", "GPIO library in Linux", "BSD-3-Clause", "licenses/gpiozero-BSD-3-Clause.txt"),
        Component("colorzero 2.0", "colour library in Linux", "BSD-3-Clause", "licenses/colorzero-BSD-3-Clause.txt"),
        Component("Termux terminal-emulator / terminal-view 0.118.3", "the terminal", "Apache-2.0", "licenses/Apache-2.0.txt"),
        Component("Apache Commons Compress, IO, Lang, Codec", "unpacking Linux", "Apache-2.0", "licenses/Apache-2.0.txt"),
        Component("XZ for Java 1.10", "unpacking Linux", "0BSD", "licenses/0BSD-xz-java.txt"),
        Component("AndroidX Annotation, Kotlin standard library", "app runtime", "Apache-2.0", "licenses/Apache-2.0.txt"),
    )

    fun sourceOffer(projectUrl: String = BuildConfig.PROJECT_URL) =
        "PiSandbox is free software (GPL-3.0-or-later). Source code: $projectUrl\n\n" +
        "Written offer: for at least three years from the date you received this copy, anyone who has it can get " +
        "the complete corresponding source code of PiSandbox and of the GPL/LGPL parts it contains (PRoot, talloc and " +
        "the Debian packages) on a common data medium, for no more than the cost of making and sending the copy. " +
        "The same source is also free to download from the project page and from the upstream projects listed in the " +
        "third-party notices. To ask for it, open an issue on the project page, quoting version ${BuildConfig.VERSION_NAME}."

    fun header(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE) =
        "PiSandbox $versionName (build $versionCode)\nDebian 12 (bookworm) arm64 in PRoot. Free and open source."

    /** Reads a bundled legal text; never throws (a missing asset is reported in the text itself). */
    fun read(ctx: Context, asset: String): String = try {
        ctx.assets.open(asset).bufferedReader().use { it.readText() }
    } catch (e: Exception) { "($asset is missing from this build: ${e.message})" }
}
