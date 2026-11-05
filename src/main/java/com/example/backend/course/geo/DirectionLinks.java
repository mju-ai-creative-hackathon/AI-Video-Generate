package com.example.backend.course.geo;

import com.example.backend.course.domain.TravelMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 네이버 지도 앱(nmap://) / 구글 지도 웹 길찾기 딥링크. */
@Component
public class DirectionLinks {
    private final String naverAppName;

    public DirectionLinks(@Value("${app.course.naver-appname:${NAVER_MAP_APPNAME:com.mju.hackathon}}") String naverAppName) {
        this.naverAppName = naverAppName;
    }

    public String naverUrl(GeoPoint from, String fromName, GeoPoint to, String toName, TravelMode mode) {
        String type = mode == TravelMode.WALK ? "walk" : "public";
        return "nmap://route/" + type
                + "?slat=" + coord(from.lat()) + "&slng=" + coord(from.lng()) + "&sname=" + encode(fromName)
                + "&dlat=" + coord(to.lat()) + "&dlng=" + coord(to.lng()) + "&dname=" + encode(toName)
                + "&appname=" + encode(naverAppName);
    }

    public String googleUrl(GeoPoint from, GeoPoint to, TravelMode mode) {
        String travelMode = mode == TravelMode.WALK ? "walking" : "transit";
        return "https://www.google.com/maps/dir/?api=1"
                + "&origin=" + coord(from.lat()) + "," + coord(from.lng())
                + "&destination=" + coord(to.lat()) + "," + coord(to.lng())
                + "&travelmode=" + travelMode;
    }

    static String coord(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
