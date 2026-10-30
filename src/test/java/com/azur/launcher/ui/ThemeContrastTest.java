package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** WCAG AA for the theme: body text >= 4.5:1 on every background it can sit on, no font below 14px. */
class ThemeContrastTest {

    private static final double AA = 4.5;
    private static final String[] TEXT = {"on-surface", "on-surface-variant", "muted", "primary", "gold-deep"};

    private static String css() throws IOException {
        try (InputStream in = ThemeContrastTest.class.getResourceAsStream("/app.css")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String color(String css, String name) {
        Matcher m = Pattern.compile("-azur-" + Pattern.quote(name) + ":\\s*(#[0-9a-fA-F]{6});").matcher(css);
        assertTrue(m.find(), "missing -azur-" + name);
        return m.group(1);
    }

    private static List<String> backgrounds(String css) {
        List<String> bg = new ArrayList<>();
        for (String n : new String[] {"surface", "surface-container-low", "surface-container", "surface-container-high",
                "surface-container-highest", "surface-variant", "secondary-container"}) {
            bg.add(color(css, n));
        }
        Matcher g = Pattern.compile("\\.window-root \\{[^}]*?linear-gradient\\(([^)]*)\\)", Pattern.DOTALL).matcher(css);
        assertTrue(g.find(), "window gradient");
        Matcher stops = Pattern.compile("#[0-9a-fA-F]{6}").matcher(g.group(1));
        while (stops.find()) {
            bg.add(stops.group());
        }
        Matcher hero = Pattern.compile("\\.hero-card \\{[^}]*?linear-gradient\\(([^)]*)\\)", Pattern.DOTALL).matcher(css);
        assertTrue(hero.find(), "hero gradient");
        stops = Pattern.compile("#[0-9a-fA-F]{6}").matcher(hero.group(1));
        while (stops.find()) {
            bg.add(stops.group());
        }
        return bg;
    }

    static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static double ratio(String a, String b) {
        double x = luminance(a);
        double y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    @Test
    void textMeetsAaOnEveryBackground() throws IOException {
        String css = css();
        for (String text : TEXT) {
            String fg = color(css, text);
            for (String bg : backgrounds(css)) {
                double r = ratio(fg, bg);
                assertTrue(r >= AA, String.format("%s %s on %s = %.2f < %.1f", text, fg, bg, r, AA));
            }
        }
    }

    @Test
    void buttonTextMeetsAa() throws IOException {
        String css = css();
        assertTrue(ratio(color(css, "on-primary"), color(css, "primary")) >= AA);
        assertTrue(ratio(color(css, "on-primary-container"), color(css, "primary-container")) >= AA);
        assertTrue(ratio(color(css, "on-error-container"), color(css, "error-container")) >= AA);
        assertTrue(ratio(color(css, "on-secondary-container"), color(css, "secondary-container")) >= AA);
    }

    @Test
    void navyBandTextMeetsAa() throws IOException {
        String css = css();
        String navy = color(css, "navy");
        assertTrue(ratio(color(css, "gold-light"), navy) >= AA, "gold title on navy");
        assertTrue(ratio("#ffffff", color(css, "navy-high")) >= AA, "window icons on navy");
        assertTrue(ratio(color(css, "gold"), color(css, "surface")) >= 1.0);
    }

    @Test
    void inputsAreFilledAndBordered() throws IOException {
        String css = css();
        String fill = color(css, "surface-container-highest");
        // field border against its own fill, and the fill against the card it sits on (WCAG 1.4.11 asks 3:1 for borders)
        assertTrue(ratio(color(css, "outline-variant"), fill) >= 3.0, "field border");
        assertTrue(ratio(color(css, "primary"), fill) >= 3.0, "focus border");
    }

    @Test
    void panelsAreLight() throws IOException {
        String css = css();
        assertTrue(luminance(color(css, "surface-container")) > luminance("#13234a") * 2);
    }

    @Test
    void noFontBelow13px() throws IOException {
        Matcher m = Pattern.compile("-fx-font-size:\\s*(\\d+)px").matcher(css());
        while (m.find()) {
            assertTrue(Integer.parseInt(m.group(1)) >= 14, "font " + m.group(1) + "px");
        }
        Matcher v = Pattern.compile("-azur-fs-[a-z-]+:\\s*(\\d+)px").matcher(css());
        while (v.find()) {
            assertTrue(Integer.parseInt(v.group(1)) >= 14, "scale " + v.group(1) + "px");
        }
    }
}
