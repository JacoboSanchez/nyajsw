package io.jsl.nyajsw.config;

import java.util.List;

/**
 * What a {@link ConfigSource} read: its entries in source order (includes expanded in place) and the
 * warnings raised while reading. Duplicate keys are kept; deciding which one wins is the job of the
 * layer that merges sources.
 *
 * @param entries  entries in the order they appear, unmodifiable
 * @param warnings warnings in the order they were raised, unmodifiable
 */
public record SourceContent(List<ConfigEntry> entries, List<ConfigWarning> warnings) {

    public SourceContent {
        entries = List.copyOf(entries);
        warnings = List.copyOf(warnings);
    }
}
