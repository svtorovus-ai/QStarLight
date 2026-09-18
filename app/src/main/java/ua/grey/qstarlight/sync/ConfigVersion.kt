package ua.grey.qstarlight.sync

/** The origin breaks ties when two devices edit within the same millisecond. */
data class ConfigVersion(val revision: Long, val origin: String = "") : Comparable<ConfigVersion> {
    override fun compareTo(other: ConfigVersion): Int =
        compareValuesBy(this, other, ConfigVersion::revision, ConfigVersion::origin)

    fun acknowledges(pending: ConfigVersion?, current: ConfigVersion, accepted: Boolean): Boolean =
        accepted && this == pending && this == current
}
