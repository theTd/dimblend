package dimblend.experience.tuning.perf;

/** Which process a result describes: the (possibly integrated) server, or this game client. */
public enum PerfScope {
    SERVER,
    CLIENT;

    public String key() { return PerfCheck.PREFIX + "scope." + name().toLowerCase(java.util.Locale.ROOT); }
}
