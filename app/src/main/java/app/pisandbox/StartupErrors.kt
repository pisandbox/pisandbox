package app.pisandbox

/** Turns a startup failure into words a beginner can act on (shown on the "Linux couldn't start" panel). */
object StartupErrors {
    data class Friendly(val title: String, val message: String)

    fun describe(e: Throwable): Friendly = when {
        e is NotEnoughStorageException || RootfsInstaller.isOutOfSpace(e) -> {
            val need = (e as? NotEnoughStorageException)?.neededBytes ?: RootfsInstaller.NEEDED_BYTES
            val free = (e as? NotEnoughStorageException)?.freeBytes
            Friendly("Not enough storage",
                "PiSandbox needs about ${mb(need)} free to set up Linux" +
                    (if (free != null && free >= 0) " (${mb(free)} free now)" else "") +
                    ". Free up some space (photos, videos, unused apps), then tap Try again.")
        }
        e.message.orEmpty().contains("libproot.so missing") -> Friendly("This copy of PiSandbox is incomplete",
            "The Linux engine is missing from this APK. Please reinstall PiSandbox from the tester link.")
        e is java.io.FileNotFoundException && e.message.orEmpty().contains("rootfs/") -> Friendly("This copy of PiSandbox is incomplete",
            "The Debian files are missing from this APK. Please reinstall PiSandbox from the tester link.")
        else -> Friendly("Linux couldn't start",
            "Something went wrong while starting Linux (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(160) } ?: ""}). " +
                "Tap Try again. If it keeps happening, tap Copy details and paste them into your feedback.")
    }

    fun earlyExit(code: Int) = Friendly("Linux stopped right after starting",
        "The Linux shell exited straight away (exit code $code). Tap Try again. If it keeps happening, tap Copy details and paste them into your feedback.")

    private fun mb(b: Long) = if (b >= 1024L * 1024 * 1024) String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0) else "${b / (1024 * 1024)} MB"
}
