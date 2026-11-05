package com.example.backend.diary;

import com.example.backend.diary.domain.DiaryPoint;

import java.util.List;
import java.util.Locale;

/** A private, map-independent preview of the recorded route. */
public final class FootprintRenderer {
    private static final int MAX_PREVIEW_POINTS = 1000;
    private static final double WIDTH = 320;
    private static final double HEIGHT = 200;
    private static final double PADDING = 16;

    private FootprintRenderer() {
    }

    public static String render(List<DiaryPoint> points) {
        String opening = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 320 200\" role=\"img\" aria-label=\"Recorded walking route\">";
        if (points.isEmpty()) {
            return opening + "</svg>";
        }

        int count = points.size();
        double[] x = new double[count];
        double[] y = new double[count];
        double longitude = points.getFirst().getLongitude();
        double longitudeScale = Math.max(0.000001,
                Math.cos(Math.toRadians(points.getFirst().getLatitude())));
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            DiaryPoint point = points.get(i);
            if (i > 0) {
                longitude += Math.IEEEremainder(point.getLongitude()
                        - points.get(i - 1).getLongitude(), 360);
            }
            x[i] = longitude * longitudeScale;
            y[i] = -point.getLatitude();
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }

        double spanX = maxX - minX;
        double spanY = maxY - minY;
        double scale = Math.min((WIDTH - 2 * PADDING) / Math.max(spanX, 1e-9),
                (HEIGHT - 2 * PADDING) / Math.max(spanY, 1e-9));
        double offsetX = (WIDTH - spanX * scale) / 2;
        double offsetY = (HEIGHT - spanY * scale) / 2;
        for (int i = 0; i < count; i++) {
            x[i] = offsetX + (x[i] - minX) * scale;
            y[i] = offsetY + (y[i] - minY) * scale;
        }

        StringBuilder svg = new StringBuilder(opening);
        if (count > 1) {
            svg.append("<polyline fill=\"none\" stroke=\"#B93636\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" points=\"");
            int samples = Math.min(count, MAX_PREVIEW_POINTS);
            for (int i = 0; i < samples; i++) {
                int index = (int) ((long) i * (count - 1) / (samples - 1));
                svg.append(number(x[index])).append(',').append(number(y[index])).append(' ');
            }
            svg.append("\"/>");
        }
        svg.append(circle(x[0], y[0], "#258457"));
        if (count > 1) {
            svg.append(circle(x[count - 1], y[count - 1], "#B93636"));
        }
        return svg.append("</svg>").toString();
    }

    private static String circle(double x, double y, String color) {
        return "<circle cx=\"" + number(x) + "\" cy=\"" + number(y)
                + "\" r=\"6\" fill=\"" + color + "\" stroke=\"white\" stroke-width=\"2\"/>";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
