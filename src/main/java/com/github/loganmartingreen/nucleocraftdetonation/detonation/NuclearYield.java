package com.github.loganmartingreen.nucleocraftdetonation.detonation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stores nuclear yield in kilotonnes of TNT equivalent.
 * <p>
 * Design rule for NucleoCraft: Detonation:
 * 1 Minecraft TNT block is treated as exactly 1 tonne of TNT equivalent.
 */
public record NuclearYield(double kilotonnes) {
    public static final double MIN_KILOTONNES = 1.0D;
    public static final double MAX_KILOTONNES = 20_000.0D;

    private static final Pattern INPUT_PATTERN = Pattern.compile(
        "^([0-9]+(?:\\.[0-9]+)?)(t|kt|mt)$",
        Pattern.CASE_INSENSITIVE
    );

    public NuclearYield {
        if (!Double.isFinite(kilotonnes)) {
            throw new IllegalArgumentException("Yield must be a finite number.");
        }
        if (kilotonnes < MIN_KILOTONNES || kilotonnes > MAX_KILOTONNES) {
            throw new IllegalArgumentException("Yield must be between 1kt and 20mt.");
        }
    }

    public static NuclearYield parse(String rawInput) {
        String input = rawInput.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = INPUT_PATTERN.matcher(input);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid yield. Examples: 1kt, 10kt, 37.5kt, 1mt, 20mt."
            );
        }

        double kilotonnes = getKilotonnes(matcher);

        return new NuclearYield(kilotonnes);
    }

    private static double getKilotonnes(Matcher matcher) {
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid yield number.", exception);
        }

        double kilotonnes = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "t" -> value / 1_000.0D;
            case "kt" -> value;
            case "mt" -> value * 1_000.0D;
            default -> throw new IllegalArgumentException("Unsupported yield unit.");
        };
        return kilotonnes;
    }

    public String displayName() {
        if (kilotonnes >= 1_000.0D) {
            return trim(kilotonnes / 1_000.0D) + "mt";
        }
        return trim(kilotonnes) + "kt";
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.3f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
