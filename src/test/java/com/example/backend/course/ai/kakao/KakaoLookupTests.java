package com.example.backend.course.ai.kakao;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class KakaoLookupTests {
    private static final String BASE = "https://dapi.test";
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static final String PLACES = """
            {"meta":{"total_count":3},"documents":[
              {"id":"1","place_name":"을지로 노가리골목","address_name":"부산 중구 동광동","road_address_name":"부산 중구 대청로",
               "x":"129.03","y":"35.10","category_name":"음식점","place_url":"http://place.map.kakao.com/1"},
              {"id":"2","place_name":"을지로3가 맛집","address_name":"서울 중구 을지로3가 1","road_address_name":"서울 중구 을지로 100",
               "x":"126.9918","y":"37.5663","category_name":"음식점 > 한식","place_url":"http://place.map.kakao.com/2"},
              {"id":"3","place_name":"노가리 골목","address_name":"서울 중구 을지로3가 95","road_address_name":"",
               "x":"126.9925","y":"37.5660","category_name":"관광명소","place_url":"http://place.map.kakao.com/3"}
            ]}""";

    @Test
    void placeParsesSeoulOnlyAndPrefersNameMatch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE + "/v2/local/search/keyword.json")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("size", "5"))
                .andExpect(queryParam("sort", "accuracy"))
                .andExpect(queryParam("x", "126.99"))
                .andExpect(queryParam("y", "37.56"))
                .andRespond(withSuccess(PLACES, MediaType.APPLICATION_JSON));
        var lookup = new KakaoPlaceLookup("test-key", BASE, builder, mapper);

        Optional<KakaoPlace> place = lookup.findBest("노가리골목", 37.56, 126.99);

        assertThat(place).isPresent();
        KakaoPlace p = place.get();
        assertThat(p.id()).isEqualTo("3"); // 부산 결과 제외, 공백 무시 이름 일치 우선
        assertThat(p.lat()).isEqualTo(37.5660);
        assertThat(p.lng()).isEqualTo(126.9925);
        assertThat(p.address()).isEqualTo("서울 중구 을지로3가 95");
        assertThat(p.categoryName()).isEqualTo("관광명소");

        // 같은 요청은 캐시에서 (추가 HTTP 호출 없음)
        assertThat(lookup.findBest("노가리골목", 37.5601, 126.9901)).contains(p);
        server.verify();
    }

    @Test
    void placeFallsBackToFirstSeoulResultWhenNoNameMatch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(PLACES, MediaType.APPLICATION_JSON));
        var lookup = new KakaoPlaceLookup("test-key", BASE, builder, mapper);

        assertThat(lookup.findBest("전혀다른이름", null, null)).map(KakaoPlace::id).contains("2");
        server.verify();
    }

    @Test
    void placeEmptyWhenNoSeoulResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("""
                {"documents":[{"id":"9","place_name":"해운대","address_name":"부산 해운대구","x":"129.1","y":"35.1"}]}""",
                MediaType.APPLICATION_JSON));
        var lookup = new KakaoPlaceLookup("test-key", BASE, builder, mapper);

        assertThat(lookup.findBest("해운대", null, null)).isEmpty();
    }

    @Test
    void blankKeyReturnsEmptyWithoutCalling() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        assertThat(new KakaoPlaceLookup("  ", BASE, builder, mapper).findBest("경복궁", null, null)).isEmpty();
        assertThat(new KakaoImageLookup("", BASE, builder, mapper).findImageUrl("경복궁")).isEmpty();
        server.verify(); // 호출 없음
    }

    @Test
    void serverErrorReturnsEmptyAndIsNotCached() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        var lookup = new KakaoPlaceLookup("test-key", BASE, builder, mapper);

        assertThat(lookup.findBest("경복궁", null, null)).isEmpty();
        assertThat(lookup.findBest("경복궁", null, null)).isEmpty();
        server.verify();
    }

    @Test
    void imagePicksFirstLargeHttpsImage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE + "/v2/search/image")))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("size", "5"))
                .andExpect(queryParam("sort", "accuracy"))
                .andRespond(withSuccess("""
                        {"documents":[
                          {"image_url":"https://img.test/tiny.jpg","thumbnail_url":"https://t.test/1","width":120,"height":90},
                          {"image_url":"http://img.test/insecure.jpg","thumbnail_url":"https://t.test/2","width":800,"height":600},
                          {"image_url":"https://img.test/good.jpg","thumbnail_url":"https://t.test/3","width":1024,"height":768}
                        ]}""", MediaType.APPLICATION_JSON));
        var lookup = new KakaoImageLookup("test-key", BASE, builder, mapper);

        assertThat(lookup.findImageUrl("경복궁")).contains("https://img.test/good.jpg");
        assertThat(lookup.findImageUrl("경복궁")).contains("https://img.test/good.jpg"); // 캐시
        server.verify();
    }

    @Test
    void imageFallsBackToHttpsThumbnailThenEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("""
                {"documents":[{"image_url":"http://img.test/a.jpg","thumbnail_url":"https://t.test/a","width":640}]}""",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess("""
                {"documents":[{"image_url":"https://img.test/s.jpg","width":100}]}""", MediaType.APPLICATION_JSON));
        var lookup = new KakaoImageLookup("test-key", BASE, builder, mapper);

        assertThat(lookup.findImageUrl("남산타워")).contains("https://t.test/a");
        assertThat(lookup.findImageUrl("작은이미지")).isEmpty();
        server.verify();
    }

    @Test
    void imageServerErrorReturnsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(new KakaoImageLookup("test-key", BASE, builder, mapper).findImageUrl("경복궁")).isEmpty();
        server.verify();
    }
}
