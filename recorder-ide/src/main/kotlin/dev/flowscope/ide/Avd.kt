package dev.flowscope.ide

/** An installed Android Virtual Device as the device picker shows it. */
internal data class AvdEntry(val name: String, val status: AvdStatus, val owned: Boolean)

internal sealed interface AvdStatus {
    data object Stopped : AvdStatus

    /** Launched from here and not yet reporting `sys.boot_completed`. */
    data object Booting : AvdStatus

    data class Running(val serial: String) : AvdStatus
}

/**
 * Merges the installed AVD names with what ADB sees running. [running] maps serial to AVD name;
 * [booting] wins over [running] because an emulator is listed by ADB well before Android is up.
 * [owned] marks the AVDs FlowScope launched, the only ones the picker offers to stop.
 */
internal fun mergeAvds(
    names: List<String>,
    running: Map<String, String>,
    booting: Set<String>,
    owned: Set<String>,
): List<AvdEntry> {
    val serialByAvd = running.entries.associate { (serial, avd) -> avd to serial }
    return names.map { name ->
        val status = when {
            name in booting -> AvdStatus.Booting
            name in serialByAvd -> AvdStatus.Running(serialByAvd.getValue(name))
            else -> AvdStatus.Stopped
        }
        AvdEntry(name, status, owned = name in owned)
    }
}
