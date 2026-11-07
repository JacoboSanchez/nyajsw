package io.github.jacobosanchez.nyajsw.config;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * The names of the log file and its rolled copies, from {@code wrapper.logfile} and the tokens YAJSW
 * documents for its file name (the last element of the path; the directories are taken as written):
 *
 * <ul>
 *   <li>{@code YYYYMMDD} is replaced by the date, {@code yyyyMMdd};
 *   <li>{@code ROLLNUM}, at most once, is replaced by the number of a rolled copy; in the name of the
 *       current file it is left out together with one {@code .}, {@code _} or {@code -} before it
 *       (after it, when the name starts with the token), so that {@code wrapper.ROLLNUM.log} is
 *       written as {@code wrapper.log} and rolls to {@code wrapper.1.log};
 *   <li>without {@code ROLLNUM}, a rolled copy has its number appended: {@code wrapper.log.1}.
 * </ul>
 *
 * <p>Copy 1 is the newest; older copies have higher numbers, as YAJSW documents. The rules live in
 * the configuration layer so that a name they cannot work with is a problem of the key.
 */
public final class LogFileNames {

    static final String ROLLNUM = "ROLLNUM";
    static final String DATE = "YYYYMMDD";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final String SEPARATORS = "._-";

    private final Path directory;
    private final String pattern;

    /**
     * @throws IllegalArgumentException when {@code file} has no file name, names {@code ROLLNUM} more
     *                                  than once, or would leave the current file without a name
     */
    public LogFileNames(Path file) {
        Objects.requireNonNull(file, "file");
        Path name = file.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("not a file: " + file);
        }
        this.directory = file.getParent();
        this.pattern = name.toString();
        if (pattern.indexOf(ROLLNUM) != pattern.lastIndexOf(ROLLNUM)) {
            throw new IllegalArgumentException("the file name has " + ROLLNUM + " more than once");
        }
        if (currentName(LocalDate.EPOCH).isEmpty()) {
            throw new IllegalArgumentException("the file name is only " + ROLLNUM);
        }
    }

    /** The file written to on {@code date}. */
    public Path current(LocalDate date) {
        return resolve(currentName(date));
    }

    /** Rolled copy {@code number} (1 the newest) of the file written to on {@code date}. */
    public Path rolled(LocalDate date, int number) {
        if (number < 1) {
            throw new IllegalArgumentException("rolled copies are numbered from 1: " + number);
        }
        return pattern.contains(ROLLNUM) ? resolve(dated(date).replace(ROLLNUM, Integer.toString(number)))
                : resolve(currentName(date) + "." + number);
    }

    private String currentName(LocalDate date) {
        String name = dated(date);
        int token = name.indexOf(ROLLNUM);
        if (token >= 0) {
            int start = token;
            int end = token + ROLLNUM.length();
            if (start > 0 && SEPARATORS.indexOf(name.charAt(start - 1)) >= 0) {
                start--;
            } else if (start == 0 && end < name.length() && SEPARATORS.indexOf(name.charAt(end)) >= 0) {
                end++;
            }
            name = name.substring(0, start) + name.substring(end);
        }
        return name;
    }

    private String dated(LocalDate date) {
        return pattern.replace(DATE, DATE_FORMAT.format(date));
    }

    private Path resolve(String name) {
        return directory == null ? Path.of(name) : directory.resolve(name);
    }
}
