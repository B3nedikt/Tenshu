// Name a `tenshu/file` row hash whose path follows WhatsApp's media naming — no device needed.
//
//     java scripts/DataIdHashSearch.java <hash> "<folder path>"
//     java scripts/DataIdHashSearch.java 2329932176197719662 "/Backup/OnePlus CPH2581/WhatsApp Images"
//
// A store row's key is a 64-bit FNV-1a over `tenshu/file` + `_` + the path (see
// `DataId.getDataIdHash`), which cannot be inverted — but a WhatsApp media name is
// `IMG-YYYYMMDD-WA0000.jpg` (VID-/AUD-/PTT-/DOC-/STK- likewise), so the space under one folder is
// small enough to enumerate: every day since 2010 × 10 000 sequence numbers × a handful of
// extensions is ~6 billion candidates, which this walks in well under a minute with the prefix
// hashed once. This is how `IMG-20240410-WA0004.jpg` was named from the desktop store's dangling
// member on 2026-09-13 (`docs/proposals/drive-folder-files-dangling-member.md`).
//
// For a member that is not WhatsApp-named, `scripts/store-relation-audit.py --match` hashes a
// candidate list (another device's store copy, or a file listing) instead.
//
// Plain single-file Java on purpose: `java scripts/DataIdHashSearch.java …` needs a JDK and
// nothing else — no Gradle, no build.

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;

public class DataIdHashSearch {
    static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    static final long FNV_PRIME = 0x100000001B3L;

    static long feed(long h, byte[] bytes) {
        for (byte b : bytes) h = (h ^ (b & 0xFFL)) * FNV_PRIME;
        return h;
    }

    static long feed(long h, String s) {
        return feed(h, s.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: java scripts/DataIdHashSearch.java <hash> \"<folder path>\"");
            System.exit(2);
        }
        long target = Long.parseLong(args[0]);
        String folder = args[1].endsWith("/") ? args[1].substring(0, args[1].length() - 1) : args[1];

        // Everything up to the file name is hashed once; each candidate only extends it.
        long prefix = feed(feed(feed(FNV_OFFSET_BASIS, "tenshu/file"), "_"), folder + "/");
        String[] kinds = {"IMG-", "VID-", "AUD-", "PTT-", "DOC-", "STK-"};
        String[] exts = {
            ".jpg", ".jpeg", ".mp4", ".webp", ".png", ".gif", ".3gp", ".heic", ".mov",
            ".opus", ".aac", ".m4a", ".pdf", ".JPG", ".JPEG", ".jpg.jpeg",
        };
        byte[][] extBytes = new byte[exts.length][];
        for (int i = 0; i < exts.length; i++) extBytes[i] = exts[i].getBytes(StandardCharsets.UTF_8);

        LocalDate day = LocalDate.of(2010, 1, 1);
        LocalDate end = LocalDate.now().plusDays(1);
        long tried = 0;
        int matches = 0;
        byte[] digits = new byte[4];
        while (!day.isAfter(end)) {
            // Locale.ROOT: on a JVM whose default locale writes non-ASCII digits (ar, fa) a bare
            // format would hash "٢٠٢٤…" against ASCII filenames and never match anything.
            String ymd = String.format(Locale.ROOT, "%04d%02d%02d", day.getYear(), day.getMonthValue(), day.getDayOfMonth());
            for (String kind : kinds) {
                long afterDate = feed(prefix, kind + ymd + "-WA");
                for (int n = 0; n < 10_000; n++) {
                    digits[0] = (byte) ('0' + n / 1000);
                    digits[1] = (byte) ('0' + (n / 100) % 10);
                    digits[2] = (byte) ('0' + (n / 10) % 10);
                    digits[3] = (byte) ('0' + n % 10);
                    long afterSeq = feed(afterDate, digits);
                    for (int e = 0; e < extBytes.length; e++) {
                        tried++;
                        if (feed(afterSeq, extBytes[e]) == target) {
                            matches++;
                            System.out.println("MATCH: " + folder + "/" + kind + ymd + "-WA"
                                + String.format(Locale.ROOT, "%04d", n) + exts[e]);
                        }
                    }
                }
            }
            day = day.plusDays(1);
        }
        System.out.println("tried " + tried + " candidate(s), " + matches + " match(es)");
        System.exit(matches > 0 ? 0 : 1);
    }
}
