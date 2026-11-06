package com.example.backend.diary;

import com.example.backend.diary.domain.DiaryPoint;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FootprintRendererTests {
    @Test
    void emptyRouteIsValidEmptySvg() throws Exception {
        Document svg = parse(List.of());
        assertThat(svg.getDocumentElement().getTagName()).isEqualTo("svg");
        assertThat(svg.getElementsByTagName("polyline").getLength()).isZero();
        assertThat(svg.getElementsByTagName("circle").getLength()).isZero();
    }

    @Test
    void singleAndIdenticalPointsStayFiniteAndCentered() throws Exception {
        for (List<DiaryPoint> points : List.of(List.of(point(37, 127)),
                List.of(point(37, 127), point(37, 127)),
                List.of(point(0, 0), point(Double.MIN_VALUE, 0)))) {
            String svgText = FootprintRenderer.render(points);
            assertThat(svgText).doesNotContain("NaN", "Infinity");
            Element circle = (Element) parse(points).getElementsByTagName("circle").item(0);
            assertThat(Double.parseDouble(circle.getAttribute("cx"))).isEqualTo(160);
            assertThat(Double.parseDouble(circle.getAttribute("cy"))).isEqualTo(100);
        }
    }

    @Test
    void datelineCrossingKeepsLocalRouteDirectionInsteadOfCrossingWholeWorld() throws Exception {
        Element route = (Element) parse(List.of(point(0, 179), point(0, -179), point(0, -178)))
                .getElementsByTagName("polyline").item(0);
        String[] points = route.getAttribute("points").trim().split(" ");
        double first = Double.parseDouble(points[0].split(",")[0]);
        double middle = Double.parseDouble(points[1].split(",")[0]);
        double last = Double.parseDouble(points[2].split(",")[0]);
        assertThat(first).isLessThan(middle);
        assertThat(middle).isLessThan(last);
        assertThat(first).isGreaterThanOrEqualTo(16);
        assertThat(last).isLessThanOrEqualTo(304);
    }

    @Test
    void northAppearsAboveSouthAndLongRoutePreviewIsBounded() throws Exception {
        List<DiaryPoint> original = IntStream.range(0, 10000)
                .mapToObj(i -> point(37 + i / 100000d, 127 + i / 100000d)).toList();
        Element route = (Element) parse(original).getElementsByTagName("polyline").item(0);
        String[] points = route.getAttribute("points").trim().split(" ");
        assertThat(points).hasSize(1000);
        double firstY = Double.parseDouble(points[0].split(",")[1]);
        double lastY = Double.parseDouble(points[points.length - 1].split(",")[1]);
        assertThat(lastY).isLessThan(firstY);
        assertThat(original).hasSize(10000);
    }

    private static DiaryPoint point(double lat, double lon) {
        return new DiaryPoint(lat, lon, Instant.EPOCH);
    }

    private static Document parse(List<DiaryPoint> points) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(
                FootprintRenderer.render(points).getBytes(StandardCharsets.UTF_8)));
    }
}
